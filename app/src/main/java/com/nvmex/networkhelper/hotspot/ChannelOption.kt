package com.nvmex.networkhelper.hotspot

data class ChannelOption(
    val channel: Int,
    val freqMhz: Int?,      // 算不出来就 null
    val isDfs: Boolean,
    val display: String     // 下拉框展示文案
)
