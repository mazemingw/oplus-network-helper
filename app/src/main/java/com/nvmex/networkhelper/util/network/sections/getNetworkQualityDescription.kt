package com.nvmex.networkhelper.util.network.sections

import com.nvmex.networkhelper.model.network.QualityLevel
import kotlin.math.roundToInt

data class NetworkQualityAssessment(
    val level: QualityLevel,
    val text: String,
    val score: Int
)

private fun parseMetricValue(value: String): Double? {
    if (value.isBlank() || value == "-" || value == "N/A") return null
    return try {
        value
            .replace("dBm", "", ignoreCase = true)
            .replace("dB", "", ignoreCase = true)
            .replace(" ", "")
            .replace("≤", "")
            .replace("≥", "")
            .replace("<", "")
            .replace(">", "")
            .replace("~", "")
            .trim()
            .toDoubleOrNull()
    } catch (_: Exception) {
        null
    }
}

private fun scoreLabel(level: QualityLevel, score: Int): String {
    val prefix = when (level) {
        QualityLevel.EXCELLENT -> "优秀"
        QualityLevel.GOOD -> "良好"
        QualityLevel.FAIR -> "一般"
        QualityLevel.POOR -> "较差"
        QualityLevel.VERY_POOR -> "很差"
        QualityLevel.INTERFERENCE -> "干扰"
        QualityLevel.WEAK_COVERAGE -> "弱覆盖"
        QualityLevel.FAR_AWAY -> "远离基站"
        QualityLevel.CLEAN_WEAK -> "纯净弱信号"
        QualityLevel.UNKNOWN -> "未知"
    }
    return if (level == QualityLevel.UNKNOWN) prefix else "$prefix ${score}分"
}

fun assessNetworkQuality(
    rsrpString: String,
    sinrString: String,
    rsrqString: String,
    rssiString: String? = null,
    isNR: Boolean = false
): NetworkQualityAssessment {
    val rsrp = parseMetricValue(rsrpString)
    val sinr = parseMetricValue(sinrString)
    val rsrq = parseMetricValue(rsrqString)
    val rssi = rssiString?.let(::parseMetricValue)

    if (rsrp == null || sinr == null) {
        return NetworkQualityAssessment(
            level = QualityLevel.UNKNOWN,
            text = "数据不足",
            score = 0
        )
    }

    val rsrpScore = calculateRSRPScore(rsrp)
    val sinrScore = calculateSINRScore(sinr)
    val rsrqScore = rsrq?.let(::calculateRSRQScore) ?: 50.0
    val rssiScore = if (!isNR && rssi != null) calculateRSSIScore(rssi, rsrp) else null

    val totalScore = if (rssiScore != null) {
        (rsrpScore * 0.40) + (sinrScore * 0.35) + (rsrqScore * 0.15) + (rssiScore * 0.10)
    } else {
        (rsrpScore * 0.45) + (sinrScore * 0.40) + (rsrqScore * 0.15)
    }
    val score = totalScore.roundToInt().coerceIn(0, 100)

    val scenario: Pair<QualityLevel, String>? = when {
        // 强信号但体验差：你关心的核心场景
        rsrp >= -90.0 && sinr <= 5.0 && (rsrq == null || rsrq <= -14.0) -> {
            val label = when {
                !isNR && rssi != null && rssi >= -72.0 && sinr <= 3.0 -> "强信号但过载"
                rsrq != null && rsrq <= -20.0 -> "强信号但同频干扰"
                else -> "强信号但干扰"
            }
            QualityLevel.INTERFERENCE to label
        }
        rsrp <= -120.0 && sinr <= 5.0 -> QualityLevel.FAR_AWAY to "远离基站"
        rsrp <= -112.0 && sinr <= 3.0 -> QualityLevel.WEAK_COVERAGE to "弱覆盖"
        rsrp <= -110.0 && sinr >= 12.0 && (rsrq == null || rsrq >= -14.0) ->
            QualityLevel.CLEAN_WEAK to "纯净弱信号"
        rsrp in -100.0..-94.0 && sinr in 0.0..6.0 ->
            QualityLevel.POOR to "临界信号"
        rsrp >= -95.0 && sinr in 4.0..10.0 && rsrq != null && rsrq <= -13.0 ->
            QualityLevel.INTERFERENCE to "信号稳但干扰"
        rsrp <= -124.0 || sinr <= -10.0 ->
            QualityLevel.VERY_POOR to "极差信号"
        else -> null
    }

    if (scenario != null) {
        val (level, label) = scenario
        return NetworkQualityAssessment(
            level = level,
            text = "$label ${score}分",
            score = score
        )
    }

    val level = when {
        score >= 85 -> QualityLevel.EXCELLENT
        score >= 70 -> QualityLevel.GOOD
        score >= 55 -> QualityLevel.FAIR
        score >= 40 -> QualityLevel.POOR
        else -> QualityLevel.VERY_POOR
    }

    return NetworkQualityAssessment(
        level = level,
        text = scoreLabel(level, score),
        score = score
    )
}

// 兼容旧调用
fun getNetworkQualityDescription(
    rsrpString: String,
    sinrString: String,
    rsrqString: String,
    rssiString: String? = null,
    isNR: Boolean = false
): String {
    return assessNetworkQuality(
        rsrpString = rsrpString,
        sinrString = sinrString,
        rsrqString = rsrqString,
        rssiString = rssiString,
        isNR = isNR
    ).text
}

fun getNetworkQualityLevel(
    rsrpString: String,
    sinrString: String,
    rsrqString: String,
    rssiString: String? = null,
    isNR: Boolean = false
): QualityLevel {
    return assessNetworkQuality(
        rsrpString = rsrpString,
        sinrString = sinrString,
        rsrqString = rsrqString,
        rssiString = rssiString,
        isNR = isNR
    ).level
}
