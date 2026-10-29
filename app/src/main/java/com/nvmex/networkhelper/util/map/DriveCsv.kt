package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.records.CsvTable
import java.util.UUID

internal data class ImportedDriveRecording(val points: List<DrivePointEntity>, val stations: List<DriveStationEntity>, val city: String)

internal object DriveCsv {
    private val headers = listOf("session_id", "session_city", "timestamp", "sim_slot", "raw_latitude", "raw_longitude", "raw_coord_type",
        "wgs84_latitude", "wgs84_longitude", "wgs84_origin", "gcj02_latitude", "gcj02_longitude", "operator", "cell_type",
        "tac", "pci", "ci", "arfcn", "band", "rsrp_dbm", "rsrq_db", "sinr_db", "ss_rsrp_dbm", "ss_rsrq_db", "ss_sinr_db", "is_handover", "cell_key", "estimated", "accuracy_m", "segment_start",
        "snapshot_json", "station_snapshot_json", "station_link_lat", "station_link_lng", "station_ts",
        "matched_wgs84_latitude", "matched_wgs84_longitude", "matched_gcj02_latitude", "matched_gcj02_longitude",
        "match_type", "match_source", "match_offset_m", "match_status", "map_match_json", "position_source")
    fun encode(points: List<DrivePointEntity>, stations: List<DriveStationEntity>, city: String): String {
        val stationByCell = stations.associateBy { it.simSlot to it.cellKey }
        return CsvTable.encode(headers, points.map { p ->
            val s = SnapshotJson.fromJson(p.snapshotJson)
            val rawWgs = p.rawCoordType?.startsWith("WGS84", true) == true && p.rawLat != null && p.rawLng != null
            val wgs = if (p.lat == null || p.lng == null) null
                else if (rawWgs) p.rawLat!! to p.rawLng!! else Gcj02.gcj02ToWgs84(p.lat, p.lng)
            val station = stationByCell[p.simSlot to p.cellKey]
            val matchJson = SnapshotJson.mapMatch(p.snapshotJson)
            val match = matchJson?.let { com.google.gson.JsonParser.parseString(it).asJsonObject }
            val matchLat = match?.get("matched_wgs84_lat")?.asDouble
            val matchLng = match?.get("matched_wgs84_lng")?.asDouble
            val matchGcj = if (matchLat != null && matchLng != null) Gcj02.wgs84ToGcj02(matchLat, matchLng) else null
            listOf(p.sessionId, city, p.ts.toString(), (p.simSlot + 1).toString(), p.rawLat?.toString().orEmpty(), p.rawLng?.toString().orEmpty(), p.rawCoordType.orEmpty(),
                wgs?.first?.toString().orEmpty(), wgs?.second?.toString().orEmpty(), if (wgs == null) "unavailable" else if (rawWgs) "raw" else if (p.rawLat == null) "legacy_converted_from_gcj02" else "converted_from_gcj02",
                p.lat?.toString().orEmpty(), p.lng?.toString().orEmpty(), s.operatorName, s.cellType, s.tac, s.pci, s.ci, s.arfcn, s.band,
                p.rsrpDbm?.toString().orEmpty(), DriveSignalMetrics.value(s.rsrq).orEmpty(), DriveSignalMetrics.value(s.sinr).orEmpty(),
                DriveSignalMetrics.value(s.ssRsrp).orEmpty(), DriveSignalMetrics.value(s.ssRsrq).orEmpty(), DriveSignalMetrics.value(s.ssSinr).orEmpty(),
                p.isHandover.toString(), p.cellKey, p.estimated.toString(), p.accuracyMeters?.toString().orEmpty(),
                p.segmentStart.toString(), p.snapshotJson, station?.snapshotJson.orEmpty(), station?.userLat?.toString().orEmpty(),
                station?.userLng?.toString().orEmpty(), station?.ts?.toString().orEmpty(),
                matchLat?.toString().orEmpty(), matchLng?.toString().orEmpty(), matchGcj?.first?.toString().orEmpty(), matchGcj?.second?.toString().orEmpty(),
                match?.get("type")?.asString.orEmpty(), match?.get("source")?.asString.orEmpty(), match?.get("offset_m")?.asString.orEmpty(),
                match?.get("status")?.asString.orEmpty(), matchJson.orEmpty(), p.positionSource)
        })
    }
    fun decode(text: String): List<ImportedDriveRecording> {
        val rows = CsvTable.decode(text)
        require(rows.isNotEmpty()) { "路测文件没有打点" }
        val grouped = rows.groupBy { it["session_id"].orEmpty().ifBlank { "imported" } }
        return grouped.values.map { data ->
            val sid = "import_${UUID.randomUUID()}"
            val stations = linkedMapOf<Pair<Int, String>, DriveStationEntity>()
            val points = data.mapIndexed { index, row ->
                fun number(key: String): Double? = row[key]?.takeIf { it.isNotBlank() }?.let {
                    it.toDoubleOrNull()?.takeIf { n -> n.isFinite() } ?: error("第 ${index + 2} 行 $key 无效")
                }
                fun flag(key: String): Boolean = when (row[key]?.lowercase().orEmpty()) {
                    "", "0", "false" -> false
                    "1", "true" -> true
                    else -> error("第 ${index + 2} 行 $key 无效")
                }
                val ts = row["timestamp"]?.toLongOrNull()?.takeIf { it > 0 } ?: error("时间戳无效")
                val slot = row["sim_slot"]?.toIntOrNull()?.takeIf { it in 1..2 }?.minus(1) ?: error("sim_slot 必须为 1 或 2")
                val gcjLat = number("gcj02_latitude"); val gcjLng = number("gcj02_longitude")
                val wgsLat = number("wgs84_latitude"); val wgsLng = number("wgs84_longitude")
                val source = row["position_source"]?.takeIf { it.isNotBlank() }
                    ?: if (flag("estimated")) "INERTIAL" else "SDK"
                require(source in setOf("GPS", "SDK", "INERTIAL", "NONE")) { "定位来源无效" }
                require((gcjLat == null) == (gcjLng == null) && (wgsLat == null) == (wgsLng == null)) { "坐标对不完整" }
                val gcj = if (gcjLat != null && gcjLng != null) gcjLat to gcjLng
                    else if (wgsLat != null && wgsLng != null && driveValidPosition(wgsLat, wgsLng, 0f)) Gcj02.wgs84ToGcj02(wgsLat, wgsLng)
                    else if (source == "NONE" && wgsLat == null && gcjLat == null) null
                    else error("缺少有效的 GCJ02 或 WGS84 坐标")
                if (gcj != null) require(driveValidPosition(gcj.first, gcj.second, 0f) && source != "NONE") { "坐标超出范围或来源无效" }
                val wgsInputOnly = gcjLat == null && wgsLat != null
                val rawLat = number("raw_latitude") ?: wgsLat.takeIf { wgsInputOnly }
                val rawLng = number("raw_longitude") ?: wgsLng.takeIf { wgsInputOnly }
                require((rawLat == null) == (rawLng == null)) { "原始坐标对不完整" }
                require(source != "NONE" || (rawLat == null && !flag("estimated"))) { "无定位样本不能包含有效位置" }
                if (rawLat != null && rawLng != null) require(driveValidPosition(rawLat, rawLng, 0f)) { "原始坐标无效" }
                val snapshot = row["snapshot_json"]?.takeIf { it.isNotBlank() }
                val state = if (snapshot != null) {
                    require(com.google.gson.JsonParser.parseString(snapshot).isJsonObject) { "snapshot_json 无效" }
                    SnapshotJson.fromJson(snapshot)
                } else NetworkPanelUiState(operatorName = row["operator"].orEmpty(), cellType = row["cell_type"].orEmpty(),
                    tac = row["tac"].orEmpty(), pci = row["pci"].orEmpty(), ci = row["ci"].orEmpty(),
                    arfcn = row["arfcn"].orEmpty(), band = row["band"].orEmpty(),
                    rsrp = row["rsrp_dbm"].orEmpty().ifBlank { "-" }, rsrq = row["rsrq_db"].orEmpty().ifBlank { "-" },
                    sinr = row["sinr_db"].orEmpty().ifBlank { "-" }, ssRsrp = row["ss_rsrp_dbm"].orEmpty().ifBlank { "-" },
                    ssRsrq = row["ss_rsrq_db"].orEmpty().ifBlank { "-" }, ssSinr = row["ss_sinr_db"].orEmpty().ifBlank { "-" })
                val key = row["cell_key"]?.takeIf { it.isNotBlank() } ?: DriveStyle.cellKey(state)
                val accuracy = number("accuracy_m")?.also { require(it in 0.0..1_000_000.0) { "精度无效" } }?.toFloat()
                val stationJson = row["station_snapshot_json"]?.takeIf { it.isNotBlank() }
                if (stationJson != null) {
                    require(DriveStationJson.decode(stationJson) != null) { "服务站快照无效" }
                    val linkLat = number("station_link_lat") ?: gcj?.first ?: error("服务站连线缺少坐标")
                    val linkLng = number("station_link_lng") ?: gcj?.second ?: error("服务站连线缺少坐标")
                    require(driveValidPosition(linkLat, linkLng, 0f)) { "服务站连线坐标无效" }
                    stations.putIfAbsent(slot to key, DriveStationEntity(sid, slot, key, row["station_ts"]?.toLongOrNull() ?: ts, linkLat, linkLng, stationJson))
                }
                DrivePointEntity(sessionId = sid, ts = ts, simSlot = slot, lat = gcj?.first, lng = gcj?.second,
                    rsrpDbm = row["rsrp_dbm"]?.takeIf { it.isNotBlank() }?.let { it.toIntOrNull() ?: error("RSRP 无效") }, isHandover = flag("is_handover"),
                    cellKey = key, snapshotJson = SnapshotJson.withMapMatch(SnapshotJson.toJson(state),
                        snapshot?.let(SnapshotJson::mapMatch) ?: row["map_match_json"]?.takeIf { it.isNotBlank() }),
                    estimated = flag("estimated"), accuracyMeters = accuracy,
                    segmentStart = flag("segment_start"), rawLat = rawLat, rawLng = rawLng, rawCoordType = row["raw_coord_type"]?.takeIf { it.isNotBlank() }
                        ?: "WGS84_IMPORTED".takeIf { wgsInputOnly }, positionSource = source)
            }.sortedBy { it.ts }
            ImportedDriveRecording(points, stations.values.toList(), data.first()["session_city"].orEmpty())
        }
    }
}
