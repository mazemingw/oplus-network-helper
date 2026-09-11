package com.nvmex.networkhelper.util.network.sections

// RSRP评分函数
fun calculateRSRPScore(rsrp: Double): Double {
    return when {
        rsrp >= -75.0 -> 100.0
        rsrp >= -85.0 -> 90.0
        rsrp >= -95.0 -> 75.0
        rsrp >= -105.0 -> 60.0
        rsrp >= -115.0 -> 40.0
        rsrp >= -125.0 -> 20.0
        else -> 5.0
    }
}