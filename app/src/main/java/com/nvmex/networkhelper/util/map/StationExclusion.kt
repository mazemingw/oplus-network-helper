package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.map.LteCellParam

/** A display-only block list for LTE records whose spectrum may have been refarmed. */
data class StationExclusion(val bands: Set<Int> = emptySet(), val earfcns: Set<Int> = emptySet()) {
    val active get() = bands.isNotEmpty() || earfcns.isNotEmpty()
    fun excludes(channel: Int?, reportedBand: String? = null): Boolean {
        if (channel != null && channel in earfcns) return true
        val band = channel?.let { lteEarfcnToFreqMhzCN(it)?.band }
            ?: reportedBand?.trim()?.removePrefix("B")?.removePrefix("b")?.toIntOrNull()
        return band != null && band in bands
    }
    fun excludes(row: LteCellParam) = excludes(row.earfcn)
    fun excludes(state: NetworkPanelUiState?) = state?.cellType.equals("LTE", true) &&
        excludes(state?.arfcn?.trim()?.toIntOrNull(), state?.band)
    internal fun excludes(snapshot: DriveStationSnapshot, state: NetworkPanelUiState?) = active && snapshot.rat == "LTE" &&
        (excludes(state) || snapshot.sectors.isNotEmpty() && snapshot.sectors.all { excludes(it.arfcn) })
    fun bandInput() = bands.sorted().joinToString(",")
    fun channelInput() = earfcns.sorted().joinToString(",")
    fun summary() = listOfNotNull(
        bands.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",") { "B$it" },
        earfcns.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")?.let { "EARFCN $it" }
    ).joinToString(" · ")

    companion object {
        val selectableBands = listOf(1, 3, 5, 8, 34, 38, 39, 40, 41)
        fun parseChannels(input: String): Set<Int> {
            if (input.isBlank()) return emptySet()
            val tokens = input.split(',', '，').map(String::trim).filter(String::isNotEmpty)
            require(tokens.isNotEmpty())
            return tokens.map { value ->
                require(value.matches(Regex("[0-9]+")))
                requireNotNull(value.toIntOrNull()).also { require(it >= 0) }
            }.toSortedSet()
        }
    }
}
