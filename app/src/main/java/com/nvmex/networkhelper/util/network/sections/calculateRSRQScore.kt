package com.nvmex.networkhelper.util.network.sections

// RSRQ评分函数
fun calculateRSRQScore(rsrq: Double): Double {
    return when {
        rsrq >= -8.0 -> 100.0
        rsrq >= -10.0 -> 85.0
        rsrq >= -12.0 -> 70.0
        rsrq >= -14.0 -> 60.0
        rsrq >= -16.0 -> 50.0
        rsrq >= -18.0 -> 40.0
        rsrq >= -20.0 -> 25.0
        else -> 10.0
    }
}