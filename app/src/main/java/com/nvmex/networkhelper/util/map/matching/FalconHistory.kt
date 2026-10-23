package com.nvmex.networkhelper.util.map.matching

import com.google.gson.JsonParser

internal data class FalconHistorySlice(val sid: Long, val tid: Long, val trid: Long, val start: Long, val end: Long)

/** Resume sessions can contain many cloud tracks. Query each, respecting Falcon's 24-hour limit. */
internal fun falconHistorySlices(points: List<Pair<Long, String?>>, now: Long): List<FalconHistorySlice> {
    data class Id(val sid: Long, val tid: Long, val trid: Long)
    val groups = points.mapNotNull { (timestamp, json) -> runCatching {
        val objectValue = JsonParser.parseString(json ?: return@mapNotNull null).asJsonObject
        if (objectValue["provider"]?.asString != "AMAP_FALCON" || timestamp <= 0 || timestamp > now) return@mapNotNull null
        val id = Id(objectValue["sid"].asLong, objectValue["tid"].asLong, objectValue["trid"].asLong)
        if (id.sid <= 0 || id.tid <= 0 || id.trid <= 0) return@mapNotNull null
        id to timestamp
    }.getOrNull() }.groupBy({ it.first }, { it.second })
    return groups.flatMap { (id, timestamps) ->
        val end = timestamps.max()
        var start = timestamps.min()
        buildList {
            while (start < end) {
                val stop = minOf(end, start + 86_399_000)
                add(FalconHistorySlice(id.sid, id.tid, id.trid, start, stop))
                start = stop + 1
            }
        }
    }.sortedBy { it.start }
}
