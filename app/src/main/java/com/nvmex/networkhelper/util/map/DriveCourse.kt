package com.nvmex.networkhelper.util.map

import kotlin.math.*

internal fun driveAngleDelta(from: Double, to: Double): Double = ((to - from) % 360 + 540) % 360 - 180
internal fun driveNormalizeAngle(angle: Double): Double = (angle % 360 + 360) % 360

/** Travel course, independent of phone mounting angle and magnetic disturbances inside a car. */
internal class DriveCourse {
    private data class Fix(val time: Long, val lat: Double, val lng: Double, val accuracy: Double)
    private var previous: Fix? = null
    var bearing: Double? = null
        private set
    var isMoving: Boolean = false
        private set

    fun update(time: Long, lat: Double, lng: Double, accuracy: Double, speed: Double?, course: Double?, trusted: Boolean): Double? {
        if (!lat.isFinite() || !lng.isFinite() || !accuracy.isFinite()) return bearing
        val old = previous
        if (old != null && time <= old.time) return bearing
        if (trusted) {
            isMoving = if (speed?.isFinite() == true) speed >= 1.5
                else old != null && time - old.time in 1..10_000 &&
                    driveDistanceMeters(old.lat, old.lng, lat, lng) >= max(5.0, old.accuracy + accuracy) &&
                    driveDistanceMeters(old.lat, old.lng, lat, lng) / ((time - old.time) / 1000.0) >= 1.5
        }
        if (trusted && speed != null && speed >= 1.5 && course?.isFinite() == true) {
            bearing = driveNormalizeAngle(course)
            previous = Fix(time, lat, lng, accuracy)
        } else if (old == null) previous = Fix(time, lat, lng, accuracy)
        else if (time - old.time > 10_000) previous = Fix(time, lat, lng, accuracy)
        else if (trusted && driveDistanceMeters(old.lat, old.lng, lat, lng) >= max(5.0, old.accuracy + accuracy)) {
            val a = Math.toRadians(old.lat); val b = Math.toRadians(lat); val d = Math.toRadians(lng - old.lng)
            bearing = driveNormalizeAngle(Math.toDegrees(atan2(sin(d) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(d))))
            previous = Fix(time, lat, lng, accuracy)
        }
        return bearing
    }
}

internal fun driveBandLabel(rat: String, band: String): String {
    val raw = band.trim().takeUnless { it.isEmpty() || it == "-" } ?: return "—"
    val prefix = when { rat.equals("LTE", true) -> "B"; rat.equals("NR", true) -> "n"; else -> return raw }
    return prefix + raw.removePrefix("B").removePrefix("b").removePrefix("N").removePrefix("n")
}
