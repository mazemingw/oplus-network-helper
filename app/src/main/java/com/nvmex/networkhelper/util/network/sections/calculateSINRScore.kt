package com.nvmex.networkhelper.util.network.sections

// SINR评分函数
fun calculateSINRScore(sinr: Double): Double {
    return when {
        sinr >= 25.0 -> 100.0
        sinr >= 20.0 -> 90.0
        sinr >= 15.0 -> 80.0
        sinr >= 10.0 -> 70.0
        sinr >= 5.0 -> 55.0
        sinr >= 0.0 -> 40.0
        sinr >= -5.0 -> 25.0
        else -> 10.0
    }
}