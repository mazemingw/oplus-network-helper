package com.nvmex.networkhelper.util.map

/** SDK network fixes may omit elapsedRealtimeNanos. Their UTC measurement time is still usable. */
internal fun driveFixElapsedMillis(elapsedNanos: Long, utcMillis: Long, nowElapsed: Long, nowUtc: Long): Long? {
    val measured = when {
        elapsedNanos > 0 -> elapsedNanos / 1_000_000
        utcMillis > 0 -> nowElapsed - (nowUtc - utcMillis)
        else -> nowElapsed // A successful SDK callback without either optional timestamp.
    }
    return measured.takeIf { nowElapsed - it in -1000..15_000 }?.coerceAtMost(nowElapsed)
}

internal fun driveValidPosition(latitude: Double, longitude: Double, accuracy: Float): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        accuracy.isFinite() && accuracy >= 0
