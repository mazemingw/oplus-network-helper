package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

internal class DriveHistory(points: List<DrivePoint>) {
    val pointsBySlot = points.groupBy { it.simSlot }
        .mapValues { (_, values) -> values.sortedBy { it.ts } }
    val firstPoint = points.minByOrNull { it.ts }
    val snapshots: Map<Int, NetworkPanelUiState> = pointsBySlot.mapValues { (_, values) ->
        values.first().snapshot
    }

    fun initialSlot(preferred: Int): Int = preferred.takeIf { it in pointsBySlot }
        ?: pointsBySlot.keys.minOrNull() ?: 0

    fun filteredBy(filter: DriveBandFilter): DriveHistory = if (!filter.active) this
        else DriveHistory(pointsBySlot.values.flatten().filter { filter.matches(it.snapshot) })
}
