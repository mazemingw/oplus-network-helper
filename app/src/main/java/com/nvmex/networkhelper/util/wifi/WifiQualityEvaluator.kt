package com.nvmex.networkhelper.util.wifi

object WifiQualityEvaluator {
    const val LABEL_UNKNOWN = "unknown"
    const val LABEL_EXCELLENT = "excellent"
    const val LABEL_GOOD = "good"
    const val LABEL_FAIR = "fair"
    const val LABEL_POOR = "poor"
    const val LABEL_VERY_POOR = "very_poor"

    const val HINT_DEGRADED = "degraded"
    const val HINT_HIGH_RETRY = "high_retry"
    const val HINT_HIGH_BAD = "high_bad"
    const val HINT_WEAK_SIGNAL = "weak_signal"
    const val HINT_LOW_SPEED = "low_speed"

    data class Result(val score: Int, val label: String, val hint: String?)

    fun evaluate(
        rssiDbm: Int?,
        linkSpeedMbps: Int?,
        txRetryPerSec: Double?,
        txBadPerSec: Double?,
        freqMhz: Int?,
        isDegraded: Boolean
    ): Result {

        // RSSI（0..40）
        val rssiScore = when {
            rssiDbm == null -> 0
            rssiDbm >= -50 -> 40
            rssiDbm >= -60 -> 32
            rssiDbm >= -70 -> 22
            rssiDbm >= -80 -> 12
            else -> 4
        }

        // 速率（0..30）
        val speedScore = when {
            linkSpeedMbps == null -> 0
            linkSpeedMbps >= 1200 -> 30
            linkSpeedMbps >= 600 -> 22
            linkSpeedMbps >= 200 -> 14
            linkSpeedMbps >= 50 -> 8
            else -> 3
        }

        // 可靠性（0..30）——若降级则不参与
        val reliabilityScore: Int? = if (isDegraded) null else {
            val retryPenalty = when {
                txRetryPerSec == null -> 0
                txRetryPerSec <= 2 -> 0
                txRetryPerSec <= 10 -> 6
                txRetryPerSec <= 20 -> 12
                txRetryPerSec <= 40 -> 20
                else -> 28
            }
            val badPenalty = when {
                txBadPerSec == null -> 0
                txBadPerSec <= 1 -> 0
                txBadPerSec <= 5 -> 8
                txBadPerSec <= 15 -> 16
                else -> 24
            }
            (30 - (retryPenalty + badPenalty).coerceAtMost(30)).coerceIn(0, 30)
        }

        // 频段加成（0..4）
        val bandBonus = when {
            freqMhz == null -> 0
            freqMhz >= 5955 -> 4
            freqMhz >= 5000 -> 2
            else -> 0
        }

        // ===== 核心：降级时重新归一化 =====
        val base = rssiScore + speedScore + bandBonus

        val score = if (reliabilityScore == null) {
            // 最大可能：40+30+4=74，把它映射到 0..100（否则“永远上不去”会让用户误解）
            ((base / 74.0) * 100).toInt().coerceIn(0, 100)
        } else {
            (base + reliabilityScore).coerceIn(0, 100)
        }

        val label = when {
            score >= 85 -> LABEL_EXCELLENT
            score >= 70 -> LABEL_GOOD
            score >= 50 -> LABEL_FAIR
            score >= 30 -> LABEL_POOR
            else -> LABEL_VERY_POOR
        }

        val hint = buildHint(
            isDegraded = isDegraded,
            rssiDbm = rssiDbm,
            linkSpeedMbps = linkSpeedMbps,
            txRetryPerSec = txRetryPerSec,
            txBadPerSec = txBadPerSec
        )

        return Result(score, label, hint)
    }

    private fun buildHint(
        isDegraded: Boolean,
        rssiDbm: Int?,
        linkSpeedMbps: Int?,
        txRetryPerSec: Double?,
        txBadPerSec: Double?
    ): String? {
        if (isDegraded) {
            // 关键：别装懂
            return HINT_DEGRADED
        }
        return when {
            txRetryPerSec != null && txRetryPerSec > 20 -> HINT_HIGH_RETRY
            txBadPerSec != null && txBadPerSec > 5 -> HINT_HIGH_BAD
            rssiDbm != null && rssiDbm < -70 -> HINT_WEAK_SIGNAL
            linkSpeedMbps != null && linkSpeedMbps < 50 -> HINT_LOW_SPEED
            else -> null
        }
    }
}


