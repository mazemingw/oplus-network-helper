import android.graphics.Paint
import android.text.TextPaint
import com.amap.api.maps.AMap
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.nvmex.networkhelper.model.menu.LteCellQueryBody
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.map.LteSiteQueryResp
import com.nvmex.networkhelper.repository.CellParamLocalRepository
import com.nvmex.networkhelper.util.map.CellOverlayCacheDao
import com.nvmex.networkhelper.util.map.CellOverlayCacheEntity
import com.nvmex.networkhelper.util.map.Gcj02
import com.nvmex.networkhelper.util.map.lteEarfcnToFreqMhzCN
import com.nvmex.networkhelper.util.map.makeRedTriangleIcon
import com.nvmex.networkhelper.xposed.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ServerCoord { WGS84, GCJ02 }

class LteCellOverlayManager(
    private val aMap: AMap,
    private val api: ApiService,
    private val localRepository: CellParamLocalRepository,
    private val scope: CoroutineScope,
    private val cacheDao: CellOverlayCacheDao,
    private val cacheTtlMs: Long = 7L * 24 * 3600_000L,
    private val serverCoord: ServerCoord = ServerCoord.WGS84,
) {
    private val icon by lazy { makeRedTriangleIcon(56) }

    // 你之前的容器还留着（如果别处不用可删）
    private val markers = ArrayList<Marker>(32)
    private val lines = ArrayList<Polyline>(32)
    private val sectors = ArrayList<Polygon>(64)

    private var lastQueryKey: String? = null


    // ===== 扇区绘制策略 =====
    // 半径（示意）
    private var sectorRadiusMeters: Double = 200.0
    private var sectorAngleDeg: Float = 120f
    // 弧线采样点数（越大越圆）
    private val sectorSteps: Int = 14
    // zIndex：扇区与红线同层，三角形更高一层
    private val sectorZ = 27f
    private val triangleZ = 30f

    // ✅ stationKey -> node，用于 O(1) 判重
    private val historyMap = HashMap<String, BsNode>(256)

    // 颜色
    private val sectorFillColor = 0x22FF0000.toInt() // 半透明红
    private val sectorStrokeColor = 0x55FFFFFF        // 半透明白
    private val lineColor = 0x22FF0000.toInt()        // 红线淡
    private val lineWidth = 4f
    // ===== 延迟绘制控制 =====
    private var mapLoaded = false

    // 如果回包早于 mapLoaded，就先暂存一次（只保留最后一次即可）
    private var pendingSite: Pair<LteSiteQueryResp, Pair<Int, Int>>? = null
    private var pendingCache: Pair<List<CellOverlayCacheEntity>, Pair<Int, Int>>? = null

    private var mapLoadedListenerBound = false
    private var flushing = false // 防止 flush 重入

    private var lastQueryAt: Long = 0L
    private val minQueryIntervalMs = 1500L  // 1.5s 你可调：1000~3000都行
    private var lastStableKey: String? = null
    // ===== azimuth 聚类参数 =====
    private val azimuthClusterTolDeg: Float = 22f
    private val treatZeroAzimuthAsMissing: Boolean = true

    // 上限：保留最近 N 个基站
    private val maxHistoryMarkers: Int = 300
    // 是否保留历史
    private val keepHistory: Boolean = true

    // ===== 历史节点（一个基站一个 marker + 多扇区）=====
    private data class BsNode(
        val key: String,
        val marker: Marker,
        val sectors: List<Polygon>,
        val lines: List<Polyline>,
        val labels: List<Marker>, // ✅ 新增：扇区标签
    )

    private val history = ArrayDeque<BsNode>(128)

    fun updateSectorDrawConfig(angleDeg: Float, radiusMeters: Double) {
        sectorAngleDeg = angleDeg.coerceIn(20f, 180f)
        sectorRadiusMeters = radiusMeters.coerceIn(50.0, 2000.0)
    }

    fun clear() {
        history.forEach { node ->
            runCatching { node.marker.remove() }
            node.sectors.forEach { runCatching { it.remove() } }
            node.lines.forEach { runCatching { it.remove() } }
            node.labels.forEach { runCatching { it.remove() } }
        }
        history.clear()
        historyMap.clear() // ✅ 新增：判重索引也要清

        markers.clear()
        lines.clear()
        sectors.clear()

        // ✅ 关键：清掉去重/挂起，否则后续相同 key 会被直接 return
        lastQueryKey = null
        pendingSite = null
        pendingCache = null
    }
    // ============= 对外调用：延迟绘制 =============
    fun bindMapLoadedListener() {
        if (mapLoadedListenerBound) return
        mapLoadedListenerBound = true

        aMap.setOnMapLoadedListener {
            mapLoaded = true
            Logger.log("LteOverlay: mapLoaded=true, flush pending... overlay=${System.identityHashCode(this)}")
            flushPendingWithRetry()
        }
    }
    // ============= 对外调用：查询并绘制 =============
    fun queryAndShow(tac: Int?, earfcn: Int?, eci: Long?, pci: Int?, cellId: Int?) {
        if (tac == null || earfcn == null) return

        val now = System.currentTimeMillis()

        // ✅ 强标识：至少有一个相对稳定定位小区
        val hasStrongId = (eci != null && eci > 0) || (cellId != null) || (pci != null)

        // ✅ 只有强标识足够时才生成 key；否则不让它进入 x/x/x 的死锁
        val preciseKey = if (hasStrongId) {
            buildOverlayPreciseKey(tac, earfcn, eci, pci, cellId)
        } else null

        // ✅ 关键：判断是否切小区
        // - key 为空：认为不稳定，不触发“立即放行”
        // - key 非空且变化：立刻放行（绕过冷却）
        val keyChanged = (preciseKey != null && preciseKey != lastStableKey)

        // ✅ 冷却策略升级：
        // - 同 key（或 key 不稳定）时才做 minQueryIntervalMs 冷却
        // - key 变化（切小区）则立即查询
        if (!keyChanged && (now - lastQueryAt) < minQueryIntervalMs) return

        // ✅ 记录时间与 key（注意：只有 key 非空才更新 lastStableKey）
        lastQueryAt = now
        if (preciseKey != null) {
            lastStableKey = preciseKey
        }

        // ✅ 缓存 key：只要 eci 有值就锁死在该 eci 上
        val baseKey = buildOverlayBaseKey(tac, earfcn, eci, pci, cellId)

        scope.launch(Dispatchers.IO) {
            // 1) 读缓存
            val cached0 = cacheDao.listByBaseKey(baseKey)

            // ✅ 止血：如果本次有 eci，则缓存必须 eci 完全匹配，否则直接丢弃
            val cached = if (eci != null && eci > 0) cached0.filter { it.eci == eci } else cached0

            val ttlOk = cached.isNotEmpty() && (now - (cached.maxOf { it.ts })) <= cacheTtlMs
            if (ttlOk) {
                withContext(Dispatchers.Main.immediate) {
                    if (!mapLoaded) {
                        pendingCache = cached to (tac to earfcn)
                        Logger.log("LteOverlay: cache ready but map not loaded, pendingCache saved key=$baseKey size=${cached.size}")
                    } else {
                        renderFromCacheAsLegacy(cached)
                    }
                }
                return@launch
            }

            // 2) 请求新接口
            val body = LteCellQueryBody(
                tac = tac,
                earfcn = earfcn,
                eci = eci,
                pci = pci,
                cell_id = cellId
            )

            val localSite = runCatching { localRepository.queryLteSite(body) }.getOrNull()
            if (localSite != null) {
                val renderedLocal = withContext(Dispatchers.Main.immediate) {
                    if (!mapLoaded) {
                        pendingSite = localSite to (tac to earfcn)
                        Logger.log("LteOverlay: local site ready but map not loaded, pendingSite key=$baseKey rows=${localSite.data.size}")
                        true
                    } else {
                        renderSiteInternal(localSite, requestTac = tac, requestEarfcn = earfcn)
                    }
                }
                if (renderedLocal) {
                    Logger.log("LteOverlay: rendered from local sqlite key=$baseKey rows=${localSite.data.size}")
                    return@launch
                }
            }

            val resp = runCatching { api.queryLteSite(body) }.getOrNull()
            val site = resp?.takeIf { it.isSuccessful }?.body() ?: return@launch

            runCatching { localRepository.saveLteSiteQueryResp(site) }
                .onFailure { e ->
                    Logger.log("LteOverlay: save local sqlite failed ${e.message}")
                }

            withContext(Dispatchers.Main.immediate) {
                if (!mapLoaded) {
                    pendingSite = site to (tac to earfcn)
                    Logger.log("LteOverlay: site ready but map not loaded, pendingSite saved key=$baseKey rows=${site.data.size}")
                } else {
                    renderSiteInternal(site, requestTac = tac, requestEarfcn = earfcn)
                }
            }

            // 3) 落库缓存（仍按你原方案：最多 8 条）
            val rows = site.data
                .filter { it.latitude != null && it.longitude != null }
                .take(8)

            if (rows.isEmpty()) return@launch

            // ✅ 重要：不许落库
//        cacheDao.deleteByBaseKey(baseKey)

            val entities = rows.mapIndexed { idx, r ->
                CellOverlayCacheEntity(
                    key = "$baseKey#$idx",
                    baseKey = baseKey,

                    // ✅ 用 row 自己的值，避免“按 ECI 查但 earfcn 被请求参数污染”
                    tac = r.tac ?: tac,
                    earfcn = r.earfcn ?: earfcn,
                    eci = r.eci ?: eci,
                    pci = r.pci ?: pci,

                    lat = r.latitude!!,
                    lng = r.longitude!!,
                    azimuth = r.azimuth,
                    enodebId = r.enodeb_id,
                    cellId = r.cell_id,
                    cellName = r.cell_name,
                    source = r.source ?: "api",
                    ts = now
                )
            }
            //不许落库
//        cacheDao.upsertAll(entities)
        }
    }

    // ============= 新渲染：同站拼基站（一个 marker + 多扇区） =============
    private fun renderSiteInternal(resp: LteSiteQueryResp, requestTac: Int, requestEarfcn: Int): Boolean {
        if (!keepHistory) clear()

        val grouped = groupRowsByStation(resp, requestTac)
        if (grouped.isEmpty()) {
            Logger.log("LteOverlay: grouped empty (no lat/lng?) respRows=${resp.data.size}")
            return false
        }

        var anyOk = false

        grouped.forEach { (key, rows) ->
            val anchor = rows.firstOrNull { !it.cell_name.isNullOrBlank() }
                ?: rows.firstOrNull()
                ?: return@forEach

            Logger.log("LteOverlay站点信息: groups=${grouped.size}, respRows=${resp.data.size}, stationRows=${rows.size}")

            // ✅ 稳定 stationKey：别拼 idx！不然顺序一变就重复绘制
            val stationKey = "api|$key"

            val ok = renderOneStation(
                stationKey = stationKey,
                anchorRow = anchor,
                rows = rows,
                requestTac = requestTac,
                requestEarfcn = requestEarfcn,
                headerMatch = resp.match_level
            )
            anyOk = anyOk || ok
        }

        return anyOk
    }

    //站点分组函数（按 enb 优先，否则按经纬度网格）
    private fun groupRowsByStation(resp: LteSiteQueryResp, requestTac: Int): List<Pair<String, List<LteCellParamRow>>> {
        val rows = resp.data
            .filter { it.latitude != null && it.longitude != null }
            .map { it.asRow() }

        if (rows.isEmpty()) return emptyList()

        // 先看 enodeb_id 是否可靠
        val hasEnb = rows.any { it.enodeb_id != null && it.enodeb_id!! > 0 }

        return if (hasEnb) {
            rows.groupBy { r ->
                val enb = r.enodeb_id ?: -1
                "enb=$enb|tac=$requestTac"
            }.toList()
        } else {
            // 退化：经纬度网格聚类（约 120m 一格，避免多站点混一起）
            fun gridKey(lat: Double, lon: Double): String {
                val scale = 200.0 // 1/200 deg ~ 555m（纬度方向），更不容易抖动跨格
                val glat = kotlin.math.floor(lat * scale).toInt()
                val glon = kotlin.math.floor(lon * scale).toInt()
                return "g=$glat,$glon|tac=$requestTac"
            }

            rows.groupBy { r ->
                val lat = r.latitude!!
                val lon = r.longitude!!
                gridKey(lat, lon)
            }.toList()
        }
    }

    //参数化绘制单个站点
    private fun renderOneStation(
        stationKey: String,
        anchorRow: LteCellParamRow,
        rows: List<LteCellParamRow>,
        requestTac: Int,
        requestEarfcn: Int,
        headerMatch: String? = null
    ): Boolean {
        val lat0 = anchorRow.latitude ?: return false
        val lon0 = anchorRow.longitude ?: return false

        val (lat, lon) = when (serverCoord) {
            ServerCoord.WGS84 -> Gcj02.wgs84ToGcj02(lat0, lon0)
            ServerCoord.GCJ02 -> lat0 to lon0
        }
        val pos = LatLng(lat, lon)

        val title = buildString {
            append("基站：")
            append(anchorRow.cell_name ?: "LTE Site")
            anchorRow.enodeb_id?.let { append("  enb=").append(it) }
        }

        val snippet = buildString {
            if (!headerMatch.isNullOrBlank()) append("match=").append(headerMatch).append('\n')
            append("count=").append(rows.size).append('\n')
            append("tac=").append(requestTac).append("  earfcn=").append(requestEarfcn).append('\n')
            append("pci=").append(anchorRow.pci).append("  eci=").append(anchorRow.eci).append('\n')
            append("src=").append(anchorRow.source ?: "-")
        }

        // 去重
        historyMap[stationKey]?.let { existing ->
            existing.marker.title = title
            existing.marker.snippet = snippet
            return true
        }

        val m = aMap.addMarker(
            MarkerOptions()
                .position(pos)
                .icon(icon)
                .anchor(0.5f, 1.0f)
                .zIndex(triangleZ)
                .title(title)
                .snippet(snippet)
        ) ?: run {
            Logger.log("LteOverlay: addMarker=null (map not ready) stationKey=$stationKey")
            return false
        }
        m.`object` = anchorRow

        val azGroups = groupByAzimuthCluster(rows)
        val groups = if (azGroups.isNotEmpty()) azGroups else groupBySectorId(rows)

        if (groups.isEmpty()) {
            val node = BsNode(stationKey, m, emptyList(), emptyList(), emptyList())
            history.addLast(node)
            historyMap[stationKey] = node
            trimHistoryIfNeeded()
            return true
        }

        val onlyOne = groups.size == 1
        val onlyAz = groups.first().azimuth
        val shouldDrawCircle = onlyOne && (onlyAz == null || onlyAz == 0f)

        val polys = ArrayList<Polygon>(8)
        val plines = ArrayList<Polyline>(8)
        val labels = ArrayList<Marker>(8)

        fun labelRadius(idx: Int): Double {
            val base = sectorRadiusMeters * 0.98
            val bump = sectorRadiusMeters * (0.03 * (idx % 3))
            return base + bump
        }

        if (shouldDrawCircle) {
            // 微站：绘制全向圆
            val circlePts = buildCirclePoints(pos, sectorRadiusMeters, 36)
            aMap.addPolygon(
                PolygonOptions()
                    .addAll(circlePts)
                    .strokeWidth(2f)
                    .strokeColor(sectorStrokeColor)
                    .fillColor(sectorFillColor)
                    .zIndex(sectorZ)
            )?.let { polys.add(it) }

            // 微站标签（三角形下方）
            val labelText = buildBandLabel(rows)
            if (labelText.isNotBlank()) {
                val z = aMap.cameraPosition.zoom
                val meters = when {
                    z >= 18f -> 10.0   // 调近
                    z >= 16f -> 16.0
                    else -> 25.0
                }
                val labelPos = moveLatLng(pos, 180f, meters)
                aMap.addMarker(
                    MarkerOptions()
                        .position(labelPos)
                        .icon(makeTextIcon(labelText))
                        .anchor(0.5f, 0.0f)
                        .zIndex(triangleZ + 1f)
                        .setFlat(true)
                )?.let { labels.add(it) }
            }

            val node = BsNode(stationKey, m, polys, plines, labels)
            history.addLast(node)
            historyMap[stationKey] = node
            trimHistoryIfNeeded()
            return true
        }

        // 宏站：多扇区
        val sectorWidth = sectorAngleDeg
        val halfAngle = sectorWidth / 2f

        groups.forEachIndexed { idx, g ->
            val az = g.azimuth ?: return@forEachIndexed

            // 扇区多边形
            val pts = buildSectorPoints(pos, az, halfAngle, sectorRadiusMeters, sectorSteps)
            aMap.addPolygon(
                PolygonOptions()
                    .addAll(pts)
                    .strokeWidth(2f)
                    .strokeColor(sectorStrokeColor)
                    .fillColor(sectorFillColor)
                    .zIndex(sectorZ)
            )?.let { polys.add(it) }

            // 方向线
            val end = moveLatLng(pos, az, 110.0)
            aMap.addPolyline(
                PolylineOptions()
                    .add(pos, end)
                    .width(lineWidth)
                    .color(lineColor)
                    .zIndex(sectorZ)
            )?.let { plines.add(it) }

            // 原扇区标签已移除
        }

        // 统一标签（三角形下方）
        val labelText = buildBandLabel(rows)
        if (labelText.isNotBlank()) {
            val z = aMap.cameraPosition.zoom
            val meters = when {
                z >= 18f -> 10.0   // 调近
                z >= 16f -> 16.0
                else -> 25.0
            }
            val labelPos = moveLatLng(pos, 180f, meters)
            aMap.addMarker(
                MarkerOptions()
                    .position(labelPos)
                    .icon(makeTextIcon(labelText))
                    .anchor(0.5f, 0.0f)
                    .zIndex(triangleZ + 1f)
                    .setFlat(true)
            )?.let { labels.add(it) }
        }

        val node = BsNode(stationKey, m, polys, plines, labels)
        history.addLast(node)
        historyMap[stationKey] = node
        trimHistoryIfNeeded()
        return true
    }


    // ============= 旧缓存渲染：保持兼容（先不大改缓存结构） =============
    private fun renderFromCacheAsLegacy(list: List<CellOverlayCacheEntity>) {
        if (!keepHistory) clear()

        list.forEach { it ->
            val (lat, lon) = when (serverCoord) {
                ServerCoord.WGS84 -> Gcj02.wgs84ToGcj02(it.lat, it.lng)
                ServerCoord.GCJ02 -> it.lat to it.lng
            }
            val pos = LatLng(lat, lon)

            val title = "基站：${it.cellName ?: "LTE Cell"}（cache）"
            val snippet = buildString {
                append("match=cache\n")
                append("tac=").append(it.tac).append(" earfcn=").append(it.earfcn).append('\n')
                append("pci=").append(it.pci).append(" eci=").append(it.eci).append('\n')
                append("enb=").append(it.enodebId).append(" cid=").append(it.cellId).append('\n')
                append("azimuth=").append(it.azimuth).append(" src=").append(it.source)
            }

            // ✅ 稳定 stationKey：不带 idx
            val stationKey = "cache|${it.baseKey}"

            // ✅ 去重：已存在则不再绘制（可选更新 snippet）
            historyMap[stationKey]?.let { existing ->
                existing.marker.title = title
                existing.marker.snippet = snippet
                return@forEach
            }

            val m = aMap.addMarker(
                MarkerOptions()
                    .position(pos)
                    .icon(icon)
                    .anchor(0.5f, 1.0f)
                    .zIndex(triangleZ)
                    .title(title)
                    .snippet(snippet)
            ) ?: return@forEach

            val az0 = parseAzimuth(it.azimuth)
            val polys = mutableListOf<Polygon>()
            val plines = mutableListOf<Polyline>()
            val labels = mutableListOf<Marker>()

            val shouldDrawCircle = (az0 == null || az0 == 0f)
            fun labelRadius(): Double = sectorRadiusMeters * 0.98

            val rowsForLabel = listOf(
                LteCellParamRow(
                    tac = it.tac,
                    pci = it.pci,
                    enodeb_id = it.enodebId,
                    earfcn = it.earfcn,
                    cell_id = it.cellId,
                    eci = it.eci,
                    cell_name = it.cellName,
                    longitude = it.lng,
                    latitude = it.lat,
                    azimuth = (az0?.toInt()),
                    sector_id = null,
                    source = it.source
                )
            )

            if (shouldDrawCircle) {
                val circlePts = buildCirclePoints(center = pos, radiusMeters = sectorRadiusMeters, steps = 36)
                aMap.addPolygon(
                    PolygonOptions()
                        .addAll(circlePts)
                        .strokeWidth(2f)
                        .strokeColor(sectorStrokeColor)
                        .fillColor(sectorFillColor)
                        .zIndex(sectorZ)
                )?.let { polys.add(it) }

                val labelText = buildBandLabel(rowsForLabel)
                if (labelText.isNotBlank()) {
                    val z = aMap.cameraPosition.zoom
                    val meters = when {
                        z >= 18f -> 18.0
                        z >= 16f -> 26.0
                        else -> 40.0
                    }
                    val labelPos = moveLatLng(pos, 180f, meters)
                    aMap.addMarker(
                        MarkerOptions()
                            .position(labelPos)
                            .icon(makeTextIcon(labelText))
                            .anchor(0.5f, 0.0f)
                            .zIndex(triangleZ + 1f)
                            .setFlat(true)
                    )?.let { labels.add(it) }
                }
            } else {
                val pts = buildSectorPoints(center = pos, azimuthDeg = az0, halfAngleDeg = sectorAngleDeg / 2f, radiusMeters = sectorRadiusMeters, steps = sectorSteps)
                aMap.addPolygon(
                    PolygonOptions()
                        .addAll(pts)
                        .strokeWidth(2f)
                        .strokeColor(sectorStrokeColor)
                        .fillColor(sectorFillColor)
                        .zIndex(sectorZ)
                )?.let { polys.add(it) }

                val end = moveLatLng(pos, az0, 110.0)
                aMap.addPolyline(
                    PolylineOptions()
                        .add(pos, end)
                        .width(lineWidth)
                        .color(lineColor)
                        .zIndex(sectorZ)
                )?.let { plines.add(it) }

                val labelText = buildBandLabel(rowsForLabel)
                if (labelText.isNotBlank()) {
                    val labelPos = moveLatLng(pos, az0, labelRadius())
                    aMap.addMarker(
                        MarkerOptions()
                            .position(labelPos)
                            .icon(makeTextIcon(labelText))
                            .anchor(0.5f, 0.5f)
                            .zIndex(triangleZ + 1f)
                            .setFlat(true)
                    )?.let { labels.add(it) }
                }
            }

            val node = BsNode(stationKey, m, polys, plines, labels)
            history.addLast(node)
            historyMap[stationKey] = node
            trimHistoryIfNeeded()
        }
    }

    private fun renderFromCacheAsLegacyInternal(list: List<CellOverlayCacheEntity>): Boolean {
        if (!keepHistory) clear()

        var anyOk = false

        list.forEach { it ->
            val (lat, lon) = when (serverCoord) {
                ServerCoord.WGS84 -> Gcj02.wgs84ToGcj02(it.lat, it.lng)
                ServerCoord.GCJ02 -> it.lat to it.lng
            }
            val pos = LatLng(lat, lon)

            val title = "基站：${it.cellName ?: "LTE Cell"}（cache）"
            val snippet = buildString {
                append("match=cache\n")
                append("tac=").append(it.tac).append(" earfcn=").append(it.earfcn).append('\n')
                append("pci=").append(it.pci).append(" eci=").append(it.eci).append('\n')
                append("enb=").append(it.enodebId).append(" cid=").append(it.cellId).append('\n')
                append("azimuth=").append(it.azimuth).append(" src=").append(it.source)
            }

            val stationKey = "cache|${it.baseKey}"

            // ✅ 已存在：视为 ok（flush 也别一直重试）
            historyMap[stationKey]?.let { existing ->
                existing.marker.title = title
                existing.marker.snippet = snippet
                anyOk = true
                return@forEach
            }

            val m = aMap.addMarker(
                MarkerOptions()
                    .position(pos)
                    .icon(icon)
                    .anchor(0.5f, 1.0f)
                    .zIndex(triangleZ)
                    .title(title)
                    .snippet(snippet)
            )

            if (m == null) {
                Logger.log("LteOverlay: cache addMarker=null stationKey=$stationKey")
                return@forEach
            }

            anyOk = true

            val az0 = parseAzimuth(it.azimuth)
            val polys = mutableListOf<Polygon>()
            val plines = mutableListOf<Polyline>()
            val labels = mutableListOf<Marker>()

            val shouldDrawCircle = (az0 == null || az0 == 0f)
            fun labelRadius(): Double = sectorRadiusMeters * 0.98

            val rowsForLabel = listOf(
                LteCellParamRow(
                    tac = it.tac,
                    pci = it.pci,
                    enodeb_id = it.enodebId,
                    earfcn = it.earfcn,
                    cell_id = it.cellId,
                    eci = it.eci,
                    cell_name = it.cellName,
                    longitude = it.lng,
                    latitude = it.lat,
                    azimuth = (az0?.toInt()),
                    sector_id = null,
                    source = it.source
                )
            )

            if (shouldDrawCircle) {
                val circlePts = buildCirclePoints(pos, sectorRadiusMeters, 36)
                aMap.addPolygon(
                    PolygonOptions()
                        .addAll(circlePts)
                        .strokeWidth(2f)
                        .strokeColor(sectorStrokeColor)
                        .fillColor(sectorFillColor)
                        .zIndex(sectorZ)
                )?.let { polys.add(it) }

                val labelText = buildBandLabel(rowsForLabel)
                if (labelText.isNotBlank()) {
                    val z = aMap.cameraPosition.zoom
                    val meters = when {
                        z >= 18f -> 18.0
                        z >= 16f -> 26.0
                        else -> 40.0
                    }
                    val labelPos = moveLatLng(pos, 180f, meters)
                    aMap.addMarker(
                        MarkerOptions()
                            .position(labelPos)
                            .icon(makeTextIcon(labelText))
                            .anchor(0.5f, 0.0f)
                            .zIndex(triangleZ + 1f)
                            .setFlat(true)
                    )?.let { labels.add(it) }
                }
            } else {
                val pts = buildSectorPoints(pos, az0, sectorAngleDeg / 2f, sectorRadiusMeters, sectorSteps)
                aMap.addPolygon(
                    PolygonOptions()
                        .addAll(pts)
                        .strokeWidth(2f)
                        .strokeColor(sectorStrokeColor)
                        .fillColor(sectorFillColor)
                        .zIndex(sectorZ)
                )?.let { polys.add(it) }

                val end = moveLatLng(pos, az0, 110.0)
                aMap.addPolyline(
                    PolylineOptions()
                        .add(pos, end)
                        .width(lineWidth)
                        .color(lineColor)
                        .zIndex(sectorZ)
                )?.let { plines.add(it) }

                val labelText = buildBandLabel(rowsForLabel)
                if (labelText.isNotBlank()) {
                    val labelPos = moveLatLng(pos, az0, labelRadius())
                    aMap.addMarker(
                        MarkerOptions()
                            .position(labelPos)
                            .icon(makeTextIcon(labelText))
                            .anchor(0.5f, 0.5f)
                            .zIndex(triangleZ + 1f)
                            .setFlat(true)
                    )?.let { labels.add(it) }
                }
            }

            val node = BsNode(stationKey, m, polys, plines, labels)
            history.addLast(node)
            historyMap[stationKey] = node
            trimHistoryIfNeeded()
        }

        return anyOk
    }

    private fun trimHistoryIfNeeded() {
        while (history.size > maxHistoryMarkers) {
            val old = history.removeFirst()

            // ✅ 一定要先从索引删除
            historyMap.remove(old.key)

            runCatching { old.marker.remove() }
            old.sectors.forEach { runCatching { it.remove() } }
            old.lines.forEach { runCatching { it.remove() } }
            old.labels.forEach { runCatching { it.remove() } }
        }
    }

    /////////////////////////////////////////////延迟绘制
    private fun flushPendingWithRetry() {
        if (!mapLoaded) return
        if (flushing) return
        flushing = true

        // 先 flush site，再 flush cache（你也可以反过来）
        pendingSite?.let { (resp, pair) ->
            renderSiteWithRetry(resp, pair.first, pair.second, attempt = 0) { ok ->
                if (ok) pendingSite = null
                // 不管成功失败，都继续尝试 flush cache
                pendingCache?.let { (list, _) ->
                    renderCacheWithRetry(list, attempt = 0) { ok2 ->
                        if (ok2) pendingCache = null
                        flushing = false
                    }
                } ?: run { flushing = false }
            }
            return
        }

        pendingCache?.let { (list, _) ->
            renderCacheWithRetry(list, attempt = 0) { ok ->
                if (ok) pendingCache = null
                flushing = false
            }
            return
        }

        flushing = false
    }

    private fun renderSiteWithRetry(
        resp: LteSiteQueryResp,
        requestTac: Int,
        requestEarfcn: Int,
        attempt: Int,
        done: (Boolean) -> Unit
    ) {
        // 必须在主线程画
        scope.launch(Dispatchers.Main.immediate) {
            val ok = renderSiteInternal(resp, requestTac, requestEarfcn)

            if (ok) {
                Logger.log("LteOverlay: renderSite ok on attempt=$attempt")
                done(true)
                return@launch
            }

            if (attempt >= 3) {
                Logger.log("LteOverlay: renderSite failed after retries (attempt=$attempt)")
                done(false)
                return@launch
            }

            val delayMs = 100L + attempt * 80L // 100,180,260
            Logger.log("LteOverlay: renderSite retry in ${delayMs}ms (attempt=${attempt + 1})")
            delay(delayMs)
            renderSiteWithRetry(resp, requestTac, requestEarfcn, attempt + 1, done)
        }
    }

    private fun renderCacheWithRetry(
        list: List<CellOverlayCacheEntity>,
        attempt: Int,
        done: (Boolean) -> Unit
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            val ok = renderFromCacheAsLegacyInternal(list)

            if (ok) {
                Logger.log("LteOverlay: renderCache ok on attempt=$attempt")
                done(true)
                return@launch
            }

            if (attempt >= 2) {
                Logger.log("LteOverlay: renderCache failed after retries (attempt=$attempt)")
                done(false)
                return@launch
            }

            val delayMs = 80L + attempt * 80L
            Logger.log("LteOverlay: renderCache retry in ${delayMs}ms (attempt=${attempt + 1})")
            delay(delayMs)
            renderCacheWithRetry(list, attempt + 1, done)
        }
    }
    /////////////////////////////////////////////延迟绘制

    //绘制边缘band
    private fun makeTextIcon(
        text: String,
        textSizeSp: Float = 12f,
        paddingDp: Float = 6f,
        strokeDp: Float = 2f,
        maxWidthDp: Float = 180f,     // ✅ 新增：最大宽度，超了就换行（你可调 120~180）
        maxLines: Int = 5             // ✅ 新增：最多几行（避免变成论文）
    ): BitmapDescriptor {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        fun dp(v: Float) = (v * dm.density)
        fun sp(v: Float) = (v * dm.scaledDensity)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = sp(textSizeSp)
            style = Paint.Style.FILL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = sp(textSizeSp)
            style = Paint.Style.STROKE
            strokeWidth = dp(strokeDp)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val padding = dp(paddingDp)
        val maxTextW = (dp(maxWidthDp) - padding * 2).toInt().coerceAtLeast(1)

        // ✅ 自动换行布局
        fun buildLayout(paint: android.text.TextPaint): android.text.StaticLayout {
            val l = android.text.StaticLayout.Builder
                .obtain(text, 0, text.length, paint, maxTextW)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.0f)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setMaxLines(maxLines)
                .build()
            return l
        }

        val layoutFill = buildLayout(textPaint)
        val layoutStroke = buildLayout(strokePaint)

        val w = (layoutFill.width + padding * 2).toInt().coerceAtLeast(1)
        val h = (layoutFill.height + padding * 2).toInt().coerceAtLeast(1)

        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)

        c.translate(padding, padding)
        // 先描边后填充（多行）
        layoutStroke.draw(c)
        layoutFill.draw(c)

        return com.amap.api.maps.model.BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun buildBandLabel(rows: List<LteCellParamRow>): String {
        val earfcns = rows.mapNotNull { it.earfcn }.distinct().sorted()
        if (earfcns.isEmpty()) return ""

        val parts = earfcns.map { n ->
            val info = lteEarfcnToFreqMhzCN(n)
            // 只保留频段号，去掉频率
            if (info == null) "E$n" else "B${info.band}"
        }.distinct()

        val maxTokensPerLine = 3
        val lines = parts.chunked(maxTokensPerLine).map { it.joinToString(" / ") }
        return lines.joinToString("\n")
    }
    // ===================== 分组：azimuth 聚类优先，失败再 sector_id =====================

    private data class SectorGroup(
        val key: String,
        val azimuth: Float?,
        val rows: List<LteCellParamRow>
    )

    private data class LteCellParamRow(
        val tac: Int? = null,
        val pci: Int? = null,
        val enodeb_id: Int? = null,
        val earfcn: Int? = null,
        val cell_id: Int? = null,
        val eci: Long? = null,
        val cell_name: String? = null,
        val longitude: Double? = null,
        val latitude: Double? = null,
        val azimuth: Int? = null,
        val sector_id: Int? = null,
        val source: String? = null
    )

    private fun LteCellParam.asRow(): LteCellParamRow = LteCellParamRow(
        tac = tac,
        pci = pci,
        enodeb_id = enodeb_id,
        earfcn = earfcn,
        cell_id = cell_id,
        eci = eci,
        cell_name = cell_name,
        longitude = longitude,
        latitude = latitude,
        azimuth = azimuth,
        sector_id = sector_id,
        source = source
    )

    private fun validAzimuthOrNull(row: LteCellParamRow): Float? {
        val a = parseAzimuth(row.azimuth) ?: return null
        if (treatZeroAzimuthAsMissing && a == 0f) return null
        return a
    }

    private fun groupByAzimuthCluster(rows: List<LteCellParamRow>): List<SectorGroup> {
        // 1) 拆分：强可信(1..359) / 特殊(0°)
        val nonZero = ArrayList<Pair<LteCellParamRow, Float>>(rows.size)
        val zeros = ArrayList<LteCellParamRow>(8)

        rows.forEach { r ->
            val az = parseAzimuth(r.azimuth) ?: return@forEach
            if (az == 0f) zeros.add(r) else nonZero.add(r to az)
        }

        // 2) 先对 nonZero 做一维聚类（顺序扫描 + 环形合并）
        val items = nonZero.sortedBy { it.second }
        if (items.isEmpty()) {
            // 没有非0方向，只能先把 0° 作为一个组（可能为空）
            return if (zeros.isNotEmpty()) {
                listOf(
                    SectorGroup(
                        key = "az#0",
                        azimuth = 0f,
                        rows = zeros
                    )
                )
            } else emptyList()
        }

        val raw = ArrayList<MutableList<Pair<LteCellParamRow, Float>>>()
        var cur = mutableListOf(items.first())
        for (i in 1 until items.size) {
            val prevAz = cur.last().second
            val az = items[i].second
            if (angleDiffDeg(prevAz, az) <= azimuthClusterTolDeg) cur.add(items[i])
            else {
                raw.add(cur)
                cur = mutableListOf(items[i])
            }
        }
        raw.add(cur)

        // 环形合并：首尾跨 360（例如 350° 与 10°）
        if (raw.size >= 2) {
            val firstAz = raw.first().first().second
            val lastAz = raw.last().last().second
            if (angleDiffDeg(firstAz, lastAz) <= azimuthClusterTolDeg) {
                val merged = ArrayList<Pair<LteCellParamRow, Float>>(raw.last().size + raw.first().size)
                merged.addAll(raw.last())
                merged.addAll(raw.first())
                raw[0] = merged.toMutableList()
                raw.removeAt(raw.lastIndex)
            }
        }

        // 3) 生成 SectorGroup（先不加 0°）
        val groups = raw.mapIndexed { idx, g ->
            val azCenter = circularMeanDeg(g.map { it.second })
            SectorGroup(
                key = "az#$idx",
                azimuth = azCenter,
                rows = g.map { it.first }
            )
        }.toMutableList()

        // 4) 处理 0°：优先并入最近的 existing group；否则单独成组
        if (zeros.isNotEmpty()) {
            // 找到与 0° 最近的扇区中心
            val bestIdx = groups.indices.minByOrNull { i ->
                val gAz = groups[i].azimuth ?: 0f
                angleDiffDeg(gAz, 0f)
            }

            if (bestIdx != null) {
                val gAz = groups[bestIdx].azimuth ?: 0f
                val diff = angleDiffDeg(gAz, 0f)

                if (diff <= azimuthClusterTolDeg) {
                    // 并入最近组（避免额外造一个“北向伪扇区”）
                    val mergedRows = ArrayList<LteCellParamRow>(groups[bestIdx].rows.size + zeros.size)
                    mergedRows.addAll(groups[bestIdx].rows)
                    mergedRows.addAll(zeros)

                    groups[bestIdx] = groups[bestIdx].copy(rows = mergedRows)
                } else {
                    // 单独成组：真的就当一个北向扇区
                    groups.add(
                        SectorGroup(
                            key = "az#zero",
                            azimuth = 0f,
                            rows = zeros
                        )
                    )
                }
            } else {
                groups.add(
                    SectorGroup(
                        key = "az#zero",
                        azimuth = 0f,
                        rows = zeros
                    )
                )
            }
        }

        // 5) 排序输出（按中心角）
        return groups.sortedBy { it.azimuth ?: 0f }
    }

    private fun buildCirclePoints(
        center: LatLng,
        radiusMeters: Double,
        steps: Int = 36
    ): List<LatLng> {
        val pts = ArrayList<LatLng>(steps + 1)
        val step = 360f / steps
        for (i in 0..steps) {
            val ang = i * step
            pts.add(moveLatLng(center, ang, radiusMeters))
        }
        return pts
    }

    private fun groupBySectorId(rows: List<LteCellParamRow>): List<SectorGroup> {
        val groups = rows
            .filter { it.sector_id != null }
            .groupBy { it.sector_id!! }
            .toList()
            .sortedBy { it.first }

        return groups.map { (sid, list) ->
            val az = list.mapNotNull { validAzimuthOrNull(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { circularMeanDeg(it) }

            SectorGroup(
                key = "sid#$sid",
                azimuth = az,
                rows = list
            )
        }
    }

    private fun angleDiffDeg(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private fun circularMeanDeg(angles: List<Float>): Float {
        val rad = angles.map { Math.toRadians(it.toDouble()) }
        val x = rad.sumOf { kotlin.math.cos(it).toDouble() }
        val y = rad.sumOf { kotlin.math.sin(it).toDouble() }
        val mean = Math.toDegrees(kotlin.math.atan2(y, x))
        return normalizeAngle(mean.toFloat())
    }

    // ---------- azimuth 解析 ----------
    private fun parseAzimuth(v: Any?): Float? {
        if (v == null) return null
        return when (v) {
            is Number -> v.toFloat()
            else -> {
                val s = v.toString().trim()
                if (s.isEmpty() || s == "-") null else s.toFloatOrNull()
            }
        }?.let { normalizeAngle(it) }
    }

    private fun normalizeAngle(deg: Float): Float {
        var x = deg % 360f
        if (x < 0f) x += 360f
        return x
    }

    // ---------- 扇区点集 ----------
    private fun buildSectorPoints(
        center: LatLng,
        azimuthDeg: Float,
        halfAngleDeg: Float,
        radiusMeters: Double,
        steps: Int
    ): List<LatLng> {
        val pts = ArrayList<LatLng>(steps + 3)
        pts.add(center)

        val start = azimuthDeg - halfAngleDeg
        val end = azimuthDeg + halfAngleDeg
        val step = (end - start) / steps

        for (i in 0..steps) {
            val ang = start + i * step
            pts.add(moveLatLng(center, ang, radiusMeters))
        }

        pts.add(center)
        return pts
    }

    // ---------- 沿方位角移动 ----------
    private fun moveLatLng(center: LatLng, azimuthDeg: Float, distanceMeters: Double): LatLng {
        val R = 6378137.0
        val brng = Math.toRadians(azimuthDeg.toDouble())
        val lat1 = Math.toRadians(center.latitude)
        val lon1 = Math.toRadians(center.longitude)
        val dr = distanceMeters / R

        val lat2 = Math.asin(
            Math.sin(lat1) * Math.cos(dr) +
                    Math.cos(lat1) * Math.sin(dr) * Math.cos(brng)
        )

        val lon2 = lon1 + Math.atan2(
            Math.sin(brng) * Math.sin(dr) * Math.cos(lat1),
            Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2)
        )

        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun buildOverlayBaseKey(
        tac: Int,
        earfcn: Int,
        eci: Long?,
        pci: Int?,
        cellId: Int?
    ): String {
        return if (eci != null && eci > 0) {
            // ✅ ECI 查询：key 锁死到 eci，避免 earfcn 参与造成歧义
            "t=$tac|eci=$eci"
        } else {
            // 退化：没有 eci 才用频点+pci+cid
            "t=$tac|e=$earfcn|pci=${pci ?: "x"}|cid=${cellId ?: "x"}"
        }
    }

    // ✅ preciseKey：用于 lastQueryKey 去重（比 baseKey 更细，避免误拦截）
    private fun buildOverlayPreciseKey(
        tac: Int,
        earfcn: Int,
        eci: Long?,
        pci: Int?,
        cellId: Int?
    ): String {
        return "t=$tac|e=$earfcn|eci=${eci ?: "x"}|pci=${pci ?: "x"}|cid=${cellId ?: "x"}"
    }
}
