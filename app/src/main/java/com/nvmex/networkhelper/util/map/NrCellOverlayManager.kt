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
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.model.NrCellParamItem
import com.nvmex.networkhelper.network.model.NrQueryReq
import com.nvmex.networkhelper.network.model.NrSiteQueryResp
import com.nvmex.networkhelper.repository.CellParamLocalRepository
import com.nvmex.networkhelper.util.map.Gcj02
import com.nvmex.networkhelper.util.map.makeRedTriangleIcon
import com.nvmex.networkhelper.util.network.telephony.nrGuessBandByDlMhzCN
import com.nvmex.networkhelper.util.network.utils.nrArfcnToMhz
import com.nvmex.networkhelper.xposed.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NrCellOverlayManager(
    private val aMap: AMap,
    private val api: ApiService,
    private val localRepository: CellParamLocalRepository,
    private val scope: CoroutineScope,
    private val serverCoord: ServerCoord = ServerCoord.WGS84
) {
    sealed interface QueryResult {
        data class Rendered(
            val stationCount: Int,
            val rowCount: Int,
            val firstLat: Double?,
            val firstLon: Double?
        ) : QueryResult
        data class NoData(val reason: String) : QueryResult
        data class Failed(val reason: String) : QueryResult
    }

    private val icon by lazy { makeRedTriangleIcon(56) }

    private var sectorRadiusMeters: Double = 200.0
    private var sectorAngleDeg: Float = 120f
    private val sectorSteps: Int = 14
    private val sectorZ = 27f
    private val triangleZ = 30f

    private val sectorFillColor = 0x2233B5E5.toInt()
    private val sectorStrokeColor = 0x55FFFFFF
    private val lineColor = 0x4433B5E5.toInt()
    private val lineWidth = 4f

    private val azimuthClusterTolDeg: Float = 22f
    private val treatZeroAzimuthAsMissing: Boolean = true
    private val stationMergeDistanceMeters: Double = 180.0

    private val maxHistoryMarkers: Int = 300
    private val keepHistory: Boolean = true

    private var lastStableKey: String? = null
    private var lastQueryAt: Long = 0L
    private var lastRequestedKey: String? = null
    private val minQueryIntervalMs = 1500L

    private data class BsNode(
        val key: String,
        val marker: Marker,
        val sectors: List<Polygon>,
        val lines: List<Polyline>,
        val labels: List<Marker>
    )

    private data class SectorGroup(
        val key: String,
        val azimuth: Float?,
        val rows: List<NrCellParamItem>
    )

    private val historyMap = HashMap<String, BsNode>(256)
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
        historyMap.clear()
        lastStableKey = null
        lastRequestedKey = null
    }

    fun queryAndShow(
        gcellId: String?,
        nrTac: Int?,
        nrArfcn: Int?,
        nrPci: Int?,
        onResult: ((QueryResult) -> Unit)? = null
    ): Boolean {
        val cleanGcellId = gcellId?.trim()?.takeIf { it.isNotBlank() && it != "-" }
        if (cleanGcellId == null && nrTac == null && nrArfcn == null && nrPci == null) {
            return false
        }

        val key = buildOverlayKey(cleanGcellId, nrTac, nrArfcn, nrPci)
        if (key == lastStableKey) return false

        val now = System.currentTimeMillis()
        if (key == lastRequestedKey && (now - lastQueryAt) < minQueryIntervalMs) return false
        lastQueryAt = now
        lastRequestedKey = key

        scope.launch(Dispatchers.IO) {
            val body = NrQueryReq(
                gcellId = cleanGcellId,
                nrTac = nrTac,
                nrArfcn = nrArfcn,
                nrPci = nrPci
            )

            val localSite = runCatching { localRepository.queryNrSite(body) }.getOrNull()
            if (localSite != null) {
                val localOk = withContext(Dispatchers.Main.immediate) {
                    val ok = renderSiteInternal(
                        resp = localSite,
                        requestTac = nrTac,
                        requestArfcn = nrArfcn,
                        requestPci = nrPci
                    )
                    if (ok) {
                        lastStableKey = key
                        val stationCount = groupRowsByStation(localSite.data, nrTac).size
                        val first = localSite.data.firstOrNull()
                        onResult?.invoke(
                            QueryResult.Rendered(
                                stationCount = stationCount,
                                rowCount = localSite.data.size,
                                firstLat = first?.latitude,
                                firstLon = first?.longitude
                            )
                        )
                    }
                    ok
                }
                if (localOk) {
                    Logger.log("NrOverlay: rendered from local sqlite key=$key rows=${localSite.data.size}")
                    return@launch
                }
            }

            val resp = runCatching { api.queryNrSite(body) }.getOrNull()
            if (resp == null) {
                withContext(Dispatchers.Main.immediate) {
                    onResult?.invoke(QueryResult.Failed("network_error"))
                }
                return@launch
            }
            if (!resp.isSuccessful) {
                withContext(Dispatchers.Main.immediate) {
                    onResult?.invoke(QueryResult.Failed("http_${resp.code()}"))
                }
                return@launch
            }

            val site = resp.body()
            if (site == null) {
                withContext(Dispatchers.Main.immediate) {
                    onResult?.invoke(QueryResult.NoData("empty_body"))
                }
                return@launch
            }

            runCatching { localRepository.saveNrSiteQueryResp(site) }
                .onFailure { e ->
                    Logger.log("NrOverlay: save local sqlite failed ${e.message}")
                }

            withContext(Dispatchers.Main.immediate) {
                val ok = renderSiteInternal(
                    resp = site,
                    requestTac = nrTac,
                    requestArfcn = nrArfcn,
                    requestPci = nrPci
                )
                if (ok) {
                    lastStableKey = key
                    val stationCount = groupRowsByStation(site.data, nrTac).size
                    val first = site.data.firstOrNull()
                    onResult?.invoke(
                        QueryResult.Rendered(
                            stationCount = stationCount,
                            rowCount = site.data.size,
                            firstLat = first?.latitude,
                            firstLon = first?.longitude
                        )
                    )
                } else {
                    onResult?.invoke(QueryResult.NoData("no_valid_coordinate"))
                }
            }
        }
        return true
    }

    private fun renderSiteInternal(
        resp: NrSiteQueryResp,
        requestTac: Int?,
        requestArfcn: Int?,
        requestPci: Int?
    ): Boolean {
        if (!keepHistory) clear()

        val grouped = groupRowsByStation(resp.data, requestTac)
        if (grouped.isEmpty()) {
            Logger.log("NrOverlay: grouped empty rows=${resp.data.size}")
            return false
        }

        var anyOk = false
        grouped.forEach { (key, rows) ->
            val anchor = rows.firstOrNull { !it.cell_name.isNullOrBlank() } ?: rows.firstOrNull() ?: return@forEach
            val stationKey = "nr|$key"
            val ok = renderOneStation(
                stationKey = stationKey,
                anchorRow = anchor,
                rows = rows,
                requestTac = requestTac,
                requestArfcn = requestArfcn,
                requestPci = requestPci,
                headerMatch = resp.match_level
            )
            anyOk = anyOk || ok
        }
        return anyOk
    }

    private fun groupRowsByStation(
        rows: List<NrCellParamItem>,
        requestTac: Int?
    ): List<Pair<String, List<NrCellParamItem>>> {
        val valid = rows.filter { it.latitude != null && it.longitude != null }
        if (valid.isEmpty()) return emptyList()

        val hasGnb = valid.any { parseGnbId(it.gcell_id) != null }
        return if (hasGnb) {
            val grouped = valid.groupBy { row ->
                val gnb = parseGnbId(row.gcell_id) ?: -1L
                val tac = requestTac ?: row.nr_tac ?: -1
                "gnb=$gnb|tac=$tac"
            }.toList()
            mergeNearbyStationGroups(grouped, requestTac)
        } else {
            fun gridKey(lat: Double, lon: Double): String {
                val scale = 200.0
                val glat = kotlin.math.floor(lat * scale).toInt()
                val glon = kotlin.math.floor(lon * scale).toInt()
                val tac = requestTac ?: -1
                return "g=$glat,$glon|tac=$tac"
            }

            valid.groupBy { row ->
                gridKey(row.latitude!!, row.longitude!!)
            }.toList()
        }
    }

    private fun mergeNearbyStationGroups(
        groups: List<Pair<String, List<NrCellParamItem>>>,
        requestTac: Int?
    ): List<Pair<String, List<NrCellParamItem>>> {
        if (groups.size <= 1) return groups

        data class Cluster(
            var centerLat: Double,
            var centerLon: Double,
            val sourceKeys: MutableList<String>,
            val rows: MutableList<NrCellParamItem>
        )

        val clusters = ArrayList<Cluster>(groups.size)
        groups.forEach { (key, rows) ->
            val anchor = rows.firstOrNull { it.latitude != null && it.longitude != null } ?: return@forEach
            val lat = anchor.latitude ?: return@forEach
            val lon = anchor.longitude ?: return@forEach

            val nearest = clusters.minByOrNull { c ->
                distanceMeters(lat, lon, c.centerLat, c.centerLon)
            }

            if (nearest != null &&
                distanceMeters(lat, lon, nearest.centerLat, nearest.centerLon) <= stationMergeDistanceMeters
            ) {
                val oldCount = nearest.rows.size
                val addCount = rows.size
                val total = (oldCount + addCount).coerceAtLeast(1)
                nearest.centerLat = (nearest.centerLat * oldCount + lat * addCount) / total
                nearest.centerLon = (nearest.centerLon * oldCount + lon * addCount) / total
                nearest.sourceKeys.add(key)
                nearest.rows.addAll(rows)
            } else {
                clusters.add(
                    Cluster(
                        centerLat = lat,
                        centerLon = lon,
                        sourceKeys = mutableListOf(key),
                        rows = rows.toMutableList()
                    )
                )
            }
        }

        return clusters.map { c ->
            if (c.sourceKeys.size == 1) {
                c.sourceKeys.first() to c.rows.toList()
            } else {
                val tac = requestTac ?: c.rows.firstOrNull()?.nr_tac ?: -1
                val seed = c.sourceKeys.sorted().joinToString("&")
                "near|tac=$tac|h=${seed.hashCode()}" to c.rows.toList()
            }
        }
    }

    private fun parseGnbId(gcellId: String?): Long? {
        val raw = gcellId?.trim()?.toLongOrNull() ?: return null
        if (raw <= 0L) return null
        return raw shr 8
    }

    private fun renderOneStation(
        stationKey: String,
        anchorRow: NrCellParamItem,
        rows: List<NrCellParamItem>,
        requestTac: Int?,
        requestArfcn: Int?,
        requestPci: Int?,
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
            append(anchorRow.cell_name ?: "NR Site")
            parseGnbId(anchorRow.gcell_id)?.let { append("  gNB=").append(it) }
        }

        val snippet = buildString {
            if (!headerMatch.isNullOrBlank()) append("match=").append(headerMatch).append('\n')
            append("count=").append(rows.size).append('\n')
            if (requestTac != null || requestArfcn != null) {
                append("tac=").append(requestTac ?: "-").append("  nrarfcn=").append(requestArfcn ?: "-").append('\n')
            }
            append("gcell=").append(anchorRow.gcell_id ?: "-")
            append("  pci=").append(requestPci ?: anchorRow.nr_pci ?: "-").append('\n')
            append("src=").append(anchorRow.source ?: "-")
        }

        historyMap.remove(stationKey)?.let { old ->
            runCatching { old.marker.remove() }
            old.sectors.forEach { runCatching { it.remove() } }
            old.lines.forEach { runCatching { it.remove() } }
            old.labels.forEach { runCatching { it.remove() } }
            val it = history.iterator()
            while (it.hasNext()) {
                if (it.next().key == stationKey) {
                    it.remove()
                    break
                }
            }
        }

        val marker = aMap.addMarker(
            MarkerOptions()
                .position(pos)
                .icon(icon)
                .anchor(0.5f, 1.0f)
                .zIndex(triangleZ)
                .title(title)
                .snippet(snippet)
        ) ?: run {
            Logger.log("NrOverlay: addMarker=null stationKey=$stationKey")
            return false
        }

        val groups = groupByAzimuthCluster(rows)
        val shouldDrawCircle = groups.size == 1 && (groups.first().azimuth == null || groups.first().azimuth == 0f)

        val polys = ArrayList<Polygon>(8)
        val plines = ArrayList<Polyline>(8)
        val labels = ArrayList<Marker>(8)

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
        } else {
            val halfAngle = sectorAngleDeg / 2f
            groups.forEach { group ->
                val az = group.azimuth ?: return@forEach
                val pts = buildSectorPoints(pos, az, halfAngle, sectorRadiusMeters, sectorSteps)
                aMap.addPolygon(
                    PolygonOptions()
                        .addAll(pts)
                        .strokeWidth(2f)
                        .strokeColor(sectorStrokeColor)
                        .fillColor(sectorFillColor)
                        .zIndex(sectorZ)
                )?.let { polys.add(it) }

                val lineDistance = (sectorRadiusMeters * 0.55).coerceAtLeast(80.0)
                val end = moveLatLng(pos, az, lineDistance)
                aMap.addPolyline(
                    PolylineOptions()
                        .add(pos, end)
                        .width(lineWidth)
                        .color(lineColor)
                        .zIndex(sectorZ)
                )?.let { plines.add(it) }
            }
        }

        val labelText = buildBandLabel(rows)
        if (labelText.isNotBlank()) {
            val z = aMap.cameraPosition.zoom
            val meters = when {
                z >= 18f -> 10.0
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

        val node = BsNode(stationKey, marker, polys, plines, labels)
        history.addLast(node)
        historyMap[stationKey] = node
        trimHistoryIfNeeded()
        return true
    }

    private fun trimHistoryIfNeeded() {
        while (history.size > maxHistoryMarkers) {
            val old = history.removeFirst()
            historyMap.remove(old.key)
            runCatching { old.marker.remove() }
            old.sectors.forEach { runCatching { it.remove() } }
            old.lines.forEach { runCatching { it.remove() } }
            old.labels.forEach { runCatching { it.remove() } }
        }
    }

    private fun buildBandLabel(rows: List<NrCellParamItem>): String {
        val arfcns = rows.mapNotNull { it.nr_arfcn }.distinct().sorted()
        if (arfcns.isEmpty()) return ""
        val parts = arfcns.map { arfcn ->
            val band = nrGuessBandByDlMhzCN(nrArfcnToMhz(arfcn))
            if (band != null) "N$band - $arfcn" else "N$arfcn"
        }
        val lines = parts.chunked(3).map { it.joinToString(" / ") }
        return lines.joinToString("\n")
    }

    private fun groupByAzimuthCluster(rows: List<NrCellParamItem>): List<SectorGroup> {
        val nonZero = ArrayList<Pair<NrCellParamItem, Float>>(rows.size)
        val zeros = ArrayList<NrCellParamItem>(8)
        val missing = ArrayList<NrCellParamItem>(8)

        rows.forEach { row ->
            val az = parseAzimuth(row.azimuth)
            when {
                az == null -> missing.add(row)
                az == 0f -> zeros.add(row)
                else -> nonZero.add(row to az)
            }
        }

        if (nonZero.isEmpty()) {
            return listOf(
                SectorGroup(
                    key = "az#na",
                    azimuth = null,
                    rows = rows
                )
            )
        }

        val items = nonZero.sortedBy { it.second }
        val raw = ArrayList<MutableList<Pair<NrCellParamItem, Float>>>()
        var current = mutableListOf(items.first())
        for (i in 1 until items.size) {
            val prevAz = current.last().second
            val az = items[i].second
            if (angleDiffDeg(prevAz, az) <= azimuthClusterTolDeg) {
                current.add(items[i])
            } else {
                raw.add(current)
                current = mutableListOf(items[i])
            }
        }
        raw.add(current)

        if (raw.size >= 2) {
            val firstAz = raw.first().first().second
            val lastAz = raw.last().last().second
            if (angleDiffDeg(firstAz, lastAz) <= azimuthClusterTolDeg) {
                val merged = ArrayList<Pair<NrCellParamItem, Float>>(raw.last().size + raw.first().size)
                merged.addAll(raw.last())
                merged.addAll(raw.first())
                raw[0] = merged.toMutableList()
                raw.removeAt(raw.lastIndex)
            }
        }

        val groups = raw.mapIndexed { idx, group ->
            SectorGroup(
                key = "az#$idx",
                azimuth = circularMeanDeg(group.map { it.second }),
                rows = group.map { it.first }
            )
        }.toMutableList()

        if (zeros.isNotEmpty()) {
            val bestIdx = groups.indices.minByOrNull { i ->
                val gAz = groups[i].azimuth ?: 0f
                angleDiffDeg(gAz, 0f)
            }
            if (bestIdx != null && angleDiffDeg(groups[bestIdx].azimuth ?: 0f, 0f) <= azimuthClusterTolDeg) {
                val mergedRows = ArrayList<NrCellParamItem>(groups[bestIdx].rows.size + zeros.size)
                mergedRows.addAll(groups[bestIdx].rows)
                mergedRows.addAll(zeros)
                groups[bestIdx] = groups[bestIdx].copy(rows = mergedRows)
            } else {
                groups.add(SectorGroup("az#zero", 0f, zeros))
            }
        }

        if (missing.isNotEmpty()) {
            val target = groups.firstOrNull()
            if (target != null) {
                groups[0] = target.copy(rows = target.rows + missing)
            }
        }

        return groups.sortedBy { it.azimuth ?: 0f }
    }

    private fun parseAzimuth(v: Any?): Float? {
        if (v == null) return null
        val value = when (v) {
            is Number -> v.toFloat()
            else -> {
                val s = v.toString().trim()
                if (s.isEmpty() || s == "-") null else s.toFloatOrNull()
            }
        } ?: return null

        val normalized = normalizeAngle(value)
        if (treatZeroAzimuthAsMissing && normalized == 0f) return 0f
        return normalized
    }

    private fun normalizeAngle(deg: Float): Float {
        var x = deg % 360f
        if (x < 0f) x += 360f
        return x
    }

    private fun angleDiffDeg(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private fun circularMeanDeg(angles: List<Float>): Float {
        val rad = angles.map { Math.toRadians(it.toDouble()) }
        val x = rad.sumOf { kotlin.math.cos(it) }
        val y = rad.sumOf { kotlin.math.sin(it) }
        val mean = Math.toDegrees(kotlin.math.atan2(y, x))
        return normalizeAngle(mean.toFloat())
    }

    private fun buildCirclePoints(center: LatLng, radiusMeters: Double, steps: Int = 36): List<LatLng> {
        val pts = ArrayList<LatLng>(steps + 1)
        val step = 360f / steps
        for (i in 0..steps) {
            pts.add(moveLatLng(center, i * step, radiusMeters))
        }
        return pts
    }

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

    private fun moveLatLng(center: LatLng, azimuthDeg: Float, distanceMeters: Double): LatLng {
        val r = 6378137.0
        val brng = Math.toRadians(azimuthDeg.toDouble())
        val lat1 = Math.toRadians(center.latitude)
        val lon1 = Math.toRadians(center.longitude)
        val dr = distanceMeters / r

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

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6378137.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    private fun buildOverlayKey(gcellId: String?, nrTac: Int?, nrArfcn: Int?, nrPci: Int?): String {
        return "g=${gcellId ?: "x"}|t=${nrTac ?: "x"}|a=${nrArfcn ?: "x"}|p=${nrPci ?: "x"}"
    }

    private fun makeTextIcon(
        text: String,
        textSizeSp: Float = 12f,
        paddingDp: Float = 6f,
        strokeDp: Float = 2f,
        maxWidthDp: Float = 180f,
        maxLines: Int = 5
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

        fun buildLayout(paint: android.text.TextPaint): android.text.StaticLayout {
            return android.text.StaticLayout.Builder
                .obtain(text, 0, text.length, paint, maxTextW)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.0f)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setMaxLines(maxLines)
                .build()
        }

        val layoutFill = buildLayout(textPaint)
        val layoutStroke = buildLayout(strokePaint)
        val w = (layoutFill.width + padding * 2).toInt().coerceAtLeast(1)
        val h = (layoutFill.height + padding * 2).toInt().coerceAtLeast(1)

        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.translate(padding, padding)
        layoutStroke.draw(canvas)
        layoutFill.draw(canvas)

        return com.amap.api.maps.model.BitmapDescriptorFactory.fromBitmap(bmp)
    }
}
