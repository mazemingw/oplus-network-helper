package com.nvmex.networkhelper.util.network.telephony

import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager

/**
 * 只负责从 CellInfo 列表挑 serving（把策略集中到一个地方）
 */
class ServingCellPicker {

    fun pick(cellInfos: List<CellInfo>, isNsa: Boolean, dataType: Int): CellInfo {
        if (cellInfos.isEmpty()) throw IllegalArgumentException("cellInfos is empty")

        if (isNsa) {
            val nrList = cellInfos.filterIsInstance<CellInfoNr>()
            // NSA 下 NR 常是 secondary，优先 secondary -> registered -> first
            val nrServing = nrList.firstOrNull { it.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING }
                ?: nrList.firstOrNull { it.isRegistered }
                ?: nrList.firstOrNull()
            if (nrServing != null) return nrServing
            // 没有 NR，就回退常规（大概率 LTE anchor）
        } else if (dataType == TelephonyManager.NETWORK_TYPE_NR) {
            val nrList = cellInfos.filterIsInstance<CellInfoNr>()
            val nrServing = nrList.firstOrNull { it.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING }
                ?: nrList.firstOrNull { it.isRegistered }
                ?: nrList.firstOrNull()
            if (nrServing != null) return nrServing
        }

        // 常规：primary serving > registered > first
        return cellInfos.firstOrNull { it.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING }
            ?: cellInfos.firstOrNull { it.isRegistered }
            ?: cellInfos.first()
    }

    /**
     * 可选：如果你想把“顶部网络类型纠正”也集中在 picker 层（非必须）
     */
    fun derivedTypeName(isNsa: Boolean, serving: CellInfo, fallback: String): String = when {
        isNsa -> "NR"
        serving is CellInfoNr -> "NR"
        serving is CellInfoLte -> "LTE"
        serving is CellInfoWcdma -> "WCDMA"
        else -> fallback
    }
}