package com.nvmex.networkhelper.util.home

import com.nvmex.networkhelper.model.network.NetworkPanelUiState

fun detectCarrier(state: NetworkPanelUiState): Carrier {
    val mcc = state.mcc
    val mnc = state.mnc.padStart(2, '0')   // 防止 "1" / "01" 问题
    val op = state.operatorName

    // ✅ 1) MCC/MNC 最可靠
    if (mcc == "460" && mnc != "-") {
        return when (mnc) {
            // 中国移动
            "00", "02", "07" -> Carrier.CMCC
            // 中国联通
            "01", "06", "09" -> Carrier.CUCC
            // 中国电信
            "03", "05", "11" -> Carrier.CTCC
            // 中国广电
            "15" -> Carrier.CBN
            // 中国铁通（集团内）
            "20" -> Carrier.TIETONG
            else -> Carrier.UNKNOWN
        }
    }

    // ✅ 2) 兜底：名字判断（权限不全 / ROM 抽风）
    return when {
        op.contains("移动") || op.contains("CMCC", true) -> Carrier.CMCC
        op.contains("联通") || op.contains("CUCC", true) -> Carrier.CUCC
        op.contains("电信") || op.contains("CTCC", true) -> Carrier.CTCC
        op.contains("广电") || op.contains("CBN", true) || op.contains("Broadnet", true) -> Carrier.CBN
        op.contains("铁通") || op.contains("Tietong", true) -> Carrier.TIETONG
        else -> Carrier.UNKNOWN
    }
}