package com.nvmex.networkhelper.model.signalradar

/**
 * 每个角度扇区的统计结果（最终用于画图 / 保存）
 */
data class AngleBinResult(
    val binIndex: Int,         // 0..11
    val centerDeg: Float,      // 15, 45, 75...
    val count: Int,            // 样本数
    val rsrpMedian: Int?,      // dBm
    val rsrqMedian: Float?,    // dB
    val sinrMedian: Float?     // dB
)

/**
 * 扫描过程中的单条原始采样
 */
data class SignalSample(
    val t: Long,
    val headingDeg: Float,     // 0..360
    val operatorName: String,
    val mcc: String,
    val mnc: String,
    val cellType: String,      // NR/LTE/...
    val duplex: String,
    val band: String,
    val arfcn: String,
    val rsrpDbm: Int?,         // 主指标：统一成 RSRP(dBm)，NR用 SS-RSRP
    val rsrqDb: Float?,
    val sinrDb: Float?
)

/**
 * 一次完整“信号雷达扫描”的结果
 */
data class SignalScanSession(
    val startedAt: Long,
    val endedAt: Long,
    val scenarioName: String,
    val operatorName: String,
    val mcc: String,
    val mnc: String,
    val bins: List<AngleBinResult>,
    val samplesCount: Int,
    val cellType: String,  // 确保这里有 cellType
    val band: String,      // 确保这里有 band
)
