package com.nvmex.networkhelper.model.network

data class NetworkPanelUiState(
    val hasPhonePermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val isLocationEnabled: Boolean = false,

    val operatorName: String = "-",
    val mcc: String = "-",
    val mnc: String = "-",

    val dataNetworkType: String = "-",
    val dataNetworkTypeRaw: Int? = null,
    val dataNetworkTypeRawName: String = "-",
    val overrideNetworkTypeRaw: Int? = null,
    val overrideNetworkTypeName: String = "-",
    val nrMode: String = "-",          // SA/NSA/NSA-ADV/-
    val cellType: String = "-",        // NR/LTE/...
    val duplex: String = "-",          // TDD/FDD/-

    val tac: String = "-",
    val pci: String = "-",
    val ci: String = "-", // 注意这里是长号，短号要自己算
    val arfcn: String = "-",
    val band: String = "-",

    // 频率：TDD 用 freqDl；FDD 用 freqDl + freqUl
    val freqDl: String = "-",
    val freqUl: String = "-",

    // 蜂窝链路速率估计：来自 NetworkCapabilities，单位 Kbps
    val linkDownstreamKbps: Int? = null,
    val linkUpstreamKbps: Int? = null,

    // LTE / NR 主展示信号（UI 可以用这三个做“当前指标”）
    val rssi: String = "-",
    val rsrp: String = "-",
    val rsrq: String = "-",
    val sinr: String = "-",

    // NR 专属：SS/CSI 三件套
    val ssRsrp: String = "-",
    val ssRsrq: String = "-",
    val ssSinr: String = "-",
    val csiRsrp: String = "-",
    val csiRsrq: String = "-",
    val csiSinr: String = "-",

    val nrCa: String = "-",
    val nrCaInfo: NrCaInfo? = null,
    val lteCaInfo: LteCaInfo? = null,

    // =========================
    // NSA: LTE Anchor 备用信息（用于 UI 切换显示 4G）
    // =========================
    val anchorAvailable: Boolean = false,
    val anchorDuplex: String = "-",
    val anchorTac: String = "-",
    val anchorPci: String = "-",
    val anchorCi: String = "-",
    val anchorArfcn: String = "-",
    val anchorBand: String = "-",
    val anchorFreqDl: String = "-",
    val anchorFreqUl: String = "-",
    val anchorRssi: String = "-",
    val anchorRsrp: String = "-",
    val anchorRsrq: String = "-",
    val anchorSinr: String = "-",
    val anchorBandCombo: String = "-",  // e.g. "B3 FDD + N41 TDD"
    // NSA 下的备用视图：LTE Anchor 解析结果
    val nsaLteAnchor: NetworkPanelUiState? = null,

    // NSA 下的主显示偏好：true=显示 NR, false=显示 LTE
    val preferNrInNsa: Boolean = true,


    val lastError: String? = null,
    val updatedAt: Long = 0L,
    val subId: Int = -1
)

data class NrCaCarrier(
    val ccId: Int,
    val sccId: Int,
    val pci: Int,
    val bandRaw: Int,
    val bandText: String,
    val dlArfcn: Int,
    val dlStateRaw: Int,
    val dlStateText: String,
    val dlBwRaw: Int,
    val dlBwText: String,
    val ulStateRaw: Int,
    val ulStateText: String,
    val ulBwRaw: Int,
    val ulBwText: String
)

data class NrCaInfo(
    val slot: Int,
    val type: Int,
    val carriers: List<NrCaCarrier>,
    val updatedAt: Long
)

data class LteCaCarrier(
    val idx: Int,
    val pci: Int,
    val dlEarfcn: Int,
    val ulEarfcn: Int,
    val band: Int?,
    val bandText: String?,
    val dlBwRaw: Int?,
    val dlBwText: String?,
    val scellState: Int?,
    val scellStateText: String?,
    val ulEnabled: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?
)

data class LteCaInfo(
    val slot: Int,
    val carriers: List<LteCaCarrier>,
    val updatedAt: Long
)
