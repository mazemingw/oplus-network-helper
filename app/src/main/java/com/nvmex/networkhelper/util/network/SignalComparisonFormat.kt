package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.NetworkPanelUiState

internal fun comparisonDataSimStatus(subscriptionId: Int?, dataSubId: Int): Boolean? {
    if (subscriptionId == null || subscriptionId < 0 || dataSubId < 0) return null
    return subscriptionId == dataSubId
}

internal fun formatComparisonBand(state: NetworkPanelUiState?): String {
    val band = state?.band?.trim()?.takeUnless { it.isBlank() || it == "-" } ?: return "-"
    val match = Regex("(?:[45][gG]:)?([nNbB]?)([0-9]+)").matchEntire(band) ?: return band
    val explicitPrefix = match.groupValues[1].uppercase()
    // In NSA, an LTE cell's band remains Bxx even when the data-network label is NR.
    val prefix = explicitPrefix.ifEmpty {
        when (state.cellType.uppercase()) {
            "NR" -> "N"
            "LTE" -> "B"
            "", "-" -> when (state.dataNetworkType.uppercase()) {
                "NR" -> "N"
                "LTE", "LTE-CA" -> "B"
                else -> ""
            }
            else -> ""
        }
    }
    return when (prefix) {
        "N" -> "N${match.groupValues[2]}"
        "B" -> "B${match.groupValues[2]}"
        else -> band
    }
}
