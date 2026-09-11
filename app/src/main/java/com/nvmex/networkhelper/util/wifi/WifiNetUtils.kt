package com.nvmex.networkhelper.util.wifi

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WifiNetUtils {

    /**
     * 频段 MHz -> 信道（常见口径）
     */
    fun frequencyToChannel(freqMhz: Int): Int? {
        return when {
            freqMhz in 2412..2484 -> if (freqMhz == 2484) 14 else ((freqMhz - 2407) / 5)
            freqMhz in 5000..5895 -> ((freqMhz - 5000) / 5)
            freqMhz in 5955..7115 -> ((freqMhz - 5950) / 5) // 6GHz
            else -> null
        }?.takeIf { it > 0 }
    }

    /**
     * int -> IPv4（DhcpInfo 的 int 通常是 little-endian）
     */
    fun intToIpv4(value: Int): String {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        val bytes = bb.array()
        val addr = InetAddress.getByAddress(bytes)
        return addr.hostAddress ?: "-"
    }

    /**
     * 802.11 代际推断：不依赖新 SDK 常量，靠频段+速率做“诚实推断”
     */
    fun guessWifiStandardLabel(freqMhz: Int?, linkSpeedMbps: Int?): String {
        if (freqMhz == null) return "-"

        return when {
            // 6GHz：Wi-Fi 6E/7
            freqMhz >= 5955 -> "BE / AX"

            // 5GHz + 高速率：倾向 AX
            freqMhz >= 5000 && (linkSpeedMbps ?: 0) >= 1200 -> "AX"

            // 5GHz：倾向 AC
            freqMhz >= 5000 -> "AC"

            // 2.4GHz：倾向 N
            freqMhz >= 2400 -> "N"

            else -> "-"
        }
    }
}