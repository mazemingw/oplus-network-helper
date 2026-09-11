package com.nvmex.networkhelper.model.wifi

data class PingSample(
    val t: Long,          // timestamp
    val ms: Double?,      // RTT(ms); null = timeout/fail
)