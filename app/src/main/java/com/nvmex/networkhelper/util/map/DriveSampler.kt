package com.nvmex.networkhelper.util.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.LruCache
import com.amap.api.maps.AMap
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.network.formatCellType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DriveSampler(
    private val aMap: AMap,
    private val scope: CoroutineScope,
    private val getLatLng: () -> LatLng?,
    private val snapshots: () -> Map<Int, NetworkPanelUiState>,
    private val density: Float,

    // ===== 视觉大小（地图上看到的点）=====
    private val pointDp: Float = 18f,
    private val handoverDp: Float = 20f,

    // ===== 命中大小（手指点击好点）=====
    // bitmap 会做成这个大小（透明 padding），但中间图形仍按 pointDp/handoverDp 画
    private val pointHitDp: Float = 28f,
    private val handoverHitDp: Float = 30f,

    // ===== 采样频率 =====
    intervalMs: Long = 3000L,

    // ===== 上限：防止越跑越卡 =====
    private val maxMarkers: Int = 4000,

    // ===== Handover 回调：只在小区 key 变化时触发一次 =====
    private val onHandover: ((NetworkPanelUiState) -> Unit)? = null,

    // ===== 落盘回调（外部传进来） =====
    private val resolvePersistSessionId: (() -> String?)? = null,
    private val onPersist: (suspend (List<DrivePoint>, String) -> Unit)? = null,
    private val persistEnabled: () -> Boolean = { true },
    private val positionQuality: () -> Pair<Boolean, Float?> = { false to null },
    private val onPointSelected: ((DrivePoint) -> Unit)? = null,
    private val rawPosition: () -> Triple<Double?, Double?, String?> = { Triple(null, null, null) },
    private val mapMatchJson: () -> String? = { null },
    private val onCoverageChanged: (DriveCoverage) -> Unit = {}
) {

    @Volatile
    private var sampleIntervalMs: Long = intervalMs.coerceAtLeast(1000L)

    private var job: Job? = null
    private val writes = DrivePointWriteQueue<DrivePoint>(DrivePersistence.scope) { points, sid ->
        onPersist?.invoke(points, sid)
    }
    private val lastCellKeyBySlot = HashMap<Int, String>()
    private val pointsBySlot = HashMap<Int, MutableList<DrivePoint>>()
    private var visibleSimSlot: Int = 0
    private var showHandovers = true
    private var bandFilter = DriveBandFilter()
    private var segmentStartPending = true
    private var trackDirty = true
    private val trackLines = mutableListOf<Polyline>()
    private val contextTrackLines = mutableListOf<Polyline>()

    // marker -> point
    private val marker2Point = HashMap<Marker, DrivePoint>(1024)

    // FIFO：控制数量
    private val markerQueue = ArrayDeque<Marker>(1024)
    private val markerSelection = MapMarkerSelection(density)

    val isRunning: Boolean get() = job?.isActive == true

    // ===== icon 缓存 =====
    // key: "c|color|hitDp|visualDp" / "s|color|hitDp|visualDp"
    private val iconCache = object : LruCache<String, BitmapDescriptor>(96) {}

    fun start() {
        if (isRunning) return
        segmentStartPending = true
        job = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                runCatching { sampleOnce() }
                delay(sampleIntervalMs)
            }
        }
    }

    fun updateIntervalMs(intervalMs: Long) {
        sampleIntervalMs = intervalMs.coerceAtLeast(1000L)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun awaitPersistence(): Throwable? = writes.awaitIdle()

    fun captureNow() { runCatching { sampleOnce() } }

    fun setHandoverVisible(visible: Boolean) {
        showHandovers = visible
        refreshVisibleMarkers()
    }

    fun setBandFilter(filter: DriveBandFilter) {
        if (filter == bandFilter) return
        bandFilter = filter
        trackDirty = true
        clearVisibleMarkers()
        refreshVisibleMarkers()
    }

    fun refreshVisibleMarkers() {
        if (lastInfoMarker?.isRemoved == true) clearSelection()
        val points = pointsBySlot[visibleSimSlot].orEmpty()
        if (trackDirty) {
            val fixes = points.map { DriveTrackFix(it.ts, it.latLng.latitude, it.latLng.longitude, it.segmentStart) }
            onCoverageChanged(driveTrackCoverage(fixes) { bandFilter.matches(points[it].snapshot) })
            refreshTrackLines(points, fixes)
        }
        val bounds = runCatching { aMap.projection.visibleRegion.latLngBounds }.getOrNull()
        val dots = points.mapIndexedNotNull { index, p ->
            if (!bandFilter.matches(p.snapshot) || (bounds != null && !bounds.contains(p.latLng))) null
            else DriveDot(index, p.latLng.latitude, p.latLng.longitude, p.isHandover)
        }
        val selected = selectDriveDots(dots, aMap.cameraPosition.zoom, showHandovers, density, maxMarkers)
            .map { points[it] }.toMutableSet()
        // Camera thinning must not make the point under an open bubble disappear.
        marker2Point[lastInfoMarker]?.takeIf { bandFilter.matches(it.snapshot) && it in points &&
            (bounds == null || bounds.contains(it.latLng)) }?.let { selected.add(it) }
        marker2Point.entries.toList().forEach { (marker, p) ->
            if (p !in selected) {
                if (lastInfoMarker == marker) clearSelection()
                marker.remove()
                marker2Point.remove(marker)
                markerQueue.remove(marker)
            }
        }
        val existing = marker2Point.values.toHashSet()
        selected.forEach { if (it !in existing) addMarkerForPoint(it) }
    }

    fun clear() {
        clearSelection()
        markerQueue.forEach { runCatching { it.remove() } }
        markerQueue.clear()
        marker2Point.clear()
        pointsBySlot.clear()
        lastCellKeyBySlot.clear()
        clearTrackLines()
        onCoverageChanged(DriveCoverage())
    }

    fun setVisibleSimSlot(slot: Int) {
        val normalized = slot.coerceIn(0, 1)
        if (visibleSimSlot == normalized) return
        visibleSimSlot = normalized
        trackDirty = true
        clearVisibleMarkers()
        refreshVisibleMarkers()
    }

    private fun clearTrackLines() {
        trackLines.forEach { runCatching { it.remove() } }
        trackLines.clear()
        contextTrackLines.forEach { runCatching { it.remove() } }
        contextTrackLines.clear()
        trackDirty = true
    }

    private fun refreshTrackLines(points: List<DrivePoint>, fixes: List<DriveTrackFix>) {
        // Full valid route underneath the selected bands. Filtered gaps are context, not coverage.
        updateTrackLayer(points, if (bandFilter.active) driveTrackSegments(fixes) else emptyList(),
            contextTrackLines, signalColored = false)
        updateTrackLayer(points, driveTrackSegments(fixes) { bandFilter.matches(points[it].snapshot) },
            trackLines, signalColored = true)
        trackDirty = false
    }

    private fun updateTrackLayer(points: List<DrivePoint>, segments: List<List<Int>>,
        lines: MutableList<Polyline>, signalColored: Boolean) {
        var lineIndex = 0
        segments.forEach { indices ->
            // Overlap the endpoints so SDK-sized chunks still form a continuous route.
            var offset = 0
            while (offset < indices.lastIndex) {
                val chunk = indices.subList(offset, minOf(offset + 2000, indices.size)).map { points[it] }
                val options = PolylineOptions().addAll(chunk.map { it.latLng })
                    .width((if (signalColored) 3f else 2.5f) * density)
                    .zIndex(if (signalColored) 0.5f else 0f)
                if (signalColored) options.colorValues(chunk.map { DriveStyle.colorByRsrp(it.rsrpDbm) }).useGradient(true)
                else options.color(0xB08D969F.toInt())
                if (lineIndex < lines.size) lines[lineIndex].setOptions(options)
                else lines.add(aMap.addPolyline(options))
                lineIndex++
                offset += chunk.size - 1
            }
        }
        while (lines.size > lineIndex) lines.removeAt(lines.lastIndex).remove()
    }

    private fun clearVisibleMarkers() {
        clearSelection()
        markerQueue.forEach { runCatching { it.remove() } }
        markerQueue.clear()
        marker2Point.clear()
    }

    fun renderHistory(points: List<DrivePoint>, clearExisting: Boolean = false) {
        if (clearExisting) clear()
        points.groupBy { it.simSlot }.forEach { (slot, slotPoints) ->
            pointsBySlot.getOrPut(slot) { mutableListOf() }.apply {
                clear(); addAll(slotPoints.sortedBy { it.ts })
            }
        }
        clearVisibleMarkers()
        trackDirty = true
        refreshVisibleMarkers()
    }

    private fun sampleOnce() {
        val ll = getLatLng() ?: return
        val snapshotsNow = snapshots()
        val ts = System.currentTimeMillis()
        val batch = snapshotsNow.toSortedMap().map { (simSlot, s) ->
            sampleOnce(simSlot, s, ll, ts)
        }
        if (batch.isNotEmpty()) segmentStartPending = false
        val sid = resolvePersistSessionId?.invoke()
        if (batch.isNotEmpty() && persistEnabled() && onPersist != null && !sid.isNullOrBlank()) {
            writes.enqueue(batch, sid)
        }
        runCatching { refreshVisibleMarkers() }
    }

    private fun sampleOnce(simSlot: Int, s: NetworkPanelUiState, ll: LatLng, ts: Long): DrivePoint {

        // === 计算 cellKey / handover ===
        val key = DriveStyle.cellKey(s)
        val prev = lastCellKeyBySlot[simSlot]

        // 只有当 key 有意义且发生变化才算 handover
        val hasKey = !key.isNullOrBlank() && key != "-"
        val handover = hasKey && (prev != null) && (prev != key)

        // ✅ handover 时触发回调（例如：查基站库并画红三角）
        if (handover) {
            runCatching { onHandover?.invoke(s) }
        }

        // 更新 lastCellKey：只有 key 有意义才更新，否则保持上一次，避免抖动
        if (hasKey) lastCellKeyBySlot[simSlot] = key

        // === 解析 RSRP（NR 优先 SS-RSRP）===
        val rsrpDbm = DriveStyle.parseDbm(
            if (s.cellType.equals("NR", true)) {
                if (s.ssRsrp != "-" && s.ssRsrp.isNotBlank()) s.ssRsrp else s.rsrp
            } else {
                s.rsrp
            }
        )

        val p = DrivePoint(
            latLng = ll,
            ts = ts,
            simSlot = simSlot,
            rsrpDbm = rsrpDbm,
            isHandover = handover,
            cellKey = if (hasKey) key else "",
            snapshot = s,
            estimated = positionQuality().first,
            accuracyMeters = positionQuality().second,
            segmentStart = segmentStartPending,
            rawLat = rawPosition().first,
            rawLng = rawPosition().second,
            rawCoordType = rawPosition().third,
            mapMatchJson = mapMatchJson()
        )

        pointsBySlot.getOrPut(simSlot) { mutableListOf() }.apply {
            add(p)
        }
        if (simSlot == visibleSimSlot) trackDirty = true
        // A paused/unready map must never prevent either SIM from being persisted.

        return p
    }

    private fun trimIfNeeded() {
        while (markerQueue.size > maxMarkers) {
            val old = markerQueue.removeFirstOrNull() ?: break
            if (lastInfoMarker == old) clearSelection()
            runCatching { old.remove() }
            marker2Point.remove(old)
        }
    }

    private fun addMarkerForPoint(p: DrivePoint): Marker? {
        if (p.simSlot != visibleSimSlot) return null
        val color = DriveStyle.colorByRsrp(p.rsrpDbm)
        val icon = if (p.isHandover) {
            getIcon(isSquare = true, color = color, hitDp = handoverHitDp, visualDp = handoverDp)
        } else {
            getIcon(isSquare = false, color = color, hitDp = pointHitDp, visualDp = pointDp)
        }

        val marker = aMap.addMarker(
            MarkerOptions()
                .position(p.latLng)
                .icon(icon)
                .anchor(0.5f, 0.5f)
                .zIndex(if (p.isHandover) 10f else 1f)
        ) ?: return null

        marker2Point[marker] = p
        markerQueue.addLast(marker)
        trimIfNeeded()
        return marker
    }

    private var lastInfoMarker: Marker? = null
    private var lastMarkerClickTs: Long = 0L
    fun clearSelection() {
        markerSelection.clear()
        lastInfoMarker = null
    }

    fun bindClickToShowInfo() {
        aMap.setOnMapClickListener {
            // ✅ marker 点击后的极短时间内，忽略这次 map click（防止 show->hide 闪烁）
            val now = SystemClock.elapsedRealtime()
            if (now - lastMarkerClickTs < 250L) return@setOnMapClickListener

            clearSelection()
        }

        aMap.setOnMarkerClickListener { m ->
            lastMarkerClickTs = SystemClock.elapsedRealtime()

            // ✅ 先识别“基站红三角”（LteCellOverlayManager 已设置 m.`object` = row）
            val tag = m.`object`
            if (tag != null && marker2Point[m] == null) {
                // 基站 marker：你在 addMarker 时已经塞了 title/snippet，
                // 这里直接 show 就行（也可以在这里根据 tag 拼更详细的 snippet）
                markerSelection.select(m)
                lastInfoMarker = m
                m.showInfoWindow()
                return@setOnMarkerClickListener true
            }

            // ✅ 再识别采样点/切换点
            val p = marker2Point[m]
            if (p != null) {
                onPointSelected?.invoke(p)
                val s = p.snapshot
                val metrics = DriveSignalMetrics.fromState(s)
                m.title = "SIM${p.simSlot + 1}  " +
                        (if (p.isHandover) "切换点 ■" else "采样点 ●") +
                        "  RSRP=${p.rsrpDbm ?: "-"}"
                m.snippet =
                    "${metrics.rsrqLabel}=${metrics.rsrq?.let { "$it dB" } ?: "—"}  ${metrics.sinrLabel}=${metrics.sinr?.let { "$it dB" } ?: "—"}\n" +
                    "制式=${formatCellType(s.cellType, s.arfcn.toIntOrNull())}  网络=${s.dataNetworkType}\nTAC=${s.tac}  PCI=${s.pci}\nCI/NCI=${s.ci}\nARFCN=${s.arfcn}  BAND=${s.band}" +
                    "\n${mapChannelFrequency(s.cellType, s.arfcn.toIntOrNull())}" +
                    (if (p.estimated) "\n惯性估算 · 误差约 ${p.accuracyMeters?.toInt() ?: "-"} m" else "") +
                    (p.mapMatchJson?.let { json -> runCatching {
                        val m = com.google.gson.JsonParser.parseString(json).asJsonObject
                        "\n纠偏=${m["type"]?.asString} · ${m["status"]?.asString}\n纠偏WGS84=${m["matched_wgs84_lat"] ?: "—"}, ${m["matched_wgs84_lng"] ?: "—"}\n偏移=${m["offset_m"]}m；原始打点保留"
                    }.getOrDefault("") } ?: "")
                markerSelection.select(m)
                lastInfoMarker = m
                m.showInfoWindow()
                return@setOnMarkerClickListener true
            }

            false
        }
    }

    // ===== icon 获取：缓存优先 =====
    private fun getIcon(isSquare: Boolean, color: Int, hitDp: Float, visualDp: Float): BitmapDescriptor {
        val key = buildString {
            append(if (isSquare) 's' else 'c')
            append('|').append(color)
            append('|').append(hitDp)
            append('|').append(visualDp)
        }

        iconCache.get(key)?.let { return it }

        val bmp = drawShapeBitmap(color, isSquare, hitDp, visualDp)
        val desc = BitmapDescriptorFactory.fromBitmap(bmp)
        iconCache.put(key, desc)
        return desc
    }

    /**
     * hitDp: bitmap 尺寸（命中区域，透明 padding）
     * visualDp: 中间图形尺寸（视觉大小）
     */
    private fun drawShapeBitmap(color: Int, isSquare: Boolean, hitDp: Float, visualDp: Float): Bitmap {
        val hitPx = dpToPx(hitDp).coerceAtLeast(dpToPx(24f))
        val bmp = Bitmap.createBitmap(hitPx, hitPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = 0xFFFFFFFF.toInt()
            strokeWidth = dpToPxF(1.5f).coerceAtLeast(1f)
        }

        val cx = hitPx / 2f
        val visualPx = dpToPxF(visualDp).coerceAtLeast(dpToPxF(6f))
        val half = visualPx / 2f

        if (isSquare) {
            val left = cx - half
            val top = cx - half
            val right = cx + half
            val bottom = cx + half
            val r = visualPx * 0.18f
            c.drawRoundRect(left, top, right, bottom, r, r, fill)
            c.drawRoundRect(left, top, right, bottom, r, r, stroke)
        } else {
            val radius = visualPx * 0.33f
            c.drawCircle(cx, cx, radius, fill)
        }

        return bmp
    }

    private fun dpToPx(dp: Float): Int = (dp * density + 0.5f).toInt()
    private fun dpToPxF(dp: Float): Float = dp * density
}
