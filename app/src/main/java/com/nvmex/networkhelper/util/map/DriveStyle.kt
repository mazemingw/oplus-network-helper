package com.nvmex.networkhelper.util.map

import android.graphics.Color
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

//路测颜色转换工具
object DriveStyle {

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
        // 你可以按自己口味调阈值
        return when {
            rsrp == null -> Color.GRAY
            rsrp >= -80  -> Color.parseColor("#1E88E5") // 好：蓝
            rsrp >= -90  -> Color.parseColor("#43A047") // 绿
            rsrp >= -100 -> Color.parseColor("#F9A825") // 黄
            rsrp >= -110 -> Color.parseColor("#FB8C00") // 橙
            else         -> Color.parseColor("#E53935") // 红：很差
        }
    }
}