package com.nvmex.networkhelper.iperf

data class IperfRunOutput(
    val rawText: String,
    val jsonText: String? // 如果 -J 成功，会是完整 JSON
)

