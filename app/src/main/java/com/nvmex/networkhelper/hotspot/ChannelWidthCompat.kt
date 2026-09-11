package com.nvmex.networkhelper.hotspot

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

object ChannelWidthCompat {
    // VPNHotspot 里 SoftApConfigurationCompat.CHANNEL_WIDTH_AUTO = -1
    const val AUTO = -1

    private fun softApInfoClass(): Class<*>? = runCatching {
        Class.forName("android.net.wifi.SoftApInfo")
    }.getOrNull()

    private fun intField(name: String): Int? = runCatching {
        softApInfoClass()?.getField(name)?.getInt(null)
    }.getOrNull()

    val W20: Int? get() = intField("CHANNEL_WIDTH_20MHZ")
    val W40: Int? get() = intField("CHANNEL_WIDTH_40MHZ")
    val W80: Int? get() = intField("CHANNEL_WIDTH_80MHZ")
    val W160: Int? get() = intField("CHANNEL_WIDTH_160MHZ")
    val W320: Int? get() = intField("CHANNEL_WIDTH_320MHZ") // WiFi7/EHT 才可能有

    fun label(width: Int): String = when (width) {
        AUTO -> "Auto"
        W20 -> "20 MHz"
        W40 -> "40 MHz"
        W80 -> "80 MHz"
        W160 -> "160 MHz"
        W320 -> "320 MHz"
        else -> "Unknown($width)"
    }

    fun optionsForBand(band: Int): List<Int> {
        val base = mutableListOf(AUTO)

        // 保守策略：2.4G 不给太激进；5/6G 才放宽
        when (band) {
            android.net.wifi.SoftApConfiguration.BAND_2GHZ -> {
                W20?.let(base::add)
                W40?.let(base::add)
            }
            android.net.wifi.SoftApConfiguration.BAND_5GHZ -> {
                W20?.let(base::add); W40?.let(base::add)
                W80?.let(base::add); W160?.let(base::add)
            }
            android.net.wifi.SoftApConfiguration.BAND_6GHZ -> {
                W20?.let(base::add); W40?.let(base::add)
                W80?.let(base::add); W160?.let(base::add)
                W320?.let(base::add)
            }
            else -> { /* 60G 先别硬刚，只 Auto */ }
        }

        return base.distinct()
    }

    @ChecksSdkIntAtLeast(api = 33)
    fun isFrameworkConfigBandwidthSupported(): Boolean = Build.VERSION.SDK_INT >= 33
}
