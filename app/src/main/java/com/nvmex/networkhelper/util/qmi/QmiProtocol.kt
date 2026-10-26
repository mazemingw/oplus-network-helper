package com.nvmex.networkhelper.util.qmi

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class QmiBand(val key: String, val label: String, val rat: String) {
    GSM("gsm", "GSM", "gsm"), WCDMA("wcdma", "WCDMA", "wcdma"), LTE("lte", "LTE", "lte"),
    NR_SA("nr_sa", "NR SA", "nr"), NR_NSA("nr_nsa", "NR NSA", "nr")
}

val qmiBandDisplayOrder = listOf(QmiBand.NR_SA, QmiBand.NR_NSA, QmiBand.LTE, QmiBand.WCDMA, QmiBand.GSM)

data class QmiProfile(
    val rats: Set<String>,
    val bands: Map<QmiBand, Set<Int>>,
    val nrMode: String
)

data class QmiDeviceState(
    val sim: Int,
    val profile: QmiProfile,
    val hardware: Map<QmiBand, Set<Int>>?,
    /** null = unavailable, empty = unlocked. */
    val locks: Map<String, String?>,
    val controlReady: Boolean = true,
    val nrModeSettable: Boolean = true,
    val cellTargets: List<QmiCellTarget> = emptyList()
) {
    companion object {
        fun hardwareOnly(s: JSONObject, physicalSim: Int): QmiDeviceState? {
            val hw = s.optJSONObject("hardware") ?: return null
            return QmiDeviceState(physicalSim, QmiProfile(emptySet(), QmiBand.entries.associateWith { emptySet() }, "unknown"),
                QmiBand.entries.associateWith { hw.intSet(if (it.rat == "nr") "nr" else it.key) }, emptyMap(), controlReady = false)
        }
        fun parse(s: JSONObject): QmiDeviceState {
            require(s.optBoolean("valid")) { "Modem state unavailable" }
            val rat = s.optJSONObject("rat") ?: error("Missing RAT state")
            val hardware = s.optJSONObject("hardware")
            val capabilities = hardware?.let { hw -> QmiBand.entries.associateWith { hw.intSet(if (it.rat == "nr") "nr" else it.key) } }
            // Some firmware reports an all-bits mask for unrestricted bands. Show effective hardware bands.
            val bands = QmiBand.entries.associateWith { domain ->
                val reported = s.intSet(domain.key)
                capabilities?.get(domain)?.let(reported::intersect) ?: reported
            }
            val lte = s.optJSONObject("lte_cell_lock")
            val nr = s.optJSONObject("nr_cell_lock")
            val plmn = s.optJSONObject("plmn_lock")
            val lteLocks = lte?.optJSONArray("locks")
            val lteText = lteLocks?.objects()?.joinToString("\n") { "EARFCN ${it.getInt("earfcn")} · PCI ${it.getInt("pci")}" }.orEmpty()
            val nrText = if (nr?.optString("type") == "none") "" else buildList {
                nr?.optJSONObject("pci_lock")?.let {
                    add("NR-ARFCN ${it.optInt("arfcn")} · PCI ${it.optInt("pci")} · ${it.optInt("scs_khz")} kHz")
                }
                nr?.optJSONArray("arfcn_lock")?.objects()?.forEach {
                    add("NR-ARFCN ${it.optInt("arfcn")} · ${it.optInt("scs_khz")} kHz")
                }
                nr?.optJSONObject("multi_pci_lock")?.takeIf { it.optBoolean("present") }?.let { add("NR multi-PCI") }
                nr?.optJSONObject("gnb_allowlist")?.optJSONArray("gnb_ids")?.takeIf { it.length() > 0 }?.let {
                    add("gNB: " + (0 until it.length()).joinToString(", ") { i -> it.getLong(i).toString() })
                }
            }.joinToString("\n")
            val lockedPlmn = plmn?.optJSONObject("locked_plmn")
            val plmnText = if (plmn?.optString("mode") == "manual") {
                val p = lockedPlmn ?: plmn
                "${p.optInt("mcc").toString().padStart(3, '0')}-${p.optString("mnc_display", p.optInt("mnc").toString().padStart(2, '0'))}"
            } else ""
            return QmiDeviceState(
                s.getInt("sim"),
                QmiProfile(listOf("gsm", "wcdma", "lte", "nr").filter { rat.optBoolean(it) }.toSet(),
                    bands, s.optString("nr_mode", "unknown")),
                capabilities,
                mapOf("lte" to lteText.takeIf { lte?.optBoolean("valid") == true },
                    "nr" to nrText.takeIf { nr?.optBoolean("valid") == true },
                    "plmn" to plmnText.takeIf { plmn?.optBoolean("valid") == true }),
                cellTargets = qmiCellTargetsFromState(s)
            )
        }
    }
}

