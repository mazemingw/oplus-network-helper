package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NrCellParamItem

internal fun matchServingLte(state: NetworkPanelUiState, rows: List<LteCellParam>): LteCellParam? {
    val candidates = servingLteCandidates(state, rows).filter { validStationCoordinates(it.latitude, it.longitude) }
    return candidates.takeIf { values -> values.map { it.latitude to it.longitude }.distinct().size == 1 }?.firstOrNull()
}

internal fun servingLteCandidates(state: NetworkPanelUiState, rows: List<LteCellParam>): List<LteCellParam> {
    if (!state.cellType.equals("LTE", true)) return emptyList()
    val id = state.ci.trim().toLongOrNull()?.takeIf { it > 0 }
    val tac = state.tac.trim().toIntOrNull()
    val frequency = state.arfcn.trim().toIntOrNull()
    val pci = state.pci.trim().toIntOrNull()
    return rows.filter { row ->
        if (id != null) row.eci == id && (frequency == null || row.earfcn == null || row.earfcn == frequency)
        else tac != null && frequency != null && pci != null && row.tac == tac && row.earfcn == frequency && row.pci == pci
    }
}

internal fun matchServingNr(state: NetworkPanelUiState, rows: List<NrCellParamItem>): NrCellParamItem? {
    val candidates = servingNrCandidates(state, rows).filter { validStationCoordinates(it.latitude, it.longitude) }
    return candidates.takeIf { values -> values.map { it.latitude to it.longitude }.distinct().size == 1 }?.firstOrNull()
}

internal fun servingNrCandidates(state: NetworkPanelUiState, rows: List<NrCellParamItem>): List<NrCellParamItem> {
    if (!state.cellType.equals("NR", true)) return emptyList()
    val id = state.ci.trim().toLongOrNull()?.takeIf { it > 0 }
    val tac = state.tac.trim().toIntOrNull()
    val frequency = state.arfcn.trim().toIntOrNull()
    val pci = state.pci.trim().toIntOrNull()
    return rows.filter { row ->
        if (id != null) row.gcell_id?.trim()?.toLongOrNull() == id && (frequency == null || row.nr_arfcn == null || row.nr_arfcn == frequency)
        else tac != null && frequency != null && pci != null && row.nr_tac == tac && row.nr_arfcn == frequency && row.nr_pci == pci
    }
}

internal fun validStationCoordinates(lat: Double?, lng: Double?): Boolean =
    lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 &&
        lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)
