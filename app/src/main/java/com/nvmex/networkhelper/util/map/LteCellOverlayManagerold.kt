//import com.amap.api.maps.AMap
//import com.amap.api.maps.model.BitmapDescriptor
//import com.amap.api.maps.model.LatLng
//import com.amap.api.maps.model.Marker
//import com.amap.api.maps.model.MarkerOptions
//import com.amap.api.maps.model.Polygon
//import com.amap.api.maps.model.PolygonOptions
//import com.amap.api.maps.model.Polyline
//import com.amap.api.maps.model.PolylineOptions
//import com.nvmex.networkhelper.model.menu.LteCellQueryBody
//import com.nvmex.networkhelper.network.api.ApiService
//import com.nvmex.networkhelper.network.map.LteCellParam
//import com.nvmex.networkhelper.network.map.LteSiteQueryResp
//import com.nvmex.networkhelper.util.map.CellOverlayCacheDao
//import com.nvmex.networkhelper.util.map.CellOverlayCacheEntity
//import com.nvmex.networkhelper.util.map.Gcj02
//import com.nvmex.networkhelper.util.map.buildOverlayBaseKey
//import com.nvmex.networkhelper.util.map.lteEarfcnToFreqMhzCN
//import com.nvmex.networkhelper.util.map.makeRedTriangleIcon
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import kotlin.collections.forEach
//
//enum class ServerCoord { WGS84, GCJ02 }
//
//class LteCellOverlayManagerold(
//    private val aMap: AMap,
//    private val api: ApiService,
//    private val scope: CoroutineScope,
//    private val cacheDao: CellOverlayCacheDao,
//    private val cacheTtlMs: Long = 7L * 24 * 3600_000L,
//    private val serverCoord: ServerCoord = ServerCoord.WGS84,
//) {
//    private val icon by lazy { makeRedTriangleIcon(56) }
//
//    // 你之前的容器还留着（如果别处不用可删）
//    private val markers = ArrayList<Marker>(32)
//    private val lines = ArrayList<Polyline>(32)
//    private val sectors = ArrayList<Polygon>(64)
//
//    private var lastQueryKey: String? = null
//
//    // ===== 扇区绘制策略 =====
//    // 半径（示意）
//    private val sectorRadiusMeters: Double = 500.0
//    // 弧线采样点数（越大越圆）
//    private val sectorSteps: Int = 14
//    // zIndex：扇区与红线同层，三角形更高一层
//    private val sectorZ = 27f
//    private val triangleZ = 30f
//
//    // 颜色
//    private val sectorFillColor = 0x22FF0000.toInt() // 半透明红
//    private val sectorStrokeColor = 0x55FFFFFF        // 半透明白
//    private val lineColor = 0x22FF0000.toInt()        // 红线淡
//    private val lineWidth = 4f
//
//    // ===== azimuth 聚类参数 =====
//    private val azimuthClusterTolDeg: Float = 22f
//    private val treatZeroAzimuthAsMissing: Boolean = true
//
//    // 上限：保留最近 N 个基站
//    private val maxHistoryMarkers: Int = 300
//    // 是否保留历史
//    private val keepHistory: Boolean = true
//
//    // ===== 历史节点（一个基站一个 marker + 多扇区）=====
//    private data class BsNode(
//        val key: String,
//        val marker: Marker,
//        val sectors: List<Polygon>,
//        val lines: List<Polyline>,
//        val labels: List<Marker>, // ✅ 新增：扇区标签
//    )
//
//    private val history = ArrayDeque<BsNode>(128)
//
//    fun clear() {
//        history.forEach { node ->
//            runCatching { node.marker.remove() }
//            node.sectors.forEach { runCatching { it.remove() } }
//            node.lines.forEach { runCatching { it.remove() } }
//            node.labels.forEach { runCatching { it.remove() } } // ✅
//        }
//        history.clear()
//        markers.clear()
//        lines.clear()
//        sectors.clear()
//    }
//
//    // ============= 对外调用：查询并绘制 =============
//    fun queryAndShow(tac: Int?, earfcn: Int?, eci: Long?, pci: Int?, cellId: Int?) {
//        if (tac == null || earfcn == null) return
//
//        val baseKey = buildOverlayBaseKey(tac, earfcn, eci, pci)
//        if (baseKey == lastQueryKey) return
//        lastQueryKey = baseKey
//
//        val now = System.currentTimeMillis()
//
//        scope.launch(Dispatchers.IO) {
//            // 1) 缓存命中：你原来缓存的是“点”，不是“同站扇区集合”
//            //    这里为了不大改你的缓存结构：缓存命中时仍按“旧 renderFromCache”绘制（单面/多点）
//            //    等你确认新接口稳定后，再把缓存升级为缓存 LteSiteQueryResp。
//            val cached = cacheDao.listByBaseKey(baseKey)
//            val ttlOk = cached.isNotEmpty() && (now - (cached.maxOf { it.ts })) <= cacheTtlMs
//            if (ttlOk) {
//                withContext(Dispatchers.Main.immediate) {
//                    renderFromCacheAsLegacy(cached)
//                }
//                return@launch
//            }
//
//            // 2) 请求新接口：返回同站 N 条
//            val body = LteCellQueryBody(
//                tac = tac,
//                earfcn = earfcn,
//                eci = eci,
//                pci = pci,
//                cell_id = cellId
//            )
//
//            val resp = runCatching { api.queryLteSite(body) }.getOrNull()
//            val site = resp?.takeIf { it.isSuccessful }?.body() ?: return@launch
//
//            withContext(Dispatchers.Main.immediate) {
//                renderSite(site, requestTac = tac, requestEarfcn = earfcn)
//            }
//
//            // 3) 仍然按你原来的方式落库（先落 anchor/代表点，避免缓存表爆）
//            val rows = site.data
//                .filter { it.latitude != null && it.longitude != null }
//                .take(8)
//
//            if (rows.isEmpty()) return@launch
//
//            cacheDao.deleteByBaseKey(baseKey)
//
//            val entities = rows.mapIndexed { idx, r ->
//                CellOverlayCacheEntity(
//                    key = "$baseKey#$idx",
//                    baseKey = baseKey,
//                    tac = tac,
//                    earfcn = earfcn,
//                    eci = eci,
//                    pci = pci,
//                    lat = r.latitude!!,
//                    lng = r.longitude!!,
//                    azimuth = r.azimuth,
//                    enodebId = r.enodeb_id,
//                    cellId = r.cell_id,
//                    cellName = r.cell_name,
//                    source = r.source ?: "api",
//                    ts = now
//                )
//            }
//            cacheDao.upsertAll(entities)
//        }
//    }
//
//    // ============= 新渲染：同站拼基站（一个 marker + 多扇区） =============
//    private fun renderSite(resp: LteSiteQueryResp, requestTac: Int, requestEarfcn: Int) {
//        if (!keepHistory) clear()
//
//        val anchor = resp.anchor
//            ?: resp.data.firstOrNull { it.latitude != null && it.longitude != null }
//            ?: return
//
//        val lat0 = anchor.latitude ?: return
//        val lon0 = anchor.longitude ?: return
//
//        val (lat, lon) = when (serverCoord) {
//            ServerCoord.WGS84 -> Gcj02.wgs84ToGcj02(lat0, lon0)
//            ServerCoord.GCJ02 -> lat0 to lon0
//        }
//        val pos = LatLng(lat, lon)
//
//        val enb = resp.enodeb_id ?: anchor.enodeb_id
//
//        val title = buildString {
//            append("基站：")
//            append(anchor.cell_name ?: "LTE Site")
//            if (enb != null) append("  enb=").append(enb)
//        }
//
//        val snippet = buildString {
//            append("match=").append(resp.match_level)
//                .append("  count=").append(resp.count)
//                .append("  sectors=").append(resp.sector_count)
//                .append('\n')
//            append("tac=").append(requestTac)
//                .append("  earfcn=").append(requestEarfcn)
//                .append('\n')
//            append("pci=").append(anchor.pci)
//                .append("  eci=").append(anchor.eci)
//                .append('\n')
//            append("src=").append(anchor.source ?: "-")
//        }
//
//        val stationKey = buildString {
//            append("enb=").append(enb ?: -1)
//            append("|tac=").append(requestTac)
//        }
//
//        val m = aMap.addMarker(
//            MarkerOptions()
//                .position(pos)
//                .icon(icon)
//                .anchor(0.5f, 1.0f)
//                .zIndex(triangleZ)
//                .title(title)
//                .snippet(snippet)
//        ) ?: return
//        m.`object` = anchor
//
//        val rows = resp.data
//            .filter { it.latitude != null && it.longitude != null }
//            .map { it.asRow() }
//
//        val azGroups = groupByAzimuthCluster(rows)
//        val groups = if (azGroups.isNotEmpty()) azGroups else groupBySectorId(rows)
//
//        if (groups.isEmpty()) {
//            history.addLast(BsNode(stationKey, m, emptyList(), emptyList(), emptyList()))
//            trimHistoryIfNeeded()
//            return
//        }
//
//        val onlyOne = groups.size == 1
//        val onlyAz = groups.first().azimuth
//        val shouldDrawCircle = onlyOne && (onlyAz == null || onlyAz == 0f)
//
//        val polys = ArrayList<Polygon>(8)
//        val plines = ArrayList<Polyline>(8)
//        val labels = ArrayList<Marker>(8) // ✅ 新增
//
//        // 用于让多个 label 半径错开一点，避免挤一起
//        fun labelRadius(idx: Int): Double {
//            val base = sectorRadiusMeters * 0.98
//            val bump = sectorRadiusMeters * (0.03 * (idx % 3)) // 0%,3%,6% 循环
//            return base + bump
//        }
//
//        if (shouldDrawCircle) {
//            val circlePts = buildCirclePoints(
//                center = pos,
//                radiusMeters = sectorRadiusMeters,
//                steps = 36
//            )
//            val poly = aMap.addPolygon(
//                PolygonOptions()
//                    .addAll(circlePts)
//                    .strokeWidth(2f)
//                    .strokeColor(sectorStrokeColor)
//                    .fillColor(sectorFillColor)
//                    .zIndex(sectorZ)
//            )
//            polys.add(poly)
//
//            // ✅ 圆形也挂一个总标签（同站所有频段）
//            val labelText = buildBandLabel(rows)
//            if (labelText.isNotBlank()) {
//                val labelPos = moveLatLng(pos, 45f, labelRadius(0)) // 斜上角放一个
//                val lm = aMap.addMarker(
//                    MarkerOptions()
//                        .position(labelPos)
//                        .icon(makeTextIcon(labelText))
//                        .anchor(0.5f, 0.5f)
//                        .zIndex(triangleZ + 1f)
//                        .setFlat(true)
//                )
//                if (lm != null) labels.add(lm)
//            }
//
//            history.addLast(BsNode(stationKey, m, polys, plines, labels))
//            trimHistoryIfNeeded()
//            return
//        }
//
//        val n = groups.size.coerceAtLeast(1)
//        val sectorWidth = when (n) {
//            3 -> 120f
//            4 -> 90f
//            6 -> 60f
//            else -> (360f / n).coerceIn(45f, 140f)
//        }
//        val halfAngle = sectorWidth / 2f
//
//        groups.forEachIndexed { idx, g ->
//            val az = g.azimuth ?: return@forEachIndexed
//
//            val pts = buildSectorPoints(
//                center = pos,
//                azimuthDeg = az,
//                halfAngleDeg = halfAngle,
//                radiusMeters = sectorRadiusMeters,
//                steps = sectorSteps
//            )
//            val poly = aMap.addPolygon(
//                PolygonOptions()
//                    .addAll(pts)
//                    .strokeWidth(2f)
//                    .strokeColor(sectorStrokeColor)
//                    .fillColor(sectorFillColor)
//                    .zIndex(sectorZ)
//            )
//            polys.add(poly)
//
//            val end = moveLatLng(pos, az, 110.0)
//            val line = aMap.addPolyline(
//                PolylineOptions()
//                    .add(pos, end)
//                    .width(lineWidth)
//                    .color(lineColor)
//                    .zIndex(sectorZ)
//            )
//            plines.add(line)
//
//            // ✅ 外圈短标签：显示该扇区组内的频段/频率
//            val labelText = buildBandLabel(g.rows)
//            if (labelText.isNotBlank()) {
//                val labelPos = moveLatLng(pos, az, labelRadius(idx))
//                val lm = aMap.addMarker(
//                    MarkerOptions()
//                        .position(labelPos)
//                        .icon(makeTextIcon(labelText))
//                        .anchor(0.5f, 0.5f)
//                        .zIndex(triangleZ + 1f)
//                        .setFlat(true)
//                )
//                if (lm != null) labels.add(lm)
//            }
//        }
//
//        history.addLast(BsNode(stationKey, m, polys, plines, labels))
//        trimHistoryIfNeeded()
//    }
//
//    // ============= 旧缓存渲染：保持兼容（先不大改缓存结构） =============
//    private fun renderFromCacheAsLegacy(list: List<CellOverlayCacheEntity>) {
//        if (!keepHistory) clear()
//
//        list.forEachIndexed { idx, it ->
//            val (lat, lon) = when (serverCoord) {
//                ServerCoord.WGS84 -> Gcj02.wgs84ToGcj02(it.lat, it.lng)
//                ServerCoord.GCJ02 -> it.lat to it.lng
//            }
//            val pos = LatLng(lat, lon)
//
//            val title = "基站：${it.cellName ?: "LTE Cell"}（cache${idx + 1}）"
//            val snippet = buildString {
//                append("match=cache\n")
//                append("tac=").append(it.tac).append(" earfcn=").append(it.earfcn).append('\n')
//                append("pci=").append(it.pci).append(" eci=").append(it.eci).append('\n')
//                append("enb=").append(it.enodebId).append(" cid=").append(it.cellId).append('\n')
//                append("azimuth=").append(it.azimuth).append(" src=").append(it.source)
//            }
//
//            val stationKey = "cache|${it.baseKey}|$idx"
//
//            val m = aMap.addMarker(
//                MarkerOptions()
//                    .position(pos)
//                    .icon(icon)
//                    .anchor(0.5f, 1.0f)
//                    .zIndex(triangleZ)
//                    .title(title)
//                    .snippet(snippet)
//            ) ?: return@forEachIndexed
//
//            val az0 = parseAzimuth(it.azimuth)
//            val polys = mutableListOf<Polygon>()
//            val plines = mutableListOf<Polyline>()
//
//            // ✅ 最小修复：缓存命中也遵守“圆形兜底”
//            val shouldDrawCircle = (az0 == null || az0 == 0f)
//
//            if (shouldDrawCircle) {
//                val circlePts = buildCirclePoints(
//                    center = pos,
//                    radiusMeters = sectorRadiusMeters,
//                    steps = 36
//                )
//                polys += aMap.addPolygon(
//                    PolygonOptions()
//                        .addAll(circlePts)
//                        .strokeWidth(2f)
//                        .strokeColor(sectorStrokeColor)
//                        .fillColor(sectorFillColor)
//                        .zIndex(sectorZ)
//                )
//                // 圆形不画方向线
//            } else {
//                val pts = buildSectorPoints(
//                    center = pos,
//                    azimuthDeg = az0,
//                    halfAngleDeg = 45f,
//                    radiusMeters = sectorRadiusMeters,
//                    steps = sectorSteps
//                )
//                polys += aMap.addPolygon(
//                    PolygonOptions()
//                        .addAll(pts)
//                        .strokeWidth(2f)
//                        .strokeColor(sectorStrokeColor)
//                        .fillColor(sectorFillColor)
//                        .zIndex(sectorZ)
//                )
//                val end = moveLatLng(pos, az0, 110.0)
//                plines += aMap.addPolyline(
//                    PolylineOptions()
//                        .add(pos, end)
//                        .width(lineWidth)
//                        .color(lineColor)
//                        .zIndex(sectorZ)
//                )
//            }
//
//            history.addLast(BsNode(stationKey, m, polys, plines, emptyList()))
//            trimHistoryIfNeeded()
//        }
//    }
//
//    private fun trimHistoryIfNeeded() {
//        while (history.size > maxHistoryMarkers) {
//            val old = history.removeFirst()
//            runCatching { old.marker.remove() }
//            old.sectors.forEach { runCatching { it.remove() } }
//            old.lines.forEach { runCatching { it.remove() } }
//            old.labels.forEach { runCatching { it.remove() } } // ✅
//        }
//    }
//
//    //绘制边缘band
//    private fun makeTextIcon(
//        text: String,
//        textSizeSp: Float = 12f,
//        paddingDp: Float = 6f,
//        strokeDp: Float = 3f
//    ): BitmapDescriptor {
//        val dm = android.content.res.Resources.getSystem().displayMetrics
//        fun dp(v: Float) = (v * dm.density)
//        fun sp(v: Float) = (v * dm.scaledDensity)
//
//        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
//            color = android.graphics.Color.WHITE
//            textSize = sp(textSizeSp)
//            style = android.graphics.Paint.Style.FILL
//            typeface = android.graphics.Typeface.DEFAULT_BOLD
//        }
//        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
//            color = android.graphics.Color.BLACK
//            textSize = sp(textSizeSp)
//            style = android.graphics.Paint.Style.STROKE
//            strokeWidth = dp(strokeDp)
//            typeface = android.graphics.Typeface.DEFAULT_BOLD
//        }
//
//        val padding = dp(paddingDp)
//        val fm = textPaint.fontMetrics
//        val textW = textPaint.measureText(text)
//        val textH = (fm.descent - fm.ascent)
//
//        val w = (textW + padding * 2).toInt().coerceAtLeast(1)
//        val h = (textH + padding * 2).toInt().coerceAtLeast(1)
//
//        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
//        val c = android.graphics.Canvas(bmp)
//
//        // 文字 baseline
//        val x = padding
//        val y = padding - fm.ascent
//
//        // 先描边后填充（白字黑边，地图上更清楚）
//        c.drawText(text, x, y, strokePaint)
//        c.drawText(text, x, y, textPaint)
//
//        return com.amap.api.maps.model.BitmapDescriptorFactory.fromBitmap(bmp)
//    }
//
//    private fun buildBandLabel(rows: List<LteCellParamRow>): String {
//        val earfcns = rows.mapNotNull { it.earfcn }.distinct().sorted()
//        if (earfcns.isEmpty()) return ""
//
//        val parts = earfcns.map { n ->
//            val info = lteEarfcnToFreqMhzCN(n)
//            if (info == null) "E$n" else "B${info.band} ${info.dlMhz.toInt()}M"
//        }.distinct()
//
//        return parts.joinToString(" / ")
//    }
//    // ===================== 分组：azimuth 聚类优先，失败再 sector_id =====================
//
//    private data class SectorGroup(
//        val key: String,
//        val azimuth: Float?,              // 可能为空
//        val rows: List<LteCellParamRow>
//    )
//
//    private data class LteCellParamRow(
//        val tac: Int? = null,
//        val pci: Int? = null,
//        val enodeb_id: Int? = null,
//        val earfcn: Int? = null,
//        val cell_id: Int? = null,
//        val eci: Long? = null,
//        val cell_name: String? = null,
//        val longitude: Double? = null,
//        val latitude: Double? = null,
//        val azimuth: Int? = null,
//        val sector_id: Int? = null,
//        val source: String? = null
//    )
//
//    private fun LteCellParam.asRow(): LteCellParamRow = LteCellParamRow(
//        tac = tac,
//        pci = pci,
//        enodeb_id = enodeb_id,
//        earfcn = earfcn,
//        cell_id = cell_id,
//        eci = eci,
//        cell_name = cell_name,
//        longitude = longitude,
//        latitude = latitude,
//        azimuth = azimuth,
//        sector_id = sector_id,
//        source = source
//    )
//
//    private fun validAzimuthOrNull(row: LteCellParamRow): Float? {
//        val a = parseAzimuth(row.azimuth) ?: return null
//        if (treatZeroAzimuthAsMissing && a == 0f) return null
//        return a
//    }
//
//    private fun groupByAzimuthCluster(rows: List<LteCellParamRow>): List<SectorGroup> {
//        // 1) 拆分：强可信(1..359) / 特殊(0°)
//        val nonZero = ArrayList<Pair<LteCellParamRow, Float>>(rows.size)
//        val zeros = ArrayList<LteCellParamRow>(8)
//
//        rows.forEach { r ->
//            val az = parseAzimuth(r.azimuth) ?: return@forEach
//            if (az == 0f) zeros.add(r) else nonZero.add(r to az)
//        }
//
//        // 2) 先对 nonZero 做一维聚类（顺序扫描 + 环形合并）
//        val items = nonZero.sortedBy { it.second }
//        if (items.isEmpty()) {
//            // 没有非0方向，只能先把 0° 作为一个组（可能为空）
//            return if (zeros.isNotEmpty()) {
//                listOf(
//                    SectorGroup(
//                        key = "az#0",
//                        azimuth = 0f,
//                        rows = zeros
//                    )
//                )
//            } else emptyList()
//        }
//
//        val raw = ArrayList<MutableList<Pair<LteCellParamRow, Float>>>()
//        var cur = mutableListOf(items.first())
//        for (i in 1 until items.size) {
//            val prevAz = cur.last().second
//            val az = items[i].second
//            if (angleDiffDeg(prevAz, az) <= azimuthClusterTolDeg) cur.add(items[i])
//            else {
//                raw.add(cur)
//                cur = mutableListOf(items[i])
//            }
//        }
//        raw.add(cur)
//
//        // 环形合并：首尾跨 360（例如 350° 与 10°）
//        if (raw.size >= 2) {
//            val firstAz = raw.first().first().second
//            val lastAz = raw.last().last().second
//            if (angleDiffDeg(firstAz, lastAz) <= azimuthClusterTolDeg) {
//                val merged = ArrayList<Pair<LteCellParamRow, Float>>(raw.last().size + raw.first().size)
//                merged.addAll(raw.last())
//                merged.addAll(raw.first())
//                raw[0] = merged.toMutableList()
//                raw.removeAt(raw.lastIndex)
//            }
//        }
//
//        // 3) 生成 SectorGroup（先不加 0°）
//        val groups = raw.mapIndexed { idx, g ->
//            val azCenter = circularMeanDeg(g.map { it.second })
//            SectorGroup(
//                key = "az#$idx",
//                azimuth = azCenter,
//                rows = g.map { it.first }
//            )
//        }.toMutableList()
//
//        // 4) 处理 0°：优先并入最近的 existing group；否则单独成组
//        if (zeros.isNotEmpty()) {
//            // 找到与 0° 最近的扇区中心
//            val bestIdx = groups.indices.minByOrNull { i ->
//                val gAz = groups[i].azimuth ?: 0f
//                angleDiffDeg(gAz, 0f)
//            }
//
//            if (bestIdx != null) {
//                val gAz = groups[bestIdx].azimuth ?: 0f
//                val diff = angleDiffDeg(gAz, 0f)
//
//                if (diff <= azimuthClusterTolDeg) {
//                    // 并入最近组（避免额外造一个“北向伪扇区”）
//                    val mergedRows = ArrayList<LteCellParamRow>(groups[bestIdx].rows.size + zeros.size)
//                    mergedRows.addAll(groups[bestIdx].rows)
//                    mergedRows.addAll(zeros)
//
//                    groups[bestIdx] = groups[bestIdx].copy(rows = mergedRows)
//                } else {
//                    // 单独成组：真的就当一个北向扇区
//                    groups.add(
//                        SectorGroup(
//                            key = "az#zero",
//                            azimuth = 0f,
//                            rows = zeros
//                        )
//                    )
//                }
//            } else {
//                groups.add(
//                    SectorGroup(
//                        key = "az#zero",
//                        azimuth = 0f,
//                        rows = zeros
//                    )
//                )
//            }
//        }
//
//        // 5) 排序输出（按中心角）
//        return groups.sortedBy { it.azimuth ?: 0f }
//    }
//
//    private fun buildCirclePoints(
//        center: LatLng,
//        radiusMeters: Double,
//        steps: Int = 36
//    ): List<LatLng> {
//        val pts = ArrayList<LatLng>(steps + 1)
//        val step = 360f / steps
//        for (i in 0..steps) {
//            val ang = i * step
//            pts.add(moveLatLng(center, ang, radiusMeters))
//        }
//        return pts
//    }
//
//    private fun groupBySectorId(rows: List<LteCellParamRow>): List<SectorGroup> {
//        val groups = rows
//            .filter { it.sector_id != null }
//            .groupBy { it.sector_id!! }
//            .toList()
//            .sortedBy { it.first }
//
//        return groups.map { (sid, list) ->
//            val az = list.mapNotNull { validAzimuthOrNull(it) }
//                .takeIf { it.isNotEmpty() }
//                ?.let { circularMeanDeg(it) }
//
//            SectorGroup(
//                key = "sid#$sid",
//                azimuth = az,
//                rows = list
//            )
//        }
//    }
//
//    private fun angleDiffDeg(a: Float, b: Float): Float {
//        val d = kotlin.math.abs(a - b) % 360f
//        return if (d > 180f) 360f - d else d
//    }
//
//    private fun circularMeanDeg(angles: List<Float>): Float {
//        val rad = angles.map { Math.toRadians(it.toDouble()) }
//        val x = rad.sumOf { kotlin.math.cos(it).toDouble() }
//        val y = rad.sumOf { kotlin.math.sin(it).toDouble() }
//        val mean = Math.toDegrees(kotlin.math.atan2(y, x))
//        return normalizeAngle(mean.toFloat())
//    }
//
//    // ---------- azimuth 解析 ----------
//    private fun parseAzimuth(v: Any?): Float? {
//        if (v == null) return null
//        return when (v) {
//            is Number -> v.toFloat()
//            else -> {
//                val s = v.toString().trim()
//                if (s.isEmpty() || s == "-") null else s.toFloatOrNull()
//            }
//        }?.let { normalizeAngle(it) }
//    }
//
//    private fun normalizeAngle(deg: Float): Float {
//        var x = deg % 360f
//        if (x < 0f) x += 360f
//        return x
//    }
//
//    // ---------- 扇区点集 ----------
//    private fun buildSectorPoints(
//        center: LatLng,
//        azimuthDeg: Float,
//        halfAngleDeg: Float,
//        radiusMeters: Double,
//        steps: Int
//    ): List<LatLng> {
//        val pts = ArrayList<LatLng>(steps + 3)
//        pts.add(center)
//
//        val start = azimuthDeg - halfAngleDeg
//        val end = azimuthDeg + halfAngleDeg
//        val step = (end - start) / steps
//
//        for (i in 0..steps) {
//            val ang = start + i * step
//            pts.add(moveLatLng(center, ang, radiusMeters))
//        }
//
//        pts.add(center)
//        return pts
//    }
//
//    // ---------- 沿方位角移动 ----------
//    private fun moveLatLng(center: LatLng, azimuthDeg: Float, distanceMeters: Double): LatLng {
//        val R = 6378137.0
//        val brng = Math.toRadians(azimuthDeg.toDouble())
//        val lat1 = Math.toRadians(center.latitude)
//        val lon1 = Math.toRadians(center.longitude)
//        val dr = distanceMeters / R
//
//        val lat2 = Math.asin(
//            Math.sin(lat1) * Math.cos(dr) +
//                    Math.cos(lat1) * Math.sin(dr) * Math.cos(brng)
//        )
//
//        val lon2 = lon1 + Math.atan2(
//            Math.sin(brng) * Math.sin(dr) * Math.cos(lat1),
//            Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2)
//        )
//
//        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
//    }
//}