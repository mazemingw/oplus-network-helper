package com.nvmex.networkhelper.util.map

import kotlin.math.*

/** GPS/IMU complementary filter. All timestamps are elapsed realtime milliseconds. */
internal class DeadReckoningEngine {
    enum class Mode { GPS, ESTIMATED, NO_FIX }
    enum class Calibration { FIX_REQUIRED, COLLECTING, READY }
    data class Reading(val mode: Mode, val lat: Double? = null, val lng: Double? = null,
        val speedMps: Double? = null, val bearing: Double? = null, val uncertaintyMeters: Double? = null,
        val outageMillis: Long = 0, val measuredAtMillis: Long = -1, val reason: String = "ok")
    private data class Motion(val from: Long, val to: Long, val east: Double, val north: Double, val turn: Double)

    var enabled = false
        set(value) { if (field != value) { field = value; resetSensors() } }
    var calibration = Calibration.FIX_REQUIRED
        private set
    private var fixAt = -1L
    private var fixLat = 0.0
    private var fixLng = 0.0
    private var fixSpeed: Double? = null
    private var fixBearing: Double? = null
    private var fixAccuracy = 0.0
    private var lat = 0.0
    private var lng = 0.0
    private var speed = 0.0
    private var heading = 0.0
    private var headingAligned = false
    private var sensorAt = -1L
    private var positionAt = -1L
    private var filteredAcceleration = 0.0
    private var eastBias = 0.0
    private var northBias = 0.0
    private var turnBias = 0.0
    private var learnedSeconds = 0.0
    private var sensorsReliable = true
    private val motions = ArrayDeque<Motion>()
    var maxOutageMs = DEFAULT_OUTAGE_MS
        set(value) { field = value.coerceIn(60_000, 1_800_000) }

    fun resetSensors() {
        calibration = Calibration.FIX_REQUIRED
        sensorAt = -1
        headingAligned = false
        learnedSeconds = 0.0
        eastBias = 0.0
        northBias = 0.0
        turnBias = 0.0
        filteredAcceleration = 0.0
        motions.clear()
    }

    fun sensorReliability(reliable: Boolean) {
        if (sensorsReliable && !reliable) resetSensors()
        sensorsReliable = reliable
    }

