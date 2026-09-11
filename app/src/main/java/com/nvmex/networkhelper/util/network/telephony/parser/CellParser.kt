package com.nvmex.networkhelper.util.network.telephony.parser

import android.telephony.CellInfo
import android.telephony.SignalStrength
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

interface CellParser {
    fun canParse(info: CellInfo): Boolean
    fun parse(base: NetworkPanelUiState, info: CellInfo, sig: SignalStrength?): NetworkPanelUiState
}