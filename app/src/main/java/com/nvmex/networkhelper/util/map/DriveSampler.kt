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
import com.nvmex.networkhelper.util.map.matching.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class DriveSampler(
    private val aMap: AMap,
    private val scope: CoroutineScope,
    private val position: () -> DriveSamplePosition,
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
    private val onPointSelected: ((DrivePoint) -> String?)? = null,
    private val onCoverageChanged: (DriveCoverage) -> Unit = {}
) {

    @Volatile
    private var sampleIntervalMs: Long = intervalMs.coerceAtLeast(1000L)

    private var job: Job? = null
    private val writes = DrivePointWriteQueue<DrivePoint>(DrivePersistence.scope) { points, sid ->
        onPersist?.invoke(points, sid)
    }
    private val sampling = DriveSamplingBatch()
    private val pointsBySlot = HashMap<Int, MutableList<DrivePoint>>()
    private var visibleSimSlot: Int = 0
    private var showHandovers = true
    private var bandFilter = DriveBandFilter()
    private var trackDirty = true
    private val trackLines = mutableListOf<Polyline>()
    private val contextTrackLines = mutableListOf<Polyline>()
    private val contextCasings = mutableListOf<Polyline>()

    // marker -> point
    private val marker2Point = HashMap<Marker, DrivePoint>(1024)

    // FIFO：控制数量
    private val markerQueue = ArrayDeque<Marker>(1024)
    private val markerSelection = MapMarkerSelection(density)
    private val displayCoordinates = java.util.IdentityHashMap<DrivePoint, LatLng>()

    val isRunning: Boolean get() = job?.isActive == true

    // ===== icon 缓存 =====
    // key: "c|color|hitDp|visualDp" / "s|color|hitDp|visualDp"
    private val iconCache = object : LruCache<String, BitmapDescriptor>(96) {}

    fun start() {
        if (isRunning) return
        sampling.startSegment()
        job = scope.launch(Dispatchers.Main.immediate) {
            runDriveSamplingLoop({ isActive }, { sampleIntervalMs }, { sampleOnce() },
                { driveLog("sample failed: ${it.message}") })
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

    fun captureNow() { runCatching { sampleOnce() }.onFailure { driveLog("capture failed: ${it.message}") } }

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
        refreshSelectedStationMarker()
        val points = pointsBySlot[visibleSimSlot].orEmpty()
        if (trackDirty) {
            val fixes = points.map { DriveTrackFix(it.ts, it.latLng?.latitude, it.latLng?.longitude, it.segmentStart) }
            onCoverageChanged(driveTrackCoverage(fixes) { bandFilter.matches(points[it].snapshot) })
            refreshTrackLines(points, fixes)
        }
        val bounds = runCatching { aMap.projection.visibleRegion.latLngBounds }.getOrNull()
        val dots = points.mapIndexedNotNull { index, p ->
            if (p.latLng == null) return@mapIndexedNotNull null
            val position = displayPosition(p)
            if (!bandFilter.matches(p.snapshot) || (bounds != null && !bounds.contains(position))) null
            else DriveDot(index, position.latitude, position.longitude, p.isHandover)
        }
        val selected = selectDriveDots(dots, aMap.cameraPosition.zoom, showHandovers, density, maxMarkers)
            .map { points[it] }.toMutableSet()
        // Camera thinning must not make the point under an open bubble disappear.
        marker2Point[lastInfoMarker]?.takeIf { bandFilter.matches(it.snapshot) && it in points &&
            (bounds == null || bounds.contains(displayPosition(it))) }?.let { selected.add(it) }
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
        displayCoordinates.clear()
        markerQueue.forEach { runCatching { it.remove() } }
        markerQueue.clear()
        marker2Point.clear()
        pointsBySlot.clear()
        sampling.clear()
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
        contextCasings.forEach { runCatching { it.remove() } }; contextCasings.clear()
        trackDirty = true
    }

    private fun refreshTrackLines(points: List<DrivePoint>, fixes: List<DriveTrackFix>) {
        // Full valid route underneath the selected bands. Filtered gaps are context, not coverage.
        val context = if (bandFilter.active) driveTrackSegments(fixes) else emptyList()
        updateTrackLayer(points, context, contextCasings, signalColored = false, casing = true)
        updateTrackLayer(points, context,
            contextTrackLines, signalColored = false)
        updateTrackLayer(points, driveTrackSegments(fixes) { bandFilter.matches(points[it].snapshot) },
            trackLines, signalColored = true)
        trackDirty = false
    }

    private fun updateTrackLayer(points: List<DrivePoint>, segments: List<List<Int>>,
        lines: MutableList<Polyline>, signalColored: Boolean, casing: Boolean = false) {
        var lineIndex = 0
        segments.forEach { indices ->
            // Overlap the endpoints so SDK-sized chunks still form a continuous route.
            var offset = 0
            while (offset < indices.lastIndex) {
                val chunk = indices.subList(offset, minOf(offset + 2000, indices.size)).map { points[it] }
                val options = PolylineOptions().addAll(chunk.map(::displayPosition))
                    .width((if (casing) 5.5f else 3f) * density)
                    .zIndex(if (signalColored) 0.5f else if (casing) -.1f else 0f)
                if (signalColored) options.colorValues(chunk.map { DriveStyle.colorByRsrp(it.rsrpDbm) }).useGradient(true)
                else options.color(if (casing) 0xEE30213F.toInt() else 0xFFE082FF.toInt())
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

    fun recordedPoints(): List<DrivePoint> = pointsBySlot.values.flatten()

    private fun displayPosition(point: DrivePoint): LatLng = displayCoordinates.getOrPut(point) {
        val raw = requireNotNull(point.latLng) // Only located samples reach marker/line rendering.
        val matched = if (point.estimated) null else falconRecordedPosition(point.mapMatchJson)
        if (matched != null && driveDistanceMeters(raw.latitude, raw.longitude, matched.lat, matched.lng) <= 100)
            LatLng(matched.lat, matched.lng) else raw
    }

    /** Update cloud metadata and presentation only. The raw sample and coverage denominator stay intact. */
    fun applyFalconTrace(trace: FalconTrace): List<FalconPointUpdate> {
        val index = FalconCorrectionIndex(trace)
        val updates = mutableListOf<FalconPointUpdate>()
        val replaced = mutableMapOf<Pair<Int, Long>, DrivePoint>()
        pointsBySlot.values.forEach { points ->
            for (i in points.indices) {
                val point = points[i]
                val coordinate = point.latLng ?: continue
                val metadata = index.apply(point.mapMatchJson, point.ts,
                    RoutePosition(coordinate.latitude, coordinate.longitude), (point.accuracyMeters ?: 100f).toDouble(), point.estimated)
                if (metadata != null) {
                    val updated = point.copy(mapMatchJson = metadata)
                    points[i] = updated; displayCoordinates.remove(point)
                    replaced[point.simSlot to point.ts] = updated
                    updates.add(FalconPointUpdate(point.ts, point.simSlot, metadata))
                }
            }
        }
        if (updates.isNotEmpty()) {
            marker2Point.entries.forEach { entry ->
                replaced[entry.value.simSlot to entry.value.ts]?.let { point ->
                    entry.setValue(point); entry.key.position = displayPosition(point)
                }
            }
            trackDirty = true
            refreshVisibleMarkers()
        }
        return updates
    }

    private fun sampleOnce() {
        val fix = position()
        val batch = sampling.sample(System.currentTimeMillis(), snapshots(), fix)
        batch.forEach { point ->
            pointsBySlot.getOrPut(point.simSlot) { mutableListOf() }.add(point)
            if (point.simSlot == visibleSimSlot) trackDirty = true
            if (point.isHandover) runCatching { onHandover?.invoke(point.snapshot) }
        }
        val sid = resolvePersistSessionId?.invoke()
        val persist = batch.isNotEmpty() && persistEnabled() && onPersist != null && !sid.isNullOrBlank()
        if (persist) writes.enqueue(batch, sid!!)
        driveLog("sample source=${fix.source} measuredAt=${fix.measuredAt} radio=${batch.size} located=${batch.count { it.latLng != null }} queued=$persist reason=${fix.reason}")
        runCatching { refreshVisibleMarkers() }.onFailure { driveLog("render failed: ${it.message}") }
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
                .position(displayPosition(p))
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
    var onMapMarkerSelected: ((Marker?) -> Unit)? = null
    var resolveStationMarker: (() -> Marker?)? = null

    fun refreshSelectedStationMarker() {
        val marker = lastInfoMarker ?: return
        if (!marker.isRemoved) return
        val replacement = if (marker2Point[marker] == null) resolveStationMarker?.invoke() else null
        if (replacement != null && !replacement.isRemoved) selectStationMarker(replacement) else clearSelection()
    }

    fun selectStationMarker(marker: Marker) {
        onMapMarkerSelected?.invoke(marker)
        markerSelection.select(marker)
        lastInfoMarker = marker
        marker.showInfoWindow()
    }

    fun clearSelection() {
        markerSelection.clear()
        lastInfoMarker = null
        onMapMarkerSelected?.invoke(null)
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
                selectStationMarker(m)
                return@setOnMarkerClickListener true
            }

            // ✅ 再识别采样点/切换点
            val p = marker2Point[m]
            if (p != null) {
                onMapMarkerSelected?.invoke(m)
                val stationInfo = onPointSelected?.invoke(p.copy(latLng = m.position))
                val s = p.snapshot
                val metrics = DriveSignalMetrics.fromState(s)
                m.title = "SIM${p.simSlot + 1}  " +
                        (if (p.isHandover) "切换点 ■" else "采样点 ●") +
                        "  RSRP=${p.rsrpDbm ?: "-"}"
                m.snippet =
                    "${metrics.rsrqLabel}=${metrics.rsrq?.let { "$it dB" } ?: "—"}  ${metrics.sinrLabel}=${metrics.sinr?.let { "$it dB" } ?: "—"}\n" +
                    "制式=${formatCellType(s.cellType, s.arfcn.toIntOrNull())}  网络=${s.dataNetworkType}\nTAC=${s.tac}  PCI=${s.pci}\nCI/NCI=${s.ci}\nARFCN=${s.arfcn}  BAND=${s.band}" +
                    "\n${mapChannelFrequency(s.cellType, s.arfcn.toIntOrNull())}" +
                    "\n定位来源=${p.positionSource}" +
                    (if (p.estimated) "\n惯导估算 · 漂移指示 ${p.accuracyMeters?.toInt() ?: "-"} m（非实测精度）" else "") +
                    (p.mapMatchJson?.let { json -> runCatching {
                        val m = com.google.gson.JsonParser.parseString(json).asJsonObject
                        "\n纠偏=${m["type"]?.asString} · ${m["status"]?.asString}\n纠偏WGS84=${m["matched_wgs84_lat"] ?: "—"}, ${m["matched_wgs84_lng"] ?: "—"}\n偏移=${m["offset_m"]}m；原始打点保留"
                    }.getOrDefault("") } ?: "") +
                    (stationInfo?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
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