/** UI selections are an operation draft, separate from the modem's current masks. */
fun qmiEmptyBandSelection(profile: QmiProfile): QmiProfile = profile.copy(bands = QmiBand.entries.associateWith { emptySet() })

fun qmiSelectedBandTarget(current: QmiProfile, selection: QmiProfile): QmiProfile = selection.copy(
    bands = current.bands + selection.bands.filter { (domain, bands) -> domain.rat in selection.rats && bands.isNotEmpty() },
    nrMode = if ("nr" in selection.rats) selection.nrMode else current.nrMode
)

data class QmiBandUnlockPlan(val target: QmiProfile, val commands: List<JSONObject>)

/** Remove RAT/mode restrictions as well as band masks; carrier filtering is display-only. */
fun qmiBandUnlockPlan(current: QmiProfile, hardware: Map<QmiBand, Set<Int>>, nrModeSettable: Boolean = true): QmiBandUnlockPlan {
    val supportedRats = QmiBand.entries.filter { !hardware[it].isNullOrEmpty() }.map { it.rat }.toSet()
    require(supportedRats.isNotEmpty()) { "Hardware capabilities unavailable" }
    val target = QmiProfile(supportedRats, QmiBand.entries.associateWith { hardware[it].orEmpty() },
        if ("nr" in supportedRats && current.nrMode != "unknown" && nrModeSettable) "both" else current.nrMode)
    // The helper's reset restores masks only. Explicitly restore mode/RAT afterwards;
    // another tool may have changed them since our last read. Enable RATs after masks.
    val commands = buildList {
        add(qmiCommand("reset"))
        if ("nr" in supportedRats && target.nrMode != "unknown" && nrModeSettable && current.nrMode != target.nrMode)
            add(qmiCommand("mode_set").put("mode", target.nrMode))
        add(qmiCommand("rat_set").put("rat", supportedRats.sorted().joinToString(",")))
    }
    return QmiBandUnlockPlan(target, commands)
}

fun qmiCellClearCommand(mode: QmiCellMode): String = when (mode) {
    QmiCellMode.LTE -> "lte_cell_lock_clear"
    QmiCellMode.PLMN -> "plmn_lock_clear"
    else -> "nr_cell_lock_clear"
}

fun qmiCellRatCommands(input: QmiCellInput, profile: QmiProfile, nrModeSettable: Boolean = true): List<JSONObject> = buildList {
    require(input.mode != QmiCellMode.PLMN) { "PLMN is independent of RAT" }
    if (input.mode != QmiCellMode.LTE) {
        require(profile.nrMode != "unknown") { "NR SA mode cannot be verified on this firmware" }
        require(nrModeSettable || profile.nrMode == "sa") { "NR mode is read-only on this modem" }
        if (profile.nrMode != "sa") add(qmiCommand("mode_set").put("mode", "sa"))
    }
    add(qmiCommand("rat_set").put("rat", if (input.mode == QmiCellMode.LTE) "lte" else "nr"))
}

/** Clear actual active locks, independent of whichever form happens to be selected. */
fun qmiActiveCellClearCommands(locks: Map<String, String?>): List<JSONObject> =
    listOf("lte", "nr", "plmn").filter { !locks[it].isNullOrEmpty() }
        .map { qmiCommand(if (it == "plmn") "plmn_lock_clear" else "${it}_cell_lock_clear") }

fun qmiRxMetricFraction(value: Double?, min: Double, max: Double): Float? {
    require(min.isFinite() && max.isFinite() && max > min)
    return value?.takeIf { it.isFinite() }?.let { ((it - min) / (max - min)).coerceIn(0.0, 1.0).toFloat() }
}

