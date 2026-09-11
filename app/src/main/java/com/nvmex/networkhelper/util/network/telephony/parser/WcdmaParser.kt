package com.nvmex.networkhelper.util.network.telephony.parser

import android.telephony.CellInfo
import android.telephony.CellInfoWcdma
import android.telephony.SignalStrength
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

class WcdmaParser : CellParser {

    override fun canParse(info: CellInfo): Boolean = info is CellInfoWcdma

    override fun parse(base: NetworkPanelUiState, info: CellInfo, sig: SignalStrength?): NetworkPanelUiState {
        val w = info as CellInfoWcdma
        val id = w.cellIdentity
        val ss = w.cellSignalStrength
        return base.copy(
            cellType = "WCDMA",
            ci = id.cid.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-",
            tac = id.lac.takeIf { it != CellInfo.UNAVAILABLE }?.toString() ?: "-",
            rssi = ss.dbm.takeIf { it != CellInfo.UNAVAILABLE }?.let { "$it dBm" } ?: "-",
            lastError = null
        )
    }
}