package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import org.json.JSONObject

data class QmiServingCell(val rat: String, val channel: Int, val pci: Int, val band: Int?,
                          val rsrp: Double?, val at: Long, val nci: Long? = null) {
    val label: String get() = "$rat · $channel · PCI $pci"
}
data class QmiCellTarget(val rat: String, val channel: Int? = null, val pcis: Set<Int> = emptySet(),
                         val bands: Set<Int> = emptySet(), val gnbIds: Set<Long> = emptySet(), val gnbBits: Int? = null)
enum class QmiCellLockState { NONE, UNKNOWN, MATCHED, OUTSIDE }

fun qmiRsrp(value: String): Double? = value.trim().replace("dBm", "", ignoreCase = true).trim().toDoubleOrNull()
    ?.takeIf { it.isFinite() && it in -160.0..-20.0 }

fun qmiServingCells(state: NetworkPanelUiState?, now: Long): List<QmiServingCell> {
    if (state == null || state.updatedAt <= 0 || now - state.updatedAt !in 0..12000) return emptyList()
    fun cell(s: NetworkPanelUiState): QmiServingCell? {
        val rat = s.cellType.uppercase()
        if (rat !in setOf("LTE", "NR")) return null
        val channel = s.arfcn.toIntOrNull()?.takeIf { it in if (rat == "NR") 1..3279165 else 0..262143 } ?: return null
        val pci = s.pci.toIntOrNull()?.takeIf { it in if (rat == "NR") 0..1007 else 0..503 } ?: return null
        val band = s.band.trim().removePrefix("N").removePrefix("n").removePrefix("B").toIntOrNull()?.takeIf { it in 1..512 }
        val rsrp = if (rat == "NR") qmiRsrp(s.ssRsrp) ?: qmiRsrp(s.rsrp) else qmiRsrp(s.rsrp)
        return QmiServingCell(rat, channel, pci, band, rsrp, state.updatedAt, s.ci.toLongOrNull())
    }
    val anchor = state.nsaLteAnchor ?: if (state.anchorAvailable && state.nrMode.startsWith("NSA", true))
        state.copy(cellType = "LTE", arfcn = state.anchorArfcn, pci = state.anchorPci, band = state.anchorBand,
            rsrp = state.anchorRsrp, ci = state.anchorCi, nsaLteAnchor = null) else null
    return listOfNotNull(cell(state), anchor?.let(::cell)).distinctBy { Triple(it.rat, it.channel, it.pci) }
}

private fun cellTargetMatch(target: QmiCellTarget, cell: QmiServingCell): Boolean? {
    if (target.rat != cell.rat || (target.channel != null && target.channel != cell.channel)) return false
    if (target.pcis.isNotEmpty() && cell.pci !in target.pcis) return false
    if (target.bands.isNotEmpty() && cell.band != null && cell.band !in target.bands) return false
    if (target.gnbIds.isNotEmpty()) {
        val bits = target.gnbBits?.takeIf { it in 22..32 } ?: return null
        val nci = cell.nci?.takeIf { it in 0 until (1L shl 36) } ?: return null
        if (nci ushr (36 - bits) !in target.gnbIds) return false
    }
    if (target.bands.isNotEmpty() && cell.band == null) return null
    return target.channel != null || target.gnbIds.isNotEmpty()
}

fun qmiCellTargetMatches(target: QmiCellTarget, cell: QmiServingCell): Boolean = cellTargetMatch(target, cell) == true

fun qmiCellTargetsFromInput(input: QmiCellInput): List<QmiCellTarget> {
    val command = qmiCellCommand(input) // Reuse full input validation.
    if (input.mode == QmiCellMode.PLMN) return emptyList()
    fun ints(key: String) = command.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty()
    return listOf(when (input.mode) {
        QmiCellMode.LTE -> QmiCellTarget("LTE", command.getInt("earfcn"), ints("pci_list"))
        QmiCellMode.NR_GNB -> QmiCellTarget("NR", gnbIds = command.getJSONArray("gnb_ids").let { a ->
            (0 until a.length()).map { a.getLong(it) }.toSet() }, gnbBits = command.getInt("id_bits"))
        QmiCellMode.NR_ARFCN -> QmiCellTarget("NR", command.getInt("arfcn"))
        else -> QmiCellTarget("NR", command.getInt("arfcn"),
            if (input.mode == QmiCellMode.NR_PCI) setOf(command.getInt("pci")) else ints("pci_list"), setOf(command.getInt("band")))
    })
}

