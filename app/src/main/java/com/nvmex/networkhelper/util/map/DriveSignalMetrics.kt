package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState

/** Keeps NR SS measurements distinct while allowing older devices' generic measurements as fallback. */
data class DriveSignalMetrics(val nr: Boolean, val rsrp: String?, val rsrq: String?, val sinr: String?,
    val rsrqIsSs: Boolean = false, val sinrIsSs: Boolean = false) {
    val rsrqLabel: String get() = if (rsrqIsSs) "SS-RSRQ" else "RSRQ"
    val sinrLabel: String get() = if (sinrIsSs) "SS-SINR" else "SINR"

    companion object {
        private val number = Regex("""^(-?\d+(?:\.\d+)?)\s*(?:dBm|dB)?$""", RegexOption.IGNORE_CASE)

        fun value(raw: String?): String? = raw?.trim()?.let { number.matchEntire(it)?.groupValues?.get(1) }
            ?.takeIf { it.toDoubleOrNull()?.let { value -> value.isFinite() && value != Int.MAX_VALUE.toDouble() } == true }

        fun fromState(state: NetworkPanelUiState): DriveSignalMetrics {
            val nr = state.cellType.equals("NR", true)
            fun metric(ss: String, generic: String) = if (nr) value(ss) ?: value(generic) else value(generic)
            return DriveSignalMetrics(nr, metric(state.ssRsrp, state.rsrp), metric(state.ssRsrq, state.rsrq), metric(state.ssSinr, state.sinr),
                nr && value(state.ssRsrq) != null, nr && value(state.ssSinr) != null)
        }
    }
}
