package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.CellTimelinePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

data class QmiTimelineNeighbor(val point: CellTimelinePoint)

fun qmiLiveNeighbors(snapshot: QmiRadioSnapshot?, state: NetworkPanelUiState?, slot: Int?, now: Long): List<QmiCellCandidate> {
    val qmi = snapshot?.takeIf { it.slot == slot && now - it.updatedAt in 0..12000 }?.neighbors.orEmpty()
    val android = state?.neighborCells.orEmpty().filter { now - it.ts in 0..12000 }.mapNotNull { cell ->
        val nr = cell.cellType.equals("NR", true)
        if (!nr && !cell.cellType.equals("LTE", true)) return@mapNotNull null
        val pci = cell.pci.toIntOrNull()?.takeIf { it in if (nr) 0..1007 else 0..503 } ?: return@mapNotNull null
        val channel = cell.arfcn.toIntOrNull()?.takeIf { it in if (nr) 1..3279165 else 0..262143 } ?: return@mapNotNull null
        QmiCellCandidate(QmiCellInput(if (nr) QmiCellMode.NR_PCI else QmiCellMode.LTE, channel.toString(), pci.toString(), cell.band),
            QmiCellSource.NEIGHBOR, qmiRsrp(cell.rsrp)?.takeIf { it in -160.0..-20.0 })
    }
    return (qmi + android).distinctBy { "${it.input.channel}:${it.input.pcis}:${if (it.input.mode == QmiCellMode.LTE) "LTE" else "NR"}" }
}

/** Live measured neighbors belong beside serving history, never in its persisted visited-cell list. */
fun qmiTimelineNeighbors(snapshot: QmiRadioSnapshot?, state: NetworkPanelUiState?, slot: Int?, now: Long): List<QmiTimelineNeighbor> {
    val serving = qmiServingCells(state, now)
    return qmiLiveNeighbors(snapshot, state, slot, now).mapNotNull { neighbor ->
        val input = neighbor.input
        val rat = when (input.mode) { QmiCellMode.LTE -> "LTE"; QmiCellMode.NR_PCI, QmiCellMode.NR_ARFCN -> "NR"; else -> return@mapNotNull null }
        val channel = input.channel.toIntOrNull()?.takeIf { it in if (rat == "NR") 1..3279165 else 0..262143 } ?: return@mapNotNull null
        val pci = input.pcis.toIntOrNull()?.takeIf { it in if (rat == "NR") 0..1007 else 0..503 }
        if (pci == null) return@mapNotNull null
        if (serving.any { it.rat == rat && it.channel == channel && it.pci == pci }) return@mapNotNull null
        val band = input.band.toIntOrNull()?.takeIf { it in 1..512 }?.toString()
            ?: if (rat == "NR") qmiNrBandsForChannel(channel, state?.mcc.orEmpty(), state?.mnc.orEmpty())
                .joinToString("/") { "N$it" }.ifEmpty { "-" } else "-"
        val rsrp = neighbor.rsrp?.takeIf { it.isFinite() && it in -160.0..-20.0 }?.toString() ?: "—"
        QmiTimelineNeighbor(CellTimelinePoint(now, rat, "—", pci.toString(), "", band,
            rsrp, "—", "—", channel.toString(), state?.mcc.orEmpty(), state?.mnc.orEmpty()))
    }.sortedByDescending { qmiRsrp(it.point.rsrp) ?: Double.NEGATIVE_INFINITY }
}

/** Freeze a visible batch for at least three seconds so ranking/availability changes cannot steal a tap. */
internal class QmiNeighborDisplayHold(private val minimumMs: Long = 3000) {
    private var displayed = emptyList<QmiTimelineNeighbor>()
    private var displayedAt = 0L
    private var wasInteracting = false

    private fun identities(rows: List<QmiTimelineNeighbor>) = rows.map {
        "${it.point.cellType}:${it.point.arfcn}:${it.point.pci}:${it.point.band}"
    }

    fun retainForInteraction(now: Long) { if (displayed.isNotEmpty()) displayedAt = now }

    fun update(incoming: List<QmiTimelineNeighbor>, now: Long, interacting: Boolean = false): List<QmiTimelineNeighbor> {
        if (interacting) {
            retainForInteraction(now)
            wasInteracting = true
            return displayed
        }
        if (wasInteracting) { retainForInteraction(now); wasInteracting = false }
        if (displayed.isEmpty() || remainingMs(now) == 0L) {
            if (identities(displayed) != identities(incoming)) displayedAt = now
            displayed = incoming.toList()
        }
        return displayed
    }

    fun remainingMs(now: Long): Long = if (displayed.isEmpty()) 0 else (minimumMs - (now - displayedAt)).coerceAtLeast(0)
}
