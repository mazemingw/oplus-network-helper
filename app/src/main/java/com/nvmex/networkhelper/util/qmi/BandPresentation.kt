package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.network.LTE_GLOBAL_BANDS
import com.nvmex.networkhelper.util.network.NR_GLOBAL_BANDS
import com.nvmex.networkhelper.util.network.utils.nrArfcnToMhz
import com.nvmex.networkhelper.model.network.NrCaCarrier
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import java.util.Locale

data class MainlandBandRule(val lte: Set<Int>, val nr: Set<Int>)

/** Display-only frequency conversion. Ambiguous ranges retain every candidate band. */
fun qmiNrBandsForChannel(channel: Int, mcc: String = "", mnc: String = ""): List<Int> {
    if (channel !in 1..3279165) return emptyList()
    val mhz = nrArfcnToMhz(channel)
    val bands = NR_GLOBAL_BANDS.filter { it.duplex != "SUL" && mhz in it.dlLowMhz..it.dlHighMhz }
        .map { it.band }.distinct().sorted()
    val operatorBands = mainlandBandRule(mcc, mnc)?.nr
    return bands.filter { operatorBands == null || it in operatorBands }.takeIf { it.isNotEmpty() } ?: bands
}

fun qmiNrCaBandLabel(carrier: NrCaCarrier, mcc: String = "", mnc: String = ""): String =
    carrier.bandText.uppercase().takeIf { it.matches(Regex("N[0-9]+(?:/N[0-9]+)*")) }
        ?: qmiNrBandsForChannel(carrier.dlArfcn, mcc, mnc).joinToString("/") { "N$it" }.ifEmpty { "N?" }

/** User-defined mainland carrier plans, shared by the help dialog and both locking UIs. */
fun mainlandBandRule(mcc: String, mnc: String): MainlandBandRule? {
    if (mcc.trim() != "460" || !mnc.trim().matches(Regex("[0-9]{1,3}"))) return null
    return when (mnc.trim().padStart(2, '0')) {
        "00", "02", "07", "15" -> MainlandBandRule(setOf(3, 8, 34, 38, 39, 40, 41), setOf(3, 8, 28, 41, 78, 79))
        "01", "06", "09", "03", "05", "11" -> MainlandBandRule(setOf(1, 3, 5, 8), setOf(1, 3, 5, 8, 78))
        else -> null
    }
}

fun qmiVisibleBands(domain: QmiBand, hardware: Set<Int>, mainland: Boolean, mcc: String, mnc: String): Set<Int> {
    val rule = if (mainland) mainlandBandRule(mcc, mnc) else null
    val allowed = when (domain) {
        QmiBand.LTE -> rule?.lte
        QmiBand.NR_SA, QmiBand.NR_NSA -> rule?.nr
        else -> null
    }
    return allowed?.let(hardware::intersect) ?: hardware
}

fun qmiHelpBandNumbers(domain: QmiBand, hardware: Set<Int>?, fallback: Set<Int>, mcc: String, mnc: String): Set<Int> =
    if (hardware == null) fallback else qmiVisibleBands(domain, hardware, true, mcc, mnc)

fun qmiPruneSelection(selection: QmiProfile, hardware: Map<QmiBand, Set<Int>>?, mainland: Boolean, mcc: String, mnc: String): QmiProfile =
    selection.copy(bands = QmiBand.entries.associateWith { domain ->
        if (domain.rat !in selection.rats) emptySet()
        else {
            val chosen = selection.bands[domain].orEmpty()
            chosen.intersect(qmiVisibleBands(domain, hardware?.get(domain) ?: chosen, mainland, mcc, mnc))
        }
    })

private val nominalGhz = mapOf(
    5 to 0.85, 6 to 0.85, 7 to 2.6, 9 to 1.8, 12 to 0.7, 13 to 0.7, 14 to 0.7,
    17 to 0.7, 18 to 0.85, 19 to 0.85, 20 to 0.8, 26 to 0.85, 28 to 0.7, 30 to 2.3,
    31 to 0.45, 38 to 2.6, 40 to 2.3, 41 to 2.6, 48 to 3.5, 65 to 2.1, 66 to 2.1,
    68 to 0.7, 71 to 0.6, 77 to 3.7, 78 to 3.5, 79 to 4.9, 85 to 0.7,
    257 to 28.0, 258 to 26.0, 259 to 40.0, 260 to 39.0, 261 to 28.0, 262 to 47.5
)
private val utraGhz = mapOf(1 to 2.1, 2 to 1.9, 3 to 1.8, 4 to 2.1, 5 to 0.85, 6 to 0.85,
    7 to 2.6, 8 to 0.9, 9 to 1.7, 10 to 2.1, 11 to 1.5, 12 to 0.7, 13 to 0.7, 14 to 0.7, 19 to 0.85)

/** Nominal frequency labels, not the current cell's channel frequency. SUL deliberately has no DL label. */
fun qmiBandButtonLabel(domain: QmiBand, band: Int): String {
    if (domain == QmiBand.GSM) return "$band MHz"
    val nr = domain.rat == "nr"
    val prefix = if (nr) "N" else "B"
    val nrPlan = if (nr) NR_GLOBAL_BANDS.firstOrNull { it.band == band } else null
    if (nrPlan?.duplex == "SUL") return "$prefix$band SUL"
    val ghz = if (domain == QmiBand.WCDMA) utraGhz[band] else {
        val center = if (nr) nrPlan?.let { (it.dlLowMhz + it.dlHighMhz) / 2000.0 }
        else LTE_GLOBAL_BANDS.firstOrNull { it.band == band }?.let {
            (it.dlLowMhz + (it.earfcnEnd - it.earfcnStart) * 0.05) / 1000.0
        }
        center?.let { nominalGhz[band] ?: String.format(Locale.US, "%.1f", it).toDouble() }
    }
    val frequency = ghz?.let {
        if (it == 0.85 || it == 0.45) String.format(Locale.US, "%.2fG", it) else String.format(Locale.US, "%.1fG", it)
    } ?: "—"
    return "$prefix$band $frequency"
}

