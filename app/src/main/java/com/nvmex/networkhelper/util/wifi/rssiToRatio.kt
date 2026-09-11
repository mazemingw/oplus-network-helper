package com.nvmex.networkhelper.util.wifi

import kotlin.math.ln

fun rssiToRatio(rssiDbm: Int): Float {
    // -90..-40 映射到 0..1（更像工程范围）
    val clamped = rssiDbm.coerceIn(-90, -40)
    return (clamped + 90) / 50f
}

fun speedToRatio(
    mbps: Int,
    standardLabel: String?
): Float {
    if (mbps <= 0) return 0f

    val max = when (standardLabel) {
        "N" -> 300.0
        "AC" -> 1200.0
        "AX" -> 2400.0
        "BE" -> 5000.0
        else -> 2000.0
    }

    val x = mbps.coerceAtMost(max.toInt()).toDouble()
    return (ln(1.0 + x) / ln(1.0 + max)).toFloat()
}

fun retryToRatio(retryPerSec: Double): Float {
    if (retryPerSec <= 0.0) return 0f

    // 你实际能看到 800+，我建议把“很差上限”先放到 1500/s
    val max = 1500.0

    val x = retryPerSec.coerceIn(0.0, max)

    // log(1+x) / log(1+max) -> 0..1
    val ratio = ln(1.0 + x) / ln(1.0 + max)
    return ratio.toFloat()
}

fun badToRatio(badPerSec: Double): Float {
    if (badPerSec <= 0.0) return 0f

    val max = 300.0 // 视你的实际数据再调，先别用 20 这种“童话值”
    val x = badPerSec.coerceIn(0.0, max)

    val ratio = ln(1.0 + x) / ln(1.0 + max)
    return ratio.toFloat()
}