fun qmiCommand(name: String): JSONObject = JSONObject().put("cmd", name)
private fun JSONObject.intSet(key: String): Set<Int> = optJSONArray(key)?.let { a ->
    (0 until a.length()).map { a.getInt(it) }.toSet()
}.orEmpty()
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** Build narrow updates only. Hardware validation is repeated in the root helper. */
fun qmiBandChanges(before: QmiProfile, after: QmiProfile, hardware: Map<QmiBand, Set<Int>>, combinedNr: Boolean): List<JSONObject> {
    require(after.rats.isNotEmpty() && after.rats.all { it in setOf("gsm", "wcdma", "lte", "nr") }) { "RAT" }
    QmiBand.entries.forEach { domain ->
        val bands = after.bands[domain].orEmpty()
        require(hardware[domain].orEmpty().containsAll(bands)) { "Unsupported ${domain.label} band" }
        val enabled = domain.rat in after.rats && when (domain) {
            QmiBand.NR_SA -> after.nrMode != "nsa"
            QmiBand.NR_NSA -> after.nrMode != "sa"
            else -> true
        }
        val unchangedEmptyDomain = bands == before.bands[domain] && domain.rat in before.rats && after.nrMode == before.nrMode
        require(!enabled || hardware[domain].isNullOrEmpty() || bands.isNotEmpty() || unchangedEmptyDomain) { "${domain.label}: empty band selection" }
    }
    require(after.nrMode in setOf("sa", "nsa", "both", "unknown")) { "NR mode" }
    if (combinedNr) require(after.bands[QmiBand.NR_SA] == after.bands[QmiBand.NR_NSA]) { "Combined NR bands differ" }
    return buildList {
        QmiBand.entries.forEach { domain ->
            if (combinedNr && domain.rat == "nr") return@forEach
            if (before.bands[domain] != after.bands[domain]) add(qmiCommand("${domain.key}_set").put("bands", JSONArray(after.bands[domain].orEmpty().sorted())))
        }
        if (combinedNr && (before.bands[QmiBand.NR_SA] != after.bands[QmiBand.NR_SA] || before.bands[QmiBand.NR_NSA] != after.bands[QmiBand.NR_NSA])) {
            add(qmiCommand("nr_set").put("bands", JSONArray(after.bands[QmiBand.NR_SA].orEmpty().sorted())))
        }
        if (after.nrMode != before.nrMode && after.nrMode != "unknown") add(qmiCommand("mode_set").put("mode", after.nrMode))
        if (before.rats != after.rats) add(qmiCommand("rat_set").put("rat", after.rats.sorted().joinToString(",")))
    }
}

enum class QmiCellMode { LTE, NR_ARFCN, NR_PCI, NR_MULTI_PCI, NR_GNB, PLMN }
data class QmiCellInput(
    val mode: QmiCellMode = QmiCellMode.LTE,
    val channel: String = "", val pcis: String = "", val band: String = "",
    val scs: Int = 30, val gnbBits: String = "22", val gnbIds: String = "", val plmn: String = ""
)

fun qmiCellCommand(input: QmiCellInput): JSONObject {
    fun number(text: String, range: LongRange, field: String): Long =
        text.trim().toLongOrNull()?.takeIf { it in range } ?: throw IllegalArgumentException(field)
    fun numbers(text: String, range: LongRange, max: Int, field: String): List<Long> {
        val parts = text.trim().split(Regex("[,，\\s]+"))
        require(parts.size in 1..max) { field }
        return parts.map { number(it, range, field) }.distinct()
    }
    if (input.mode == QmiCellMode.PLMN) {
        val value = input.plmn.trim()
        require(value.matches(Regex("[0-9]{5,6}"))) { "PLMN: MCC(3) + MNC(2/3)" }
        return qmiCommand("plmn_lock_set").put("mcc", value.take(3).toInt()).put("mnc", value.drop(3).toInt())
    }
    if (input.mode == QmiCellMode.NR_GNB) {
        val bits = number(input.gnbBits, 22L..32L, "gNB bits: 22–32").toInt()
        val ids = numbers(input.gnbIds, 0L until (1L shl bits), 32, "gNB ID")
        return qmiCommand("nr_cell_lock_gnb_set").put("id_bits", bits).put("gnb_ids", JSONArray(ids))
    }
    val lte = input.mode == QmiCellMode.LTE
    val channel = number(input.channel, if (lte) 0L..262143L else 0L..3279165L, if (lte) "EARFCN: 0–262143" else "NR-ARFCN: 0–3279165")
    if (lte) return qmiCommand("lte_cell_lock_multi_pci_set").put("earfcn", channel)
        .put("pci_list", JSONArray(numbers(input.pcis, 0L..503L, 64, "LTE PCI: 0–503 (max 64)")))
    require(input.scs in setOf(15, 30, 60, 120, 240)) { "SCS" }
    if (input.mode == QmiCellMode.NR_ARFCN) return qmiCommand("nr_cell_lock_arfcn_set").put("arfcn", channel).put("scs_khz", input.scs)
    val band = number(input.band, 1L..512L, "NR band: 1–512")
    val pcis = numbers(input.pcis, 0L..1007L, if (input.mode == QmiCellMode.NR_PCI) 1 else 64, "NR PCI: 0–1007")
    return qmiCommand(if (input.mode == QmiCellMode.NR_PCI) "nr_cell_lock_pci_set" else "nr_cell_lock_multi_pci_set")
        .put("arfcn", channel).put("scs_khz", input.scs).put("band", band).apply {
            if (input.mode == QmiCellMode.NR_PCI) put("pci", pcis.single()) else put("pci_list", JSONArray(pcis))
        }
}

