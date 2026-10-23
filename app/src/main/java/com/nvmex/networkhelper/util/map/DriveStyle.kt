package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState

//路测颜色转换工具
object DriveStyle {
    data class SignalBand(val minimum: Int?, val color: Int, val rangeLabel: String?)

    // Shared by point/track rendering and the map legend. Ranges are inclusive integer dBm.
    val signalBands = listOf(
        SignalBand(-80, 0xFF1E88E5.toInt(), "≥ −80"),
        SignalBand(-90, 0xFF43A047.toInt(), "−90 ~ −81"),
        SignalBand(-100, 0xFFF9A825.toInt(), "−100 ~ −91"),
        SignalBand(-110, 0xFFFB8C00.toInt(), "−110 ~ −101"),
        SignalBand(Int.MIN_VALUE, 0xFFE53935.toInt(), "< −110"),
        SignalBand(null, 0xFF808080.toInt(), null)
    )

    fun cellKey(s: NetworkPanelUiState): String {
        // LTE 用 eci，NR 用 nci（你这里都存在在 ci 字段里：NR 解析时你应该也写到 ci 了）
        // 再拼上 arfcn+pci+tac，避免某些 ROM ci 取不到时全是 "-"
        return buildString {
            append(s.cellType).append("|")
            append(s.ci).append("|")
            append(s.arfcn).append("|")
            append(s.pci).append("|")
            append(s.tac)
        }
    }

    fun parseDbm(v: String): Int? {
        // 允许 "-95", "-95 dBm", " -95 " 这种
        val t = v.trim()
        if (t.isBlank() || t == "-") return null
        val num = t.replace("dBm", "", ignoreCase = true).trim()
        return num.toIntOrNull()
    }

    fun colorByRsrp(rsrp: Int?): Int {
        if (rsrp == null) return signalBands.last().color
        return signalBands.first { it.minimum != null && rsrp >= it.minimum }.color
    }
}
