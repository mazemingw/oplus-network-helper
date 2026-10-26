package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NrCellParamItem

internal fun matchServingLte(state: NetworkPanelUiState, rows: List<LteCellParam>, reference: ServingStationReference?): LteCellParam? {
    val nearby = rows.filter { servingStationInRange(it.latitude, it.longitude, reference) }
    val candidates = servingLteCandidates(state, nearby)
    return candidates.takeIf { values -> values.map { it.latitude to it.longitude }.distinct().size == 1 }?.firstOrNull()
}

internal fun servingLteCandidates(state: NetworkPanelUiState, rows: List<LteCellParam>): List<LteCellParam> {
    if (!state.cellType.equals("LTE", true)) return emptyList()
    val id = state.ci.trim().toLongOrNull()?.takeIf { it > 0 }
    val tac = state.tac.trim().toIntOrNull()
    val frequency = state.arfcn.trim().toIntOrNull()
    val pci = state.pci.trim().toIntOrNull()
    if (id != null) {
        // ECI identifies the cell even when engineering data predates a channel reconfiguration.
        return rows.filter { it.eci == id }
            .preferKnownValue(tac) { it.tac }
            .preferKnownValue(pci) { it.pci }
            .preferKnownValue(frequency) { it.earfcn }
    }
    return rows.filter { row ->
        tac != null && frequency != null && pci != null && row.tac == tac && row.earfcn == frequency && row.pci == pci
    }
}

internal fun matchServingNr(state: NetworkPanelUiState, rows: List<NrCellParamItem>, reference: ServingStationReference?): NrCellParamItem? {
    val nearby = rows.filter { servingStationInRange(it.latitude, it.longitude, reference) }
    val candidates = servingNrCandidates(state, nearby)
    return candidates.takeIf { values -> values.map { it.latitude to it.longitude }.distinct().size == 1 }?.firstOrNull()
}

internal fun servingNrCandidates(state: NetworkPanelUiState, rows: List<NrCellParamItem>): List<NrCellParamItem> {
    if (!state.cellType.equals("NR", true)) return emptyList()
    val id = state.ci.trim().toLongOrNull()?.takeIf { it > 0 }
    val tac = state.tac.trim().toIntOrNull()
    val frequency = state.arfcn.trim().toIntOrNull()
    val pci = state.pci.trim().toIntOrNull()
    if (id != null) {
        return rows.filter { it.gcell_id?.trim()?.toLongOrNull() == id }
            .preferKnownValue(tac) { it.nr_tac }
            .preferKnownValue(pci) { it.nr_pci }
            .preferKnownValue(frequency) { it.nr_arfcn }
    }
    return rows.filter { row ->
        tac != null && frequency != null && pci != null && row.nr_tac == tac && row.nr_arfcn == frequency && row.nr_pci == pci
    }
}

private fun <T> List<T>.preferKnownValue(value: Int?, field: (T) -> Int?): List<T> =
    if (value == null) this else filter { field(it) == value }.ifEmpty { this }

internal fun validStationCoordinates(lat: Double?, lng: Double?): Boolean =
    lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 &&
        lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)
