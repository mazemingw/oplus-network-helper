package com.nvmex.networkhelper.iperf.client

import com.nvmex.networkhelper.iperf.IperfSummary

data class IperfClientUiState(
    val running: Boolean = false,
    val config: IperfClientConfig = IperfClientConfig(),
    val summary: IperfSummary? = null,
    val log: String = "",
    val points: List<IperfPoint> = emptyList()   // ✅新增
)