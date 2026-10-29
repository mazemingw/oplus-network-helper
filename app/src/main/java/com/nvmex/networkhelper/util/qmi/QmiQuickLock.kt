package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.CellTimelinePoint
import com.nvmex.networkhelper.util.network.NR_GLOBAL_BANDS
import com.nvmex.networkhelper.util.network.utils.nrArfcnToMhz
import org.json.JSONObject
import java.util.UUID

/** Product defaults; unknown/SUL bands keep the user's SCS. */
fun qmiDefaultScs(band: String): Int? = when (NR_GLOBAL_BANDS.firstOrNull {
    it.band == band.trim().removePrefix("N").removePrefix("n").toIntOrNull()
}?.duplex) { "FDD" -> 15; "TDD" -> 30; else -> null }

fun qmiCellWithDefaultScs(input: QmiCellInput): QmiCellInput {
    if (input.mode in setOf(QmiCellMode.LTE, QmiCellMode.PLMN, QmiCellMode.NR_GNB)) return input
    val scs = if (input.mode == QmiCellMode.NR_ARFCN) {
        val mhz = input.channel.toIntOrNull()?.takeIf { it in 0..3279165 }?.let(::nrArfcnToMhz)
        // Overlapping FDD/TDD ranges do not identify a duplex mode; retain manual SCS.
        when (NR_GLOBAL_BANDS.filter { mhz != null && it.duplex in setOf("FDD", "TDD") && mhz in it.dlLowMhz..it.dlHighMhz }
            .map { it.duplex }.distinct().singleOrNull()) { "FDD" -> 15; "TDD" -> 30; else -> null }
    } else qmiDefaultScs(input.band)
    return input.copy(scs = scs ?: input.scs)
}

enum class QmiQuickLockKind { CELL, BAND }

data class QmiQuickLockRequest(val id: String, val sim: Int, val subId: Int, val kind: QmiQuickLockKind,
                               val rat: String, val band: Int, val channel: String, val pci: String) {
    fun cellInput(): QmiCellInput {
        require(kind == QmiQuickLockKind.CELL)
        return qmiCellWithDefaultScs(QmiCellInput(if (rat == "NR") QmiCellMode.NR_PCI else QmiCellMode.LTE,
            channel, pci, band.toString())).also { qmiCellCommand(it) }
    }
    fun json(): String = JSONObject().put("id", id).put("sim", sim).put("subId", subId).put("kind", kind.name)
        .put("rat", rat).put("band", band).put("channel", channel).put("pci", pci).toString()

    fun validate(): QmiQuickLockRequest = apply {
        require(id.length in 1..64 && sim in 1..2 && subId >= 0 && rat in setOf("LTE", "NR") && band in 1..512)
        if (kind == QmiQuickLockKind.CELL) cellInput()
    }
    companion object {
        fun parse(json: String): QmiQuickLockRequest {
            require(json.length <= 2048)
            val o = JSONObject(json)
            return QmiQuickLockRequest(o.getString("id"), o.getInt("sim"), o.getInt("subId"),
                QmiQuickLockKind.valueOf(o.getString("kind")), o.getString("rat"), o.getInt("band"),
                o.getString("channel"), o.getString("pci")).validate()
        }
    }
}

fun qmiQuickLockRequest(sim: Int, subId: Int, point: CellTimelinePoint, kind: QmiQuickLockKind): QmiQuickLockRequest =
    QmiQuickLockRequest(UUID.randomUUID().toString(), sim, subId, kind, point.cellType.uppercase(),
        point.band.trim().uppercase().removePrefix("N").removePrefix("B").toIntOrNull() ?: -1,
        point.arfcn, point.pci).validate()

/** Only a selection draft: preparing a timeline shortcut must never issue a modem write. */
fun qmiQuickBandSelection(request: QmiQuickLockRequest, current: QmiProfile, hardware: Map<QmiBand, Set<Int>>,
                          mainlandMode: Boolean, mcc: String, mnc: String): QmiProfile {
    request.validate()
    val domains = if (request.rat == "NR") listOf(QmiBand.NR_SA, QmiBand.NR_NSA) else listOf(QmiBand.LTE)
    require(domains.all { request.band in qmiVisibleBands(it, hardware[it].orEmpty(), mainlandMode, mcc, mnc) }) {
        "Target band is unavailable for this device/operator"
    }
    return qmiEmptyBandSelection(current).copy(rats = current.rats + domains.first().rat,
        bands = QmiBand.entries.associateWith { if (it in domains) setOf(request.band) else emptySet() })
}
