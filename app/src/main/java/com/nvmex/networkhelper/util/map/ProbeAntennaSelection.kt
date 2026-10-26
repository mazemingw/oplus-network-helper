package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NrCellParamItem

data class ProbedStation(val key: String, val lte: List<LteCellParam> = emptyList(), val nr: List<NrCellParamItem> = emptyList())

/** Component-lifetime pins are separate from the transient selected marker. */
class ProbeAntennaSelection {
    private val pinned = linkedMapOf<String, ProbedStation>()
    private var selected: ProbedStation? = null
    fun select(station: ProbedStation?): Boolean {
        if (selected == station) return false
        selected = station
        return true
    }
    fun isPinned(station: ProbedStation) = station.key in pinned
    fun togglePin(station: ProbedStation): Boolean {
        if (pinned.remove(station.key) != null) return false
        pinned[station.key] = station
        return true
    }
    fun displayed(exclusion: StationExclusion): Pair<List<LteCellParam>, List<NrCellParamItem>> {
        val stations = (pinned.values + listOfNotNull(selected)).distinctBy { it.key }
        return stations.flatMap { it.lte }.filterNot { exclusion.excludes(it) } to stations.flatMap { it.nr }
    }
    fun clear() { selected = null; pinned.clear() }
}
