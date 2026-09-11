package com.nvmex.networkhelper.hotspot.sections

import android.net.wifi.SoftApConfiguration
import com.nvmex.networkhelper.hotspot.ChannelOption

 fun fallbackChannelOptionsForBand(band: Int): List<ChannelOption> {
    fun dfs5g(ch: Int) = ch in 52..144

    val raw = when (band) {
        SoftApConfiguration.BAND_2GHZ -> listOf(1, 6, 11, 14)
        SoftApConfiguration.BAND_5GHZ -> listOf(36, 40, 44, 48, 149, 153, 157, 161, 165, 52, 56, 60, 64)
        SoftApConfiguration.BAND_6GHZ -> listOf(1, 5, 9, 13, 17, 21, 25, 29, 33)
        SoftApConfiguration.BAND_60GHZ -> listOf(1, 2, 3, 4, 5, 6)
        else -> emptyList()
    }

    val items = raw.distinct().sorted().map { ch ->
        val isDfs = (band == SoftApConfiguration.BAND_5GHZ) && dfs5g(ch)
        val text = buildString {
            append("CH $ch")
            if (isDfs) append(" · DFS")
        }
        ChannelOption(
            channel = ch,
            freqMhz = null,
            isDfs = isDfs,
            display = text
        )
    }

    return listOf(ChannelOption(0, null, false, "Auto")) + items
}