internal fun qmiCellTargetsFromState(state: JSONObject): List<QmiCellTarget> = buildList {
    state.optJSONObject("lte_cell_lock")?.takeIf { it.optBoolean("valid") }?.optJSONArray("locks")?.let { locks ->
        repeat(locks.length()) { i -> locks.optJSONObject(i)?.let { lock ->
            val channel = lock.optInt("earfcn", -1); val pci = lock.optInt("pci", -1)
            if (channel in 0..262143 && pci in 0..503) add(QmiCellTarget("LTE", channel, setOf(pci)))
        } }
    }
    state.optJSONObject("nr_cell_lock")?.takeIf { it.optBoolean("valid") && it.optString("type") != "none" }?.let { nr ->
        nr.optJSONObject("pci_lock")?.let { lock ->
            val channel = lock.optInt("arfcn", -1); val pci = lock.optInt("pci", -1)
            val bands = lock.optJSONArray("bands")?.let { a -> (0 until a.length()).map { a.getInt(it) }.filter { it in 1..512 }.toSet() }.orEmpty()
            if (channel in 1..3279165 && pci in 0..1007) add(QmiCellTarget("NR", channel, setOf(pci), bands))
        }
        nr.optJSONArray("arfcn_lock")?.let { locks -> repeat(locks.length()) { i ->
            val channel = locks.optJSONObject(i)?.optInt("arfcn", -1) ?: -1
            if (channel in 1..3279165) add(QmiCellTarget("NR", channel))
        } }
        nr.optJSONObject("gnb_allowlist")?.let { lock ->
            val bits = lock.optInt("id_bits", -1)
            val ids = lock.optJSONArray("gnb_ids")?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }.orEmpty()
            if (bits in 22..32 && ids.isNotEmpty()) add(QmiCellTarget("NR", gnbIds = ids, gnbBits = bits))
        }
    }
}

fun qmiCellLockState(device: QmiDeviceState?, cells: List<QmiServingCell>, fallback: List<QmiCellTarget> = emptyList()): QmiCellLockState {
    if (device?.controlReady != true) return QmiCellLockState.UNKNOWN
    val rats = listOf("LTE", "NR").filter { !device.locks[it.lowercase()].isNullOrEmpty() }
    if (rats.isEmpty()) return if (device.locks["lte"] == "" && device.locks["nr"] == "") QmiCellLockState.NONE else QmiCellLockState.UNKNOWN
    if (cells.isEmpty()) return QmiCellLockState.UNKNOWN
    val observed = cells.filter { it.rat in rats }
    if (observed.isEmpty()) return QmiCellLockState.OUTSIDE
    val targets = device.cellTargets + fallback.filter { f -> device.cellTargets.none { it.rat == f.rat } }
    val matches = observed.map { cell -> targets.filter { it.rat == cell.rat }.map { cellTargetMatch(it, cell) } }
    if (matches.any { it.isNotEmpty() && it.all { match -> match == false } }) return QmiCellLockState.OUTSIDE
    return if (matches.all { true in it }) QmiCellLockState.MATCHED else QmiCellLockState.UNKNOWN
}

/** A command acknowledgement or an already-cached serving cell cannot confirm a new lock. */
fun qmiCellRegistrationMatches(device: QmiDeviceState?, targets: List<QmiCellTarget>,
                               cells: List<QmiServingCell>, issuedAt: Long): List<QmiServingCell> {
    if (device?.controlReady != true) return emptyList()
    return cells.filter { cell ->
        val readback = device.cellTargets.filter { it.rat == cell.rat }
        cell.at > issuedAt && !device.locks[cell.rat.lowercase()].isNullOrEmpty() &&
            targets.any { qmiCellTargetMatches(it, cell) } &&
            (readback.isEmpty() || readback.any { qmiCellTargetMatches(it, cell) })
    }
}
