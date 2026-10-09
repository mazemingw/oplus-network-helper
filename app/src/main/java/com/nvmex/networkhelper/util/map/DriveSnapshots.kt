package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState

/** Converts subscription-keyed HOME telemetry to physical SIM-slot keyed points. */
internal fun driveSnapshotsBySlot(
    states: Map<Int, NetworkPanelUiState>,
    subscriptions: List<Pair<Int, Int>>
): Map<Int, NetworkPanelUiState> = subscriptions.mapNotNull { (subId, slot) ->
    if (slot < 0) null else states[subId]?.takeIf { it.subId == subId }?.let { slot to it }
}.toMap()
