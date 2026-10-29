package com.nvmex.networkhelper.util.map

/** SDK network fixes may omit elapsedRealtimeNanos. Their UTC measurement time is still usable. */
internal fun driveFixElapsedMillis(elapsedNanos: Long, utcMillis: Long, nowElapsed: Long, nowUtc: Long): Long? {
    val measured = when {
        elapsedNanos > 0 -> elapsedNanos / 1_000_000
        utcMillis > 0 -> nowElapsed - (nowUtc - utcMillis)
        else -> return null // Callback arrival is not a new location measurement.
    }
    return measured.takeIf { nowElapsed - it in -1000..15_000 }?.coerceAtMost(nowElapsed)
}

internal fun driveValidPosition(latitude: Double, longitude: Double, accuracy: Float): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracy.isFinite() && accuracy >= 0

/** One immutable location decision per sampling tick, shared by both SIMs. Null means radio-only. */
internal data class DriveSamplePosition(
    val lat: Double? = null, val lng: Double? = null,
    val source: String = "NONE", val measuredAt: Long = -1,
    val accuracy: Float? = null,
    val rawLat: Double? = null, val rawLng: Double? = null, val rawCoordType: String? = null,
    val mapMatchJson: String? = null, val reason: String = "no_location"
) {
    val located: Boolean get() = lat != null && lng != null && driveValidPosition(lat, lng, accuracy ?: 0f)
}

/** Keeps measurement time separate from callback/timer time. It never refreshes a cached fix. */
internal class DrivePositionSelector {
    private var sdk: DriveSamplePosition? = null
    private var gps: DriveSamplePosition? = null
    private var sdkGpsValidated = false
    private val latestAt = mutableMapOf<String, Long>()
    private val consumedAt = mutableMapOf<String, Long>()

    fun reliableGpsFresh(now: Long): Boolean = gps?.let { now - it.measuredAt in 0..3000 } == true

    fun offerSdk(fix: DriveSamplePosition, now: Long, estimate: DeadReckoningEngine.Reading, gpsValidated: Boolean = true): String? {
        if (!fix.located || now - fix.measuredAt !in 0..15_000) return "invalid_or_stale_sdk"
        if (fix.measuredAt <= (latestAt[fix.source] ?: -1)) return "duplicate_sdk_measurement"
        latestAt[fix.source] = fix.measuredAt
        val reliableGps = gpsValidated && fix.source == "GPS" && (fix.accuracy ?: Float.MAX_VALUE) <= 35f && now - fix.measuredAt <= 3000
        if (!reliableGps && (reliableGpsFresh(now) || estimate.mode == DeadReckoningEngine.Mode.ESTIMATED))
            return "prefer_gps_or_inertial"
        sdk = fix
        sdkGpsValidated = gpsValidated
        if (reliableGps) gps = fix
        return null
    }

    fun select(now: Long, estimate: DeadReckoningEngine.Reading, inertialEnabled: Boolean): DriveSamplePosition {
        val candidate = when {
            reliableGpsFresh(now) -> gps
            inertialEnabled && estimate.mode == DeadReckoningEngine.Mode.ESTIMATED &&
                estimate.lat != null && estimate.lng != null && now - estimate.measuredAtMillis in 0..1000 -> {
                val gcj = Gcj02.wgs84ToGcj02(estimate.lat, estimate.lng)
                DriveSamplePosition(gcj.first, gcj.second, "INERTIAL", estimate.measuredAtMillis,
                    estimate.uncertaintyMeters?.toFloat(), estimate.lat, estimate.lng, "WGS84_INERTIAL", reason = "imu")
            }
            else -> sdk?.takeIf {
                val maxAge = if (inertialEnabled && it.source == "GPS") 3000 else 5000
                (!inertialEnabled || it.source != "GPS" || sdkGpsValidated) &&
                    now - it.measuredAt in 0..maxAge && (it.accuracy ?: Float.MAX_VALUE) <= 100f
            }
        } ?: return DriveSamplePosition(reason = when {
            sdk == null -> "no_sdk_measurement:${estimate.reason}"
            inertialEnabled && sdk?.source == "GPS" && !sdkGpsValidated -> "gps_not_reliable:${estimate.reason}"
            (sdk?.accuracy ?: Float.MAX_VALUE) > 100f -> "sdk_accuracy_exceeds_100m:${estimate.reason}"
            else -> "sdk_measurement_expired:${estimate.reason}"
        })
        if (!candidate.located) return DriveSamplePosition(reason = "invalid_coordinates")
        if (candidate.measuredAt <= (consumedAt[candidate.source] ?: -1))
            return DriveSamplePosition(reason = "no_new_${candidate.source.lowercase()}_measurement")
        consumedAt[candidate.source] = candidate.measuredAt
        return candidate
    }
}
