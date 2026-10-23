package com.nvmex.networkhelper.util.map.matching

import com.google.gson.JsonObject
import kotlin.math.*

enum class MatchSource { GNSS, IMU, SDK }
data class MatchFix(val position: RoutePosition, val elapsedMs: Long, val source: MatchSource,
    val speedMps: Double?, val bearing: Double?, val accuracy: Double, val speedReliable: Boolean,
    val timestamp: Long = 0)
data class MatchResult(val fix: MatchFix, val corrected: RoutePosition? = null, val type: RouteType = RouteType.UNKNOWN,
    val routeId: String = "", val routeName: String = "", val status: String, val degraded: Boolean = false) {
    val offsetMeters get() = corrected?.let { routeDistance(fix.position, it) } ?: 0.0
    fun toJson(): String = JsonObject().apply {
        addProperty("version", 1); addProperty("raw_wgs84_lat", fix.position.lat); addProperty("raw_wgs84_lng", fix.position.lng)
        corrected?.let { addProperty("matched_wgs84_lat", it.lat); addProperty("matched_wgs84_lng", it.lng) }
        addProperty("type", type.name); addProperty("input_source", fix.source.name)
        addProperty("source", if (corrected == null) fix.source.name else "MAP_MATCH")
        addProperty("route_id", routeId); addProperty("route_name", routeName); addProperty("offset_m", offsetMeters)
        addProperty("status", status); addProperty("degraded", degraded); addProperty("elapsed_ms", fix.elapsedMs)
    }.toString()
}

