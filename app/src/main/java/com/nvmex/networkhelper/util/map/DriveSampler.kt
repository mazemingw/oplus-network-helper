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
import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
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
    private val onPersist: (suspend (DrivePoint, String) -> Unit)? = null,
    private val persistEnabled: () -> Boolean = { true }
) {

    @Volatile
    private var sampleIntervalMs: Long = intervalMs.coerceAtLeast(1000L)

    private var job: Job? = null
    private val lastCellKeyBySlot = HashMap<Int, String>()
    private val pointsBySlot = HashMap<Int, MutableList<DrivePoint>>()
    private var visibleSimSlot: Int = 0

    // marker -> point
    private val marker2Point = HashMap<Marker, DrivePoint>(1024)

    // FIFO：控制数量
    private val markerQueue = ArrayDeque<Marker>(1024)
    private val markerQueueBySlot = HashMap<Int, ArrayDeque<Marker>>()

    val isRunning: Boolean get() = job?.isActive == true

    // ===== icon 缓存 =====
    // key: "c|color|hitDp|visualDp" / "s|color|hitDp|visualDp"
    private val iconCache = object : LruCache<String, BitmapDescriptor>(96) {}

    fun start() {
        if (isRunning) return
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

    fun clear() {
        markerQueue.forEach { runCatching { it.remove() } }
        markerQueue.clear()
        marker2Point.clear()
        markerQueueBySlot.clear()
        pointsBySlot.clear()
        lastCellKeyBySlot.clear()
    }

    fun setVisibleSimSlot(slot: Int) {
        val normalized = slot.coerceIn(0, 1)
        if (visibleSimSlot == normalized) return
        visibleSimSlot = normalized
        clearVisibleMarkers()
        pointsBySlot[visibleSimSlot].orEmpty().sortedBy { it.ts }.forEach { addMarkerForPoint(it) }
    }

    private fun clearVisibleMarkers() {
        markerQueue.forEach { runCatching { it.remove() } }
        markerQueue.clear()
        marker2Point.clear()
        markerQueueBySlot.clear()
    }

    fun renderHistory(points: List<DrivePoint>, clearExisting: Boolean = false) {
        if (clearExisting) clear()
        points.groupBy { it.simSlot }.forEach { (slot, slotPoints) ->
            pointsBySlot.getOrPut(slot) { mutableListOf() }.apply {
                clear(); addAll(slotPoints.sortedBy { it.ts })
            }
        }
        pointsBySlot[visibleSimSlot].orEmpty().forEach { addMarkerForPoint(it) }
    }

    private fun sampleOnce() {
        val ll = getLatLng() ?: return
        val snapshotsNow = snapshots().ifEmpty { mapOf(0 to NetworkPanelUiState()) }
        snapshotsNow.toSortedMap().forEach { (simSlot, s) ->
            sampleOnce(simSlot, s, ll)
        }
    }

    private fun sampleOnce(simSlot: Int, s: NetworkPanelUiState, ll: LatLng) {

        // === 计算 cellKey / handover ===
        val key = DriveStyle.cellKey(s)
        val prev = lastCellKeyBySlot[simSlot]

        // 只有当 key 有意义且发生变化才算 handover
        val hasKey = !key.isNullOrBlank() && key != "-"
        val handover = hasKey && (prev != null) && (prev != key)

        // ✅ handover 时触发回调（例如：查基站库并画红三角）
        if (handover) {
            onHandover?.invoke(s)
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
            ts = System.currentTimeMillis(),
            simSlot = simSlot,
            rsrpDbm = rsrpDbm,
            isHandover = handover,
            cellKey = if (hasKey) key else "",
            snapshot = s
        )

        pointsBySlot.getOrPut(simSlot) { mutableListOf() }.add(p)
        if (simSlot == visibleSimSlot) addMarkerForPoint(p) else null

        // 落盘：放 IO 线程，避免卡 UI
        val sid = resolvePersistSessionId?.invoke()
        if (persistEnabled() && onPersist != null && !sid.isNullOrBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching { onPersist.invoke(p, sid) }
            }
        }
    }

    private fun trimIfNeeded() {
        while (markerQueue.size > maxMarkers) {
            val old = markerQueue.removeFirstOrNull() ?: break
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
        markerQueueBySlot.getOrPut(p.simSlot) { ArrayDeque() }.addLast(marker)
        trimIfNeeded()
        return marker
    }

    private var lastInfoMarker: Marker? = null
    private var lastMarkerClickTs: Long = 0L
    fun bindClickToShowInfo() {
        aMap.setOnMapClickListener {
            // ✅ marker 点击后的极短时间内，忽略这次 map click（防止 show->hide 闪烁）
            val now = SystemClock.elapsedRealtime()
            if (now - lastMarkerClickTs < 250L) return@setOnMapClickListener

            lastInfoMarker?.hideInfoWindow()
            lastInfoMarker = null
        }

        aMap.setOnMarkerClickListener { m ->
            lastMarkerClickTs = SystemClock.elapsedRealtime()

            val old = lastInfoMarker
            if (old != null && old != m) runCatching { old.hideInfoWindow() }
            lastInfoMarker = m

            // ✅ 先识别“基站红三角”（LteCellOverlayManager 已设置 m.`object` = row）
            val tag = m.`object`
            if (tag != null && marker2Point[m] == null) {
                // 基站 marker：你在 addMarker 时已经塞了 title/snippet，
                // 这里直接 show 就行（也可以在这里根据 tag 拼更详细的 snippet）
                m.showInfoWindow()
                return@setOnMarkerClickListener true
            }

            // ✅ 再识别采样点/切换点
            val p = marker2Point[m]
            if (p != null) {
                val s = p.snapshot
                m.title = "SIM${p.simSlot + 1}  " +
                        (if (p.isHandover) "切换点 ■" else "采样点 ●") +
                        "  RSRP=${p.rsrpDbm ?: "-"}"
                m.snippet =
                    "制式=${s.cellType}  网络=${s.dataNetworkType}\nTAC=${s.tac}  PCI=${s.pci}\nCI/NCI=${s.ci}\nARFCN=${s.arfcn}  BAND=${s.band}"
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
