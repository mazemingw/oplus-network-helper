package com.nvmex.networkhelper.model.network

// 质量等级枚举
enum class QualityLevel {
    EXCELLENT,     // 优秀
    GOOD,          // 良好
    FAIR,          // 一般
    POOR,          // 较差
    VERY_POOR,     // 很差
    INTERFERENCE,  // 干扰
    WEAK_COVERAGE, // 弱覆盖
    FAR_AWAY,      // 远离基站
    CLEAN_WEAK,    // 纯净弱信号
    UNKNOWN        // 未知
}