/** Serial, output-only constraint. Never calls or resets DeadReckoningEngine. */
class AutoMapMatcher(private val network: RouteNetwork) {
    private data class Lock(val route: MatchRoute, val along: Double, val direction: Int, val fix: MatchFix, val gnssAt: Long)
    private var lock: Lock? = null
    private var pendingId = ""
    private var pendingCount = 0
    private var pendingAlong = 0.0
    private var pendingAt = 0L
    private var recoveryCount = 0
    private var lastOutput: Pair<MatchFix, RoutePosition>? = null
    fun reset() { lock = null; pendingId = ""; pendingCount = 0; recoveryCount = 0 }
    fun match(fix: MatchFix): MatchResult {
        fun fallback(reason: String, degraded: Boolean = false) = MatchResult(fix, status = reason, degraded = degraded)
        if (!fix.position.lat.isFinite() || !fix.position.lng.isFinite() || fix.elapsedMs < 0) { reset(); return fallback("原始定位无效", true) }
        if (fix.source == MatchSource.IMU) return advance(fix)
        if (fix.source != MatchSource.GNSS) return fallback("网络定位不用于线路确认，保留原始位置")
        if (!fix.accuracy.isFinite() || fix.accuracy !in 0.0..25.0) {
            reset(); return fallback("等待可信 GNSS（精度 ≤25 m）")
        }
        val old = lock
        val dt = old?.let { (fix.elapsedMs - it.fix.elapsedMs) / 1000.0 }
        if (dt != null && dt <= 0) return fallback("过期定位")
        val speed = fix.speedMps
        val heading = fix.bearing
        if (speed == null || !speed.isFinite() || speed !in 0.0..100.0 || !fix.speedReliable || heading == null || !heading.isFinite() || speed < 2) {
            // No new lock from stationary fixes or invented headings.
            reset(); return fallback("等待可靠速度和航向")
        }
        data class Candidate(val route: MatchRoute, val p: RouteProjection, val direction: Int, val score: Double)
        val candidates = network.nearby(fix.position).mapNotNull { r ->
            val p = r.project(fix.position, heading, old?.takeIf { it.route.id == r.id }?.along)
            val angle = r.directionError(heading, p.bearing)
            if (p.distance > max(20.0, min(55.0, fix.accuracy * 1.5)) || angle > 40) return@mapNotNull null
            val direction = if (angleDifference(heading, p.bearing) < 90) 1 else -1
            val same = old?.route?.id == r.id
            val progress = if (same && dt != null) abs((p.along - old!!.along) * old.direction - speed * dt) else 0.0
            Candidate(r, p, direction, p.distance + angle * 0.6 + progress * 0.25 + if (old != null && !same) 12 else 0)
        }.sortedBy { it.score }
        if (candidates.isEmpty()) { reset(); return fallback("数据未覆盖、距离过远或方向冲突") }
        val best = candidates.first()
        // Adjacent tracks in an explicitly declared railway corridor are indistinguishable at phone GNSS accuracy.
        val rival = candidates.drop(1).firstOrNull { c -> !(best.route.type == RouteType.RAIL && c.route.type == RouteType.RAIL &&
            best.route.corridor.isNotBlank() && best.route.corridor == c.route.corridor &&
            routeDistance(best.p.position, c.p.position) < 15 && angleDifference(
                best.p.bearing + if (best.direction < 0) 180 else 0,
                c.p.bearing + if (c.direction < 0) 180 else 0) < 15) }
        if (rival != null && rival.score - best.score < 10 && old?.route?.id != best.route.id) {
            reset(); return fallback("候选线路不明确，保留原始位置")
        }
        if (old != null && old.route.id == best.route.id && old.direction == best.direction && dt != null && dt <= 5) {
            if (abs(best.p.along - old.along) > speed * dt + 45 || (best.p.along - old.along) * old.direction < -15) {
                reset(); return fallback("GNSS 与已确认线路进度冲突")
            }
            if (old.fix.source == MatchSource.IMU) recoveryCount = 1
            else if (recoveryCount > 0) recoveryCount++
            lock = Lock(best.route, best.p.along, best.direction, fix, fix.elapsedMs)
            if (recoveryCount == 1) return fallback("GNSS 恢复，正在重新验证线路")
            recoveryCount = 0
            return result(fix, lock!!, best.p.position, "GNSS 线路约束")
        }
        // Switches, including graph-connected exits, require fresh GNSS evidence. No crossing-based transitions.
        val connected = old == null || network.connected(old.route, old.direction).any { it.first.id == best.route.id }
        if (old != null && !connected) { reset(); return fallback("候选路线不连续，重新确认") }
        if (pendingId != best.route.id || fix.elapsedMs - pendingAt > 4000 || best.p.along * best.direction < pendingAlong * best.direction - 5) {
            pendingId = best.route.id; pendingCount = 1; pendingAlong = best.p.along
        } else pendingCount++
        pendingAt = fix.elapsedMs
        if (pendingCount < 3 || abs(best.p.along - pendingAlong) < 6) return fallback("正在确认线路 $pendingCount/3")
        lock = Lock(best.route, best.p.along, best.direction, fix, fix.elapsedMs)
        pendingId = ""; pendingCount = 0
        return result(fix, lock!!, best.p.position, "GNSS 已确认线路")
    }
    private fun advance(fix: MatchFix): MatchResult {
        fun fallback(reason: String) = MatchResult(fix, status = reason, degraded = true)
        var previous = lock ?: return fallback("无已确认线路，回退 IMU")
        val dt = (fix.elapsedMs - previous.fix.elapsedMs) / 1000.0
        val speed = fix.speedMps
        if (!fix.speedReliable || speed == null || !speed.isFinite() || speed !in 0.0..100.0 || fix.accuracy > 120 ||
            dt !in 0.001..5.0 || fix.elapsedMs - previous.gnssAt > 60_000 ||
            previous.fix.speedMps?.let { abs(speed - it) > max(8.0, dt * 8) } == true) {
            reset(); return fallback("速度/传感器不可信、漂移过大或推算超时，质量下降")
        }
        val distance = (speed + (previous.fix.speedMps ?: speed)) * 0.5 * dt
        var along = previous.along + distance * previous.direction
        // At a fork, only one graph-connected same-type continuation with consistent tangent is admissible.
        var hops = 0
        while (along < 0 || along > previous.route.length) {
            if (++hops > 4) { reset(); return fallback("线路分段过密，回退 IMU") }
            val exit = previous.route.at(if (previous.direction > 0) previous.route.length else 0.0)
            val exitHeading = (exit.bearing + if (previous.direction < 0) 180 else 0) % 360
            val connections = network.connected(previous.route, previous.direction)
            if (connections.size != 1) { reset(); return fallback("到达线路端点或岔路，等待 GNSS") }
            val continuations = connections.filter { (r, dir) ->
                val entry = r.at(if (dir > 0) 0.0 else r.length)
                routeDistance(exit.position, entry.position) < 3 && angleDifference(exitHeading, entry.bearing + if (dir < 0) 180 else 0) < 35
            }
            if (continuations.size != 1) { reset(); return fallback("到达线路端点或岔路，等待 GNSS") }
            val (r, dir) = continuations.single()
            val remaining = if (along < 0) -along else along - previous.route.length
            previous = previous.copy(route = r, direction = dir)
            along = if (dir > 0) remaining else r.length - remaining
        }
        val projection = previous.route.at(along)
        if (fix.bearing == null || angleDifference(fix.bearing, projection.bearing + if (previous.direction < 0) 180 else 0) > 55 ||
            routeDistance(fix.position, projection.position) > 120) {
            reset(); return fallback("积分方向或横向漂移与线路冲突")
        }
        lock = previous.copy(along = along, fix = fix)
        return result(fix, lock!!, projection.position, "无 GNSS：按速度沿已确认线路推算（非实测定位）")
    }
    private fun result(fix: MatchFix, locked: Lock, p: RoutePosition, status: String): MatchResult {
        val previous = lastOutput
        if (previous != null) {
            val seconds = (fix.elapsedMs - previous.first.elapsedMs) / 1000.0
            if (seconds in 0.001..5.0 && routeDistance(previous.second, p) > (fix.speedMps ?: 0.0) * seconds + 20) {
                reset()
                return MatchResult(fix, status = "纠偏结果跳变过大，回退原始位置并重新确认", degraded = true)
            }
        }
        lastOutput = fix to p
        return MatchResult(fix, p, locked.route.type, locked.route.id, locked.route.name, status, fix.source == MatchSource.IMU)
    }
}