    fun onGps(now: Long, latitude: Double, longitude: Double, speedMps: Double?, bearing: Double?, accuracy: Double): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() || abs(latitude) > 90 || abs(longitude) > 180 ||
            !accuracy.isFinite() || accuracy !in 0.0..35.0 || now <= fixAt ||
            speedMps?.let { !it.isFinite() || it !in 0.0..75.0 } == true) return false
        val seconds = (now - fixAt) / 1000.0
        val distance = if (fixAt >= 0) driveDistanceMeters(fixLat, fixLng, latitude, longitude) else 0.0
        if (fixAt >= 0 && distance > seconds * 100 + accuracy + fixAccuracy + 20) return false
        val measuredSpeed = speedMps ?: if (fixAt >= 0 && seconds in 0.5..5.0 && distance > accuracy + fixAccuracy)
            (distance / seconds).coerceAtMost(75.0) else null
        val course = bearing?.takeIf { it.isFinite() }?.let(::normalize)
            ?: if (fixAt >= 0 && seconds in 0.5..5.0 && distance > max(5.0, accuracy + fixAccuracy))
                courseBetween(fixLat, fixLng, latitude, longitude) else null
        val freshSensors = enabled && sensorsReliable && sensorAt >= 0 && sensorAt - now in -300..3000
        if (freshSensors && measuredSpeed != null && course != null && measuredSpeed >= 2.0) {
            if (fixAt >= 0 && seconds in 0.5..3.0 && fixSpeed != null && fixBearing != null && fixSpeed!! >= 2.0) {
                learnBiases(fixAt, now, fixSpeed!!, fixBearing!!, measuredSpeed, course)
            }
            headingAligned = true
            calibration = if (learnedSeconds >= 3.0) Calibration.READY else Calibration.COLLECTING
        } else if (!freshSensors || now - fixAt > 3000) {
            headingAligned = false
            learnedSeconds = 0.0
            calibration = Calibration.FIX_REQUIRED
        }
        fixAt = now
        fixLat = latitude
        fixLng = longitude
        fixSpeed = measuredSpeed
        fixBearing = course
        fixAccuracy = accuracy
        lat = latitude
        lng = longitude
        speed = measuredSpeed ?: 0.0
        if (course != null && speed >= 2.0) heading = course
        filteredAcceleration = 0.0
        positionAt = now
        // GPS is timestamped at measurement, which can precede the latest IMU callback.
        // Replay only the tail after that timestamp; never integrate it twice.
        if (headingAligned) motions.filter { it.to > now }.forEach { integrate(it, max(it.from, now)) }
        return true
    }

    private fun learnBiases(from: Long, to: Long, oldSpeed: Double, oldCourse: Double, newSpeed: Double, newCourse: Double) {
        var covered = 0.0
        var east = 0.0
        var north = 0.0
        var turn = 0.0
        motions.forEach { m ->
            val dt = (min(m.to, to) - max(m.from, from)).coerceAtLeast(0) / 1000.0
            covered += dt
            east += m.east * dt
            north += m.north * dt
            turn += m.turn * dt
        }
        val dt = (to - from) / 1000.0
        if (covered < dt * 0.85) return
        val oldRad = Math.toRadians(oldCourse)
        val newRad = Math.toRadians(newCourse)
        val observedEastBias = east / covered - (newSpeed * sin(newRad) - oldSpeed * sin(oldRad)) / dt
        val observedNorthBias = north / covered - (newSpeed * cos(newRad) - oldSpeed * cos(oldRad)) / dt
        val observedTurnBias = turn / covered - signedAngle(newCourse - oldCourse) / dt
        // Reject GPS/phone disturbances. Real acceleration and cornering are removed using GPS velocity change.
        if (hypot(observedEastBias, observedNorthBias) > 2.5 || abs(observedTurnBias) > 5.0) return
        val gain = if (learnedSeconds == 0.0) 0.5 else dt / (8.0 + dt)
        eastBias += gain * (observedEastBias.coerceIn(-1.5, 1.5) - eastBias)
        northBias += gain * (observedNorthBias.coerceIn(-1.5, 1.5) - northBias)
        turnBias += gain * (observedTurnBias.coerceIn(-2.0, 2.0) - turnBias)
        learnedSeconds += dt
    }

    /** Earth-frame horizontal acceleration (m/s²), clockwise gyro rate about Earth up (degrees/s). */
    fun onMotion(now: Long, eastAcceleration: Double, northAcceleration: Double, yawRateDegrees: Double) {
        if (!eastAcceleration.isFinite() || !northAcceleration.isFinite() || !yawRateDegrees.isFinite() || now <= sensorAt) return
        val previous = sensorAt
        sensorAt = now
        if (previous < 0) return
        if (now - previous > 1000 || hypot(eastAcceleration, northAcceleration) > 30 || abs(yawRateDegrees) > 120) {
            resetSensors()
            sensorAt = now
            return
        }
        val motion = Motion(previous, now, eastAcceleration, northAcceleration, yawRateDegrees)
        motions.addLast(motion)
        while (motions.isNotEmpty() && motions.first().to < now - 10_000) motions.removeFirst()
        if (!enabled || !sensorsReliable || !headingAligned || fixAt < 0 || now - fixAt !in 0..maxOutageMs) return
        integrate(motion, max(previous, positionAt))
    }

    private fun integrate(m: Motion, from: Long) {
        val dt = (m.to - from) / 1000.0
        if (dt <= 0) return
        val turn = (m.turn - turnBias) * dt
        val midHeading = Math.toRadians(heading + turn / 2)
        val acceleration = ((m.east - eastBias) * sin(midHeading) + (m.north - northBias) * cos(midHeading)).coerceIn(-6.0, 4.0)
        filteredAcceleration += dt / (0.35 + dt) * (acceleration - filteredAcceleration)
        val corrected = if (abs(filteredAcceleration) < 0.06) 0.0 else filteredAcceleration
        val nextSpeed = (speed + corrected * dt).coerceIn(0.0, 75.0)
        val distance = (speed + nextSpeed) / 2 * dt
        lat += Math.toDegrees(distance * cos(midHeading) / 6371008.8)
        lng += Math.toDegrees(distance * sin(midHeading) / (6371008.8 * cos(Math.toRadians(lat)).coerceAtLeast(0.01)))
        lng = (lng + 540) % 360 - 180
        lat = lat.coerceIn(-89.9, 89.9)
        speed = nextSpeed
        heading = normalize(heading + turn)
        positionAt = m.to
    }

    fun isReady(now: Long): Boolean = enabled && calibration == Calibration.READY && headingAligned && sensorsReliable &&
        fixAt >= 0 && now - fixAt in 0..maxOutageMs && sensorAt >= 0 && now - sensorAt in 0..1000

    fun diagnostic(now: Long): String = "imuReady=${isReady(now)} calibration=$calibration enabled=$enabled " +
        "reliable=$sensorsReliable aligned=$headingAligned gpsAt=$fixAt gpsAgeMs=${if (fixAt >= 0) now - fixAt else -1} " +
        "sensorAgeMs=${if (sensorAt >= 0) now - sensorAt else -1} limitMs=$maxOutageMs"

    fun reading(now: Long): Reading {
        val sensorExpired = sensorAt >= 0 && now - sensorAt > 1000
        if (sensorExpired) resetSensors()
        if (fixAt < 0) return Reading(Mode.NO_FIX, reason = "no_gps_anchor")
        val age = now - fixAt
        if (age in 0..3000) return Reading(Mode.GPS, fixLat, fixLng, fixSpeed, fixBearing, fixAccuracy,
            age, fixAt)
        val refusal = when {
            !enabled -> "inertial_disabled"
            !sensorsReliable -> "sensors_unreliable"
            sensorExpired -> "imu_stale"
            calibration != Calibration.READY || !headingAligned -> "imu_not_calibrated"
            sensorAt < fixAt || now - sensorAt !in 0..1000 -> "imu_stale"
            age !in 3001..maxOutageMs -> "configured_outage_limit"
            else -> null
        }
        if (refusal != null) return Reading(Mode.NO_FIX, outageMillis = age, reason = refusal)
        val seconds = age / 1000.0
        // Drift indicator only, not a measured confidence radius.
        val uncertainty = fixAccuracy + 0.5 * seconds + 0.04 * seconds.pow(2)
        return Reading(Mode.ESTIMATED, lat, lng, speed, heading, uncertainty, age, positionAt)
    }

    private fun courseBetween(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val a = Math.toRadians(aLat)
        val b = Math.toRadians(bLat)
        val d = Math.toRadians(bLng - aLng)
        return normalize(Math.toDegrees(atan2(sin(d) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(d))))
    }
    private fun normalize(degrees: Double) = (degrees % 360 + 360) % 360
    private fun signedAngle(degrees: Double) = (degrees + 540) % 360 - 180
    companion object { const val DEFAULT_OUTAGE_MS = 600_000L }
}
