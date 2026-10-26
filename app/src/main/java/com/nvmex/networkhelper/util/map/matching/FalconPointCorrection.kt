package com.nvmex.networkhelper.util.map.matching

import com.google.gson.JsonParser
import com.nvmex.networkhelper.util.map.Gcj02
import kotlin.math.abs

data class FalconPointUpdate(val timestamp: Long, val simSlot: Int, val metadata: String)

/** Timestamp lookup for delayed cloud output. Never apply a past endpoint to a current fix. */
internal class FalconCorrectionIndex(private val trace: FalconTrace) {
    private val segments = trace.segments.map { values -> values.filter { it.valid && it.timestamp > 0 }
        .distinctBy { it.timestamp }.sortedBy { it.timestamp } }.filter { it.isNotEmpty() }
    val start = segments.minOfOrNull { it.first().timestamp } ?: Long.MAX_VALUE
    val end = segments.maxOfOrNull { it.last().timestamp } ?: Long.MIN_VALUE

    fun correction(timestamp: Long, raw: RoutePosition, accuracy: Double): FalconPoint? {
        if (timestamp <= 0 || !accuracy.isFinite() || accuracy < 0) return null
        val point = segments.asSequence().mapNotNull { points ->
            val found = points.binarySearchBy(timestamp) { it.timestamp }
            if (found >= 0) return@mapNotNull points[found]
            val after = -found - 1
            val a = points.getOrNull(after - 1); val b = points.getOrNull(after)
            if (a != null && b != null && b.timestamp - a.timestamp in 1..10_000) {
                val fraction = (timestamp - a.timestamp).toDouble() / (b.timestamp - a.timestamp)
                FalconPoint(a.lat + (b.lat - a.lat) * fraction, a.lng + (b.lng - a.lng) * fraction, timestamp)
            } else listOfNotNull(a, b).minByOrNull { abs(it.timestamp - timestamp) }
                ?.takeIf { abs(it.timestamp - timestamp) <= 1500 }
        }.minByOrNull { abs(it.timestamp - timestamp) }
        return point?.takeIf { routeDistance(raw, RoutePosition(it.lat, it.lng)) <= (accuracy * 2).coerceIn(25.0, 100.0) }
    }

    fun apply(metadata: String?, sampleTime: Long, rawGcj: RoutePosition, accuracy: Double, estimated: Boolean): String? = runCatching {
        if (estimated || sampleTime !in (start - 5000)..(end + 5000) || metadata.isNullOrBlank()) return null
        val value = JsonParser.parseString(metadata).asJsonObject
        if (value["provider"]?.asString != "AMAP_FALCON" || value["sid"]?.asLong != trace.sid ||
            value["tid"]?.asLong != trace.tid || value["trid"]?.asLong != trace.trid ||
            value["input_source"]?.asString == "IMU") return null
        val timestamp = value["timestamp"]?.asLong?.takeIf { it > 0 } ?: return null
        if (abs(timestamp - sampleTime) > 5000) return null
        val point = correction(timestamp, rawGcj, accuracy) ?: return null
        val wgs = Gcj02.gcj02ToWgs84(point.lat, point.lng)
        value.addProperty("type", "ROAD"); value.addProperty("source", "AMAP_FALCON")
        value.addProperty("status", "猎鹰 · 已纠偏"); value.addProperty("cloud_applied", true)
        value.addProperty("matched_wgs84_lat", wgs.first); value.addProperty("matched_wgs84_lng", wgs.second)
        value.addProperty("matched_gcj02_lat", point.lat); value.addProperty("matched_gcj02_lng", point.lng)
        value.addProperty("offset_m", routeDistance(rawGcj, RoutePosition(point.lat, point.lng)))
        value.toString().takeUnless { it == metadata }
    }.getOrNull()
}

internal fun falconRecordedPosition(metadata: String?): RoutePosition? = runCatching {
    val value = JsonParser.parseString(metadata ?: return null).asJsonObject
    if (value["provider"]?.asString != "AMAP_FALCON" || value["type"]?.asString != "ROAD" ||
        value["source"]?.asString != "AMAP_FALCON" || value["input_source"]?.asString == "IMU") return null
    val lat = value["matched_wgs84_lat"]?.asDouble ?: return null
    val lng = value["matched_wgs84_lng"]?.asDouble ?: return null
    if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    val exactLat = value["matched_gcj02_lat"]?.asDouble
    val exactLng = value["matched_gcj02_lng"]?.asDouble
    val gcj = if (exactLat != null && exactLng != null && exactLat.isFinite() && exactLng.isFinite() &&
        exactLat in -90.0..90.0 && exactLng in -180.0..180.0) exactLat to exactLng else Gcj02.wgs84ToGcj02(lat, lng)
    RoutePosition(gcj.first, gcj.second)
}.getOrNull()
