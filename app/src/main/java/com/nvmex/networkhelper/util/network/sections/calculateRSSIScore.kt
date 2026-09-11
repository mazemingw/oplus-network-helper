package com.nvmex.networkhelper.util.network.sections

// RSSI评分函数（LTE专用）
fun calculateRSSIScore(rssi: Double, rsrp: Double): Double {
    // RSSI应该比RSRP高，差值过大说明干扰严重
    val diff = rssi - rsrp // 正常情况差值在10-20dB左右

    return when {
        diff <= 15.0 -> 100.0  // 干扰很小
        diff <= 25.0 -> 80.0   // 轻微干扰
        diff <= 35.0 -> 50.0   // 中等干扰
        diff <= 45.0 -> 30.0   // 较强干扰
        else -> 10.0           // 严重干扰
    }
}