package com.nvmex.networkhelper.util.map

import kotlin.math.*

internal fun driveDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val a = sin(Math.toRadians(lat2 - lat1) / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
    return 6371008.8 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

internal data class DriveTrackFix(
    val ts: Long, val lat: Double, val lng: Double, val segmentStart: Boolean = false
)

private fun DriveTrackFix.valid() = lat.isFinite() && lng.isFinite() && abs(lat) <= 90 && abs(lng) <= 180

private fun driveTrackCanJoin(a: DriveTrackFix, b: DriveTrackFix): Boolean {
    val seconds = (b.ts - a.ts) / 1000.0
    return a.valid() && b.valid() && !b.segmentStart && seconds in 0.001..120.0 &&
        driveDistanceMeters(a.lat, a.lng, b.lat, b.lng) / seconds <= 100
}

/** Both SIMs share a fix. Never count that fix twice or bridge separate recording segments. */
internal fun driveTrackLength(fixes: List<DriveTrackFix>): Double = fixes
    .distinctBy { it.ts }.sortedBy { it.ts }.zipWithNext().sumOf { (a, b) ->
        val distance = driveDistanceMeters(a.lat, a.lng, b.lat, b.lng)
        if (!driveTrackCanJoin(a, b) || distance < 2) 0.0 else distance
    }

data class DriveCoverage(val totalMeters: Double = 0.0, val coveredMeters: Double = 0.0) {
    val fraction: Double? get() = if (totalMeters > 0) (coveredMeters / totalMeters).coerceIn(0.0, 1.0) else null
}

/** One SIM in recording order. A filtered gap cannot become band coverage by joining its endpoints. */
internal fun driveTrackCoverage(fixes: List<DriveTrackFix>, matches: (Int) -> Boolean): DriveCoverage {
    var total = 0.0
    var covered = 0.0
    for (index in 1 until fixes.size) {
        val a = fixes[index - 1]
        val b = fixes[index]
        if (!driveTrackCanJoin(a, b)) continue
        val distance = driveDistanceMeters(a.lat, a.lng, b.lat, b.lng)
        if (distance < 2) continue
        total += distance
        if (matches(index - 1) && matches(index)) covered += distance
    }
    return DriveCoverage(total, covered)
}

/** Index runs for full-resolution lines, independently of marker thinning or viewport bounds. */
internal fun driveTrackSegments(fixes: List<DriveTrackFix>, visible: (Int) -> Boolean = { true }): List<List<Int>> {
    val segments = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    fixes.forEachIndexed { index, fix ->
        if (!visible(index)) {
            if (current.size >= 2) segments.add(current)
            current = mutableListOf()
            return@forEachIndexed
        }
        if (index > 0 && !driveTrackCanJoin(fixes[index - 1], fix)) {
            if (current.size >= 2) segments.add(current)
            current = mutableListOf()
        }
        if (fix.valid()) current.add(index)
    }
    if (current.size >= 2) segments.add(current)
    return segments
}

internal data class DriveDot(val id: Int, val lat: Double, val lng: Double, val handover: Boolean)

/** Dense, route-ordered dots. Keep one marker roughly every 4dp at every zoom level. */
internal fun selectDriveDots(dots: List<DriveDot>, zoom: Float, showHandovers: Boolean, density: Float,
    maxDots: Int = 4000): List<Int> {
    val candidates = dots.filter { (showHandovers || !it.handover) && it.lat.isFinite() && it.lng.isFinite() }
    if (candidates.isEmpty() || maxDots < 2) return candidates.take(maxDots.coerceAtLeast(0)).map { it.id }
    val scale = 256.0 * 2.0.pow(zoom.coerceIn(2f, 22f).toDouble())
    val spacing = 4.0 * density.coerceAtLeast(1f)
    fun project(dot: DriveDot): Pair<Double, Double> {
        val rad = Math.toRadians(dot.lat.coerceIn(-85.0, 85.0))
        return (dot.lng + 180) / 360 * scale to (1 - ln(tan(rad) + 1 / cos(rad)) / PI) / 2 * scale
    }
    val selected = mutableListOf(candidates.first())
    var previous = project(candidates.first())
    var travelled = 0.0
    candidates.drop(1).forEach { dot ->
        val position = project(dot)
        val dx = abs(position.first - previous.first)
        travelled += hypot(min(dx, scale - dx), position.second - previous.second)
        if (travelled >= spacing || dot.handover || dot === candidates.last()) {
            selected.add(dot)
            travelled = 0.0
        }
        previous = position
    }
    if (selected.size <= maxDots) return selected.map { it.id }
    fun evenly(items: List<Int>, count: Int): List<Int> = when {
        count <= 0 -> emptyList()
        items.size <= count -> items
        count == 1 -> listOf(items[items.size / 2])
        else -> (0 until count).map { items[it * (items.size - 1) / (count - 1)] }
    }
    // Reserve coverage along the whole route, then use remaining capacity for handovers.
    val ids = mutableSetOf(selected.first().id, selected.last().id)
    ids.addAll(evenly(selected.filter { it.id !in ids }.map { it.id }, maxDots / 2 - ids.size))
    ids.addAll(evenly(selected.filter { it.handover && it.id !in ids }.map { it.id }, maxDots - ids.size))
    ids.addAll(evenly(selected.filter { it.id !in ids }.map { it.id }, maxDots - ids.size))
    return ids.sorted()
}
