package com.nvmex.networkhelper.model.wifi

// ✅ WiFi 页面独立数据类（与 NetworkPanelUiState 完全隔离）
data class WifiUiModel(
    val isWifiConnected: Boolean = false,
    val ssid: String = "-",
    val bssid: String? = null,

    val rssiDbm: Int? = null,

    // 速率类（瞬时）
    val linkSpeedMbps: Int? = null,
    val txSpeedMbps: Int? = null,
    val rxSpeedMbps: Int? = null,

    val frequencyMhz: Int? = null,
    val channel: Int? = null,
    val standardLabel: String = "-",
    val channelWidthMhz: Int? = null,
    val gateway: String = "-",
    val localIp: String = "-",
    val dns: String = "-",

    // 备用：安全类型 int（后面需要再映射）
    val securityType: Int? = null,

    // ===== 工程指标：包计数（用于调试/可选展示）=====
    val txRetriesTotal: Long? = null,
    val txBadTotal: Long? = null,
    val txSuccessTotal: Long? = null,
    val rxSuccessTotal: Long? = null,

    // ===== 工程指标：每秒变化（真正“可变”）=====
    val txRetryPerSec: Double? = null, // retries/s
    val txBadPerSec: Double? = null,   // bad/s

    // ===== 质量评价：一次性给 UI 用 =====
    val qualityScore: Int = 0,         // 0..100
    val qualityLabel: String = "unknown",
    val qualityHint: String? = null,

    // 附近 AP（用于信道可视化）
    val nearbyChannels: List<WifiChannelSample> = emptyList()
)