data class QmiBandObservation(
    val bands: Map<QmiBand, Map<Int, Long>>, val sampleAt: Long,
    val reportedBands: Map<QmiBand, Set<Int>> = bands.mapValues { it.value.keys },
) {
    fun active(domain: QmiBand): Set<Int> = bands[domain].orEmpty().keys
    /** Serving + configured CA, including deactivated SCCs. This is not registration evidence. */
    fun highlighted(domain: QmiBand): Set<Int> = reportedBands[domain].orEmpty()
}

private fun bandNumber(value: String): Int? = Regex("^(?:N|B|BAND)?\\s*(\\d+)$", RegexOption.IGNORE_CASE)
    .matchEntire(value.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

/** Registered serving cells plus active, fresh CA carriers of this physical slot. */
fun qmiObserveBands(state: NetworkPanelUiState?, slot: Int, now: Long): QmiBandObservation {
    val observed = QmiBand.entries.associateWith { linkedMapOf<Int, Long>() }
    val reported = QmiBand.entries.associateWith { linkedSetOf<Int>() }
    if (state == null) return QmiBandObservation(observed, 0)
    fun fresh(at: Long) = at > 0 && now - at in 0..12_000L
    fun add(domain: QmiBand, band: Int?, at: Long) {
        if (band != null && fresh(at)) {
            observed.getValue(domain)[band] = maxOf(observed.getValue(domain)[band] ?: 0, at)
            reported.getValue(domain).add(band)
        }
    }
    val mode = state.nrMode.uppercase(Locale.ROOT)
    val nsa = mode.startsWith("NSA") || mode.endsWith("-NSA")
    val nrDomains = when {
        nsa -> listOf(QmiBand.NR_NSA)
        mode == "SA" || mode.endsWith("-SA") -> listOf(QmiBand.NR_SA)
        else -> listOf(QmiBand.NR_SA, QmiBand.NR_NSA)
    }
    fun serving(s: NetworkPanelUiState) {
        val band = bandNumber(s.band)
        when (s.cellType.uppercase(Locale.ROOT)) {
            "NR" -> nrDomains.forEach { add(it, band, s.updatedAt) }
            "LTE" -> add(QmiBand.LTE, band, s.updatedAt)
            "WCDMA", "UMTS", "HSPA", "HSPAP", "HSDPA", "HSUPA" -> add(QmiBand.WCDMA, band, s.updatedAt)
            "GSM", "GPRS", "EDGE" -> add(QmiBand.GSM, band, s.updatedAt)
        }
    }
    serving(state)
    state.nsaLteAnchor?.let { serving(it.copy(updatedAt = state.updatedAt)) }
    if (nsa && state.anchorAvailable) add(QmiBand.LTE, bandNumber(state.anchorBand), state.updatedAt)
    state.nrCaInfo?.takeIf { it.slot == slot && fresh(it.updatedAt) }?.let { ca ->
        ca.carriers.forEach { carrier ->
            val band = bandNumber(carrier.bandText) ?: if (!ca.frequencyOnly)
                NrcaTranslator.bandShort(carrier.bandRaw).toIntOrNull()
            else null
            val configured = carrier.dlStateRaw in 1..2 || carrier.ulStateRaw in 1..2
            if (configured || ca.frequencyOnly) {
                // QMI frequency-only reports have no activation state. Highlight only an
                // unambiguous display band; never reinterpret engineering ARFCNs as lock targets.
                val displayBand = band ?: if (ca.frequencyOnly)
                    bandNumber(qmiNrCaBandLabel(carrier, state.mcc, state.mnc)) else null
                if (displayBand != null) nrDomains.forEach { reported.getValue(it).add(displayBand) }
            }
            if (!ca.frequencyOnly && (carrier.dlStateRaw == 2 || carrier.ulStateRaw == 2)) {
                nrDomains.forEach { add(it, band, ca.updatedAt) }
            }
        }
    }
    state.lteCaInfo?.takeIf { it.slot == slot && fresh(it.updatedAt) }?.let { ca ->
        ca.carriers.filter { it.scellState == 2 || (it.idx == 0 && it.scellState == null) }.forEach {
            add(QmiBand.LTE, it.band ?: it.bandText?.let(::bandNumber), ca.updatedAt)
        }
    }
    return QmiBandObservation(observed, state.updatedAt, reported)
}

/** Confirm only observed targets: accepted mask readback alone is not proof of registration. */
fun qmiMatchingRegistration(selection: QmiProfile, observation: QmiBandObservation, issuedAt: Long): Set<String> {
    val matching = linkedSetOf<String>()
    for ((domain, allowed) in selection.bands) {
        if (allowed.isEmpty() || domain.rat !in selection.rats) continue
        if (domain == QmiBand.NR_SA && selection.nrMode == "nsa") continue
        if (domain == QmiBand.NR_NSA && selection.nrMode == "sa") continue
        val fresh = observation.bands[domain].orEmpty().filterValues { it > issuedAt }.keys
        if (!allowed.containsAll(fresh)) return emptySet()
        matching += fresh.map { (if (domain.rat == "nr") "N" else if (domain == QmiBand.GSM) "GSM " else "B") + it }
    }
    return matching
}
