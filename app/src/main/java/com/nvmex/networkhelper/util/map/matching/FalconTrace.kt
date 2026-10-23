package com.nvmex.networkhelper.util.map.matching

import com.google.gson.JsonObject
import kotlin.math.abs

data class FalconPoint(val lat: Double, val lng: Double, val timestamp: Long = 0) {
    val valid: Boolean get() = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)
}

data class FalconTrace(val sid: Long, val tid: Long, val trid: Long, val segments: List<List<FalconPoint>>) {
    /** A cloud result may be delayed or lack point timestamps. Never move today's fix to an older endpoint. */
    fun correctionFor(timestamp: Long, rawGcj: RoutePosition, accuracy: Double): FalconPoint? {
        if (timestamp <= 0) return null
        return segments.asSequence().flatten().filter { it.valid && it.timestamp > 0 && abs(it.timestamp - timestamp) <= 1500 }
            .minByOrNull { abs(it.timestamp - timestamp) }
            ?.takeIf { routeDistance(rawGcj, RoutePosition(it.lat, it.lng)) <= (accuracy * 2).coerceIn(25.0, 100.0) }
    }

    fun metadata(fix: MatchFix): String = JsonObject().apply {
        addProperty("version", 2)
        addProperty("provider", "AMAP_FALCON")
        addProperty("sid", sid); addProperty("tid", tid); addProperty("trid", trid)
        addProperty("raw_wgs84_lat", fix.position.lat); addProperty("raw_wgs84_lng", fix.position.lng)
        addProperty("input_source", fix.source.name); addProperty("source", fix.source.name)
        addProperty("type", "UNKNOWN"); addProperty("status", "猎鹰轨迹纠偏")
        addProperty("elapsed_ms", fix.elapsedMs); addProperty("timestamp", fix.timestamp)
    }.toString()
}
