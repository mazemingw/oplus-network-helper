package com.nvmex.networkhelper.util.network

import java.math.BigDecimal

internal fun formatQosRateMbps(kbps: Long): String =
    "${BigDecimal.valueOf(kbps, 3).stripTrailingZeros().toPlainString()} Mbps"

internal fun formatQosAmbrMbps(uplinkKbps: Long?, downlinkKbps: Long?): String {
    if (uplinkKbps == null && downlinkKbps == null) return "-"
    fun rate(kbps: Long?): String = kbps?.let {
        BigDecimal.valueOf(it, 3).stripTrailingZeros().toPlainString()
    } ?: "-"
    return "▲${rate(uplinkKbps)} ▼${rate(downlinkKbps)} Mbps"
}
