package com.nvmex.networkhelper.util.map.matching

import com.google.gson.JsonParser
import kotlin.math.*

enum class RouteType { ROAD, RAIL, UNKNOWN }
data class RoutePosition(val lat: Double, val lng: Double)
data class RouteProjection(val position: RoutePosition, val along: Double, val distance: Double, val bearing: Double)

/** WGS84 centreline, not a routable map tile. Node IDs, rather than crossing geometry, define connectivity. */
class MatchRoute(val id: String, val name: String, val type: RouteType, val points: List<RoutePosition>,
    val startNode: String?, val endNode: String?, val oneWay: Int = 0, val corridor: String = "") {
    val lengths = DoubleArray(points.size)
    init { for (i in 1..points.lastIndex) lengths[i] = lengths[i - 1] + routeDistance(points[i - 1], points[i]) }
    val length get() = lengths.last()
    fun at(along: Double): RouteProjection {
        val value = along.coerceIn(0.0, length)
        val i = (lengths.binarySearch(value).let { if (it >= 0) it else -it - 2 }).coerceIn(0, points.lastIndex - 1)
        val a = points[i]; val b = points[i + 1]
        val t = ((value - lengths[i]) / (lengths[i + 1] - lengths[i]).coerceAtLeast(0.001)).coerceIn(0.0, 1.0)
        return RouteProjection(RoutePosition(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t), value, 0.0, routeBearing(a, b))
    }
    fun project(p: RoutePosition, heading: Double?, previousAlong: Double? = null): RouteProjection {
        val scale = cos(Math.toRadians(p.lat)) * 111_320
        return (0 until points.lastIndex).map { i ->
            val a = points[i]; val b = points[i + 1]
            val ax = (a.lng - p.lng) * scale; val ay = (a.lat - p.lat) * 111_320
            val dx = (b.lng - a.lng) * scale; val dy = (b.lat - a.lat) * 111_320
            val t = (-(ax * dx + ay * dy) / (dx * dx + dy * dy).coerceAtLeast(0.001)).coerceIn(0.0, 1.0)
            RouteProjection(RoutePosition(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t),
                lengths[i] + t * (lengths[i + 1] - lengths[i]), hypot(ax + dx * t, ay + dy * t), routeBearing(a, b))
        }.minBy { it.distance + (heading?.let { h -> directionError(h, it.bearing) } ?: 0.0) * 0.45 +
            (previousAlong?.let { old -> abs(it.along - old) * 0.08 } ?: 0.0) }
    }
    fun directionError(heading: Double, bearing: Double): Double = when (oneWay) {
        1 -> angleDifference(heading, bearing)
        -1 -> angleDifference(heading, bearing + 180)
        else -> min(angleDifference(heading, bearing), angleDifference(heading, bearing + 180))
    }
}

class RouteNetwork(val routes: List<MatchRoute>, val description: String) {
    // Bounded local grid; no nationwide geometry scans on the UI thread.
    private val grid = HashMap<Pair<Int, Int>, MutableSet<MatchRoute>>()
    init {
        routes.forEach { r ->
            val x0 = floor(r.points.minOf { it.lng } * 100).toInt(); val x1 = floor(r.points.maxOf { it.lng } * 100).toInt()
            val y0 = floor(r.points.minOf { it.lat } * 100).toInt(); val y1 = floor(r.points.maxOf { it.lat } * 100).toInt()
            require((x1 - x0 + 1L) * (y1 - y0 + 1L) <= 400) { "单条线路范围过大，请分段导入" }
            for (x in x0..x1) for (y in y0..y1) grid.getOrPut(x to y) { linkedSetOf() }.add(r)
        }
    }
    fun nearby(p: RoutePosition): List<MatchRoute> {
        val x = floor(p.lng * 100).toInt(); val y = floor(p.lat * 100).toInt()
        return (-1..1).flatMap { dx -> (-1..1).flatMap { dy -> grid[x + dx to y + dy].orEmpty() } }.distinct()
            .sortedBy { r -> r.points.minOf { routeDistance(it, p) } }.take(32)
    }
    fun connected(route: MatchRoute, direction: Int): List<Pair<MatchRoute, Int>> {
        val node = (if (direction > 0) route.endNode else route.startNode) ?: return emptyList()
        return routes.filter { it.id != route.id && it.type == route.type }.mapNotNull { r ->
            when { r.startNode == node && r.oneWay != -1 -> r to 1; r.endNode == node && r.oneWay != 1 -> r to -1; else -> null }
        }
    }
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        fun parse(text: String): RouteNetwork {
            require(text.length <= MAX_BYTES) { "GeoJSON 超过 2 MB，请按测试区域拆分" }
            val root = JsonParser.parseString(text).asJsonObject
            require(root["type"]?.asString == "FeatureCollection") { "需要 GeoJSON FeatureCollection" }
            val crs = root["coordinate_system"]?.asString ?: "WGS84"
            require(crs == "WGS84") { "线路必须使用 WGS84 [经度,纬度]，不接受 GCJ02" }
            val features = root.getAsJsonArray("features")
            require(features.size() in 1..256) { "线路数需为 1–256" }
            var vertices = 0
            val routes = features.mapIndexed { index, value ->
                val f = value.asJsonObject; val prop = f.getAsJsonObject("properties")
                fun string(key: String) = prop[key]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val geometry = f.getAsJsonObject("geometry")
                require(geometry["type"].asString == "LineString") { "仅支持 LineString，请先拆分 MultiLineString" }
                val type = when { string("route_type") == "ROAD" || string("highway").isNotBlank() -> RouteType.ROAD
                    string("route_type") == "RAIL" || string("railway") in listOf("rail", "subway", "light_rail", "tram") -> RouteType.RAIL
                    else -> error("第 ${index + 1} 条线路缺少 ROAD / RAIL 类型") }
                val points = geometry.getAsJsonArray("coordinates").map { v ->
                    val a = v.asJsonArray; val lng = a[0].asDouble; val lat = a[1].asDouble
                    require(lat.isFinite() && lng.isFinite() && lat in -85.0..85.0 && lng in -180.0..180.0) { "线路坐标无效" }
                    RoutePosition(lat, lng)
                }.distinctUntilChanged()
                vertices += points.size
                require(points.size >= 2 && vertices <= 20_000) { "线路节点无效或超过 20000 个" }
                MatchRoute(string("id").ifBlank { "feature_$index" }, string("name"), type, points,
                    string("start_node").ifBlank { null }, string("end_node").ifBlank { null },
                    when (string("oneway")) { "yes", "1", "true" -> 1; "-1" -> -1; else -> 0 }, string("corridor"))
            }
            require(routes.map { it.id }.distinct().size == routes.size) { "线路 ID 重复" }
            return RouteNetwork(routes, root["name"]?.asString ?: "本地导入线路")
        }
        private fun <T> List<T>.distinctUntilChanged() = filterIndexed { i, t -> i == 0 || t != this[i - 1] }
    }
}

fun routeDistance(a: RoutePosition, b: RoutePosition): Double {
    val x = Math.toRadians(b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
    val y = Math.toRadians(b.lat - a.lat)
    return hypot(x, y) * 6_371_000
}
fun routeBearing(a: RoutePosition, b: RoutePosition) = (Math.toDegrees(atan2(
    (b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2)), b.lat - a.lat)) + 360) % 360
fun angleDifference(a: Double, b: Double) = abs((a - b + 540) % 360 - 180)
