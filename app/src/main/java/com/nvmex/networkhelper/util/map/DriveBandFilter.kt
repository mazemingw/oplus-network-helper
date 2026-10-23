package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import java.util.Locale

/** Filters recorded serving bands, never CA text or an ambiguous inference from an ARFCN. */
data class DriveBandFilter(val lte: Set<Int> = emptySet(), val nr: Set<Int> = emptySet()) {
    val active: Boolean get() = lte.isNotEmpty() || nr.isNotEmpty()

    fun matches(state: NetworkPanelUiState): Boolean {
        if (!active) return true
        return when (state.cellType.trim().uppercase(Locale.ROOT)) {
            "LTE" -> bandNumber(state.band, 'B') in lte
            "NR" -> bandNumber(state.band, 'N') in nr
            else -> false
        }
    }

    fun lteInput(): String = lte.sorted().joinToString(",")
    fun nrInput(): String = nr.sorted().joinToString(",")
    fun summary(separator: String = " · "): String = listOfNotNull(
        lte.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",") { "B$it" }?.let { "4G $it" },
        nr.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",") { "n$it" }?.let { "5G $it" }
    ).joinToString(separator)

    companion object {
        private val digits = Regex("[0-9]+")
        fun parseLte(input: String): Set<Int> = parse(input, 'B')
        fun parseNr(input: String): Set<Int> = parse(input, 'N')

        private fun parse(input: String, prefix: Char): Set<Int> {
            if (input.isBlank()) return emptySet()
            val tokens = input.split(',', '，').map(String::trim).filter(String::isNotEmpty)
            require(tokens.isNotEmpty())
            return tokens.map { requireNotNull(bandNumber(it, prefix)) }.toSortedSet()
        }

        private fun bandNumber(raw: String, prefix: Char): Int? {
            val text = raw.trim().uppercase(Locale.ROOT).removePrefix(prefix.toString())
            if (!text.matches(digits)) return null
            return text.toIntOrNull()?.takeIf { it > 0 && it < Int.MAX_VALUE }
        }
    }
}
