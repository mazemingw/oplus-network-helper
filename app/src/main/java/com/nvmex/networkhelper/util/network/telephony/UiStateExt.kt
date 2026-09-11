package com.nvmex.networkhelper.util.network.telephony

import com.nvmex.networkhelper.model.network.NetworkPanelUiState

fun NetworkPanelUiState.withMccMnc(networkOperator: String?): NetworkPanelUiState {
    val op = networkOperator.orEmpty()
    return if (op.length >= 5) {
        copy(
            mcc = op.substring(0, 3),
            mnc = op.substring(3)
        )
    } else this
}