data class QmiRxChain(val index: Int, val tuned: Boolean, val power: Double?, val rsrp: Double?, val ecio: Double?, val rscp: Double?, val phase: Double?)
data class QmiRxGroup(val rat: String, val chains: List<QmiRxChain>, val error: String? = null)

/** UIM sessions name physical slots; NAS bind uses primary/secondary subscriptions instead. */
data class QmiSimBindings(val primarySlot: Int?, val secondarySlot: Int?) {
    fun subscription(physicalSim: Int): Int {
        require(physicalSim in 1..2)
        return when (physicalSim - 1) {
            primarySlot -> 1
            secondarySlot -> 2
            else -> throw IllegalArgumentException("SIM $physicalSim has no active modem subscription")
        }
    }
    companion object {
        fun decode(hex: String): QmiSimBindings {
            val status = responseTlvs(hex, 0x002f)[0x10] ?: error("Missing UIM card status")
            require(status.size >= 8) { "Truncated UIM sessions" }
            val b = ByteBuffer.wrap(status).order(ByteOrder.LITTLE_ENDIAN)
            val primary = b.short.toInt() and 0xffff
            b.short
            val secondary = b.short.toInt() and 0xffff
            return QmiSimBindings((primary shr 8).takeIf { primary != 0xffff }, (secondary shr 8).takeIf { secondary != 0xffff })
        }
    }
}

/** NAS Get TX/RX Info (0x005a). Absent/invalid measurements stay null. */
object QmiRxDecoder {
    val reads = listOf("005a:01010008", "005a:0101000c", "005a:01010005", "005a:01010004")
    val rats = listOf("LTE", "NR", "WCDMA", "GSM")

    fun decode(hex: String, rat: String): List<QmiRxChain> {
        val tlvs = responseTlvs(hex, 0x005a)
        return listOf(0x10, 0x11, 0x15, 0x16).mapIndexedNotNull { index, tag ->
            tlvs[tag]?.let { payload ->
                require(payload.size == 21) { "Invalid RX chain length" }
                val r = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val tuned = r.get().toInt() != 0
                fun measurement() = r.int.takeIf { tuned && it != Int.MIN_VALUE }?.div(10.0)
                val power = measurement(); val ecio = measurement(); val rscp = measurement(); val rsrp = measurement()
                val phase = (r.int.toLong() and 0xffffffffL).takeIf { tuned && it != 0xffffffffL }?.div(100.0)
                val wcdma = rat == "WCDMA"
                QmiRxChain(index, tuned, power, if (rat in listOf("LTE", "NR")) rsrp?.takeIf { it > -200 } else null,
                    if (wcdma) ecio else null, if (wcdma) rscp else null, phase)
            }
        }
    }
}

internal fun responseTlvs(hex: String, expectedMessage: Int): Map<Int, ByteArray> {
        require(hex.length % 2 == 0 && hex.length <= 4096) { "Invalid QMI hex length" }
        val bytes = ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16); val low = Character.digit(hex[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "Invalid QMI hex" }
            ((high shl 4) or low).toByte()
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 7 && b.get().toInt() == 2) { "Invalid QMI response" }
        b.short
        require(b.short.toInt() and 0xffff == expectedMessage) { "Wrong QMI message" }
        require(b.short.toInt() and 0xffff == b.remaining()) { "Invalid QMI payload length" }
        val tlvs = mutableMapOf<Int, ByteArray>()
        while (b.hasRemaining()) {
            require(b.remaining() >= 3) { "Truncated QMI TLV" }
            val tag = b.get().toInt() and 255; val length = b.short.toInt() and 0xffff
            require(length <= b.remaining() && tag !in tlvs) { "Invalid QMI TLV" }
            tlvs[tag] = ByteArray(length).also(b::get)
        }
        val result = tlvs[2]?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
        require(result?.remaining() == 4) { "Missing QMI result" }
        require(result!!.short.toInt() == 0) { "QMI error ${result.short.toInt() and 0xffff}" }
        return tlvs
}
