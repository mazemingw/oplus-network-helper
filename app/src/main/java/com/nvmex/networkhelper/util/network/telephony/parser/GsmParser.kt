package com.nvmex.networkhelper.util.network.telephony.parser

import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.SignalStrength
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

class GsmParser : CellParser {

    override fun canParse(info: CellInfo): Boolean = info is CellInfoGsm

    override fun parse(base: NetworkPanelUiState, info: CellInfo, sig: SignalStrength?): NetworkPanelUiState {
        val g = info as CellInfoGsm
        val id = g.cellIdentity
        val ss = g.cellSignalStrength
        return base.copy(
            cellType = "GSM",
            ci = id.cid.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-",
            tac = id.lac.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-",
            rssi = ss.dbm.takeIf { it != CellInfo.UNAVAILABLE }?.let { "$it dBm" } ?: "-",
            lastError = null
        )
    }
}