package dev.ririxi.bunnylock.backend.qualcomm

import dev.ririxi.bunnylock.backend.BackendProfile
import dev.ririxi.bunnylock.backend.BackendReadout
import dev.ririxi.bunnylock.backend.CellLock
import dev.ririxi.bunnylock.backend.HalLocks
import dev.ririxi.bunnylock.backend.ModemBackend
import dev.ririxi.bunnylock.backend.ModemSession
import dev.ririxi.bunnylock.backend.NetModeResult
import dev.ririxi.bunnylock.backend.bandGroupState
import dev.ririxi.bunnylock.backend.bandsToSend
import dev.ririxi.bunnylock.backend.has5g
import dev.ririxi.bunnylock.backend.samsung.SamsungNrMode
import dev.ririxi.bunnylock.core.intList
import dev.ririxi.bunnylock.core.objects
import dev.ririxi.bunnylock.data.Carriers
import dev.ririxi.bunnylock.modem.BackendFeatures
import dev.ririxi.bunnylock.modem.Chipset
import dev.ririxi.bunnylock.modem.DeviceInfo
import dev.ririxi.bunnylock.modem.Group
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.NrMode
import dev.ririxi.bunnylock.modem.QmiReply
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.asClause
import dev.ririxi.bunnylock.modem.followedBy
import dev.ririxi.bunnylock.modem.formatPlmn
import dev.ririxi.bunnylock.modem.optText
import dev.ririxi.bunnylock.modem.optTexts
import dev.ririxi.bunnylock.modem.orList
import dev.ririxi.bunnylock.modem.runSuspendCatching
import dev.ririxi.bunnylock.root.fail
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

// Long enough for the helper to go through airplane mode when the modem only changes the lock with LTE off.
private val LTE_LOCK_TIMEOUT = 60.seconds
private const val REPORTED_EARFCN_BITS = 0xFFFFL
private val QMI_NR_MODES = mapOf(NrMode.BOTH to 0, NrMode.NSA to 1, NrMode.SA to 2)

internal class QualcommBackend(
    session: ModemSession,
    hal: HalLocks,
    private val samsung: SamsungNrMode,
) : ModemBackend(session, hal) {
    private val current get() = session.current
    private val context get() = session.context
    private val prefs get() = session.prefs

    private val samsungNrModeSims = mutableSetOf<Int>()

    private var servingReport: JSONObject? = null

    override suspend fun read(
        sim: Int,
        aospState: JSONObject?,
    ): BackendReadout {
        val qcState = session.call("qc.state", sim = sim)
        val hardware = qcState.optJSONObject("hw") ?: JSONObject()
        val hardwareNr = hardware.intList("nr")
        val groups = mutableListOf(bandGroupState(Group.LTE, hardware.intList("lte"), qcState.intList("lte")))
        when {
            hardwareNr.isNullOrEmpty() && !has5g(aospState) -> {}

            qcState.has("nrSa") || qcState.has("nrNsa") -> {
                groups += bandGroupState(Group.NR_SA, hardwareNr, qcState.intList("nrSa"))
                groups += bandGroupState(Group.NR_NSA, hardwareNr, qcState.intList("nrNsa"))
            }

            !hardwareNr.isNullOrEmpty() -> {
                groups += bandGroupState(Group.NR, hardwareNr, qcState.intList("nr"))
            }
        }
        groups += bandGroupState(Group.WCDMA, hardware.intList("wcdma"), qcState.intList("wcdma"))
        groups += bandGroupState(Group.GSM, hardware.intList("gsm"), qcState.intList("gsm"))
        val samsungNrMode = if (sim in samsungNrModeSims) samsung.read(sim) else null
        val serving = runSuspendCatching { session.call("qc.serving", sim = sim) }.getOrNull()
        servingReport = serving
        val manualSelection = qcState.optJSONObject("netSel")?.takeIf { it.optInt("mode") == Qualcomm.NETWORK_SELECTION_MANUAL }
        val qmiNrMode = qcState.optInt("nrMode", -1)
        return BackendReadout(
            groups = groups,
            nrMode = samsungNrMode ?: QMI_NR_MODES.entries.firstOrNull { it.value == qmiNrMode }?.key,
            lteLock = lteLock(qcState.optJSONObject("lteLock"), sim),
            nrLock = qcState.optJSONObject("nrLock")?.takeIf { it.optBoolean("active") }?.let(::nrLockLabel),
            plmnLock = manualSelection?.let { plmnLabel(it, qcState.optJSONObject("manualPlmn"), sim) },
            plmnReported = qcState.has("netSel"),
            cells = serving?.let { runCatching { qualcommCells(it) }.getOrNull() }.orEmpty(),
            carriers = Carriers.fromReport(serving?.optJSONArray("carriers")),
        )
    }

    private fun lteLock(
        reported: JSONObject?,
        sim: Int,
    ): String? {
        val saved = prefs.getString(lteLockKey(sim), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (reported === null) return saved?.let { context.getString(R.string.modem_lock_unconfirmed, lteLockLabel(it)) }
        if (!reported.optBoolean("active")) {
            prefs.edit().remove(lteLockKey(sim)).apply()
            return null
        }
        val setEarfcn = if (reported.optBoolean("earfcnTruncated")) saved?.optJSONArray("cells")?.optJSONObject(0)?.optLong("earfcn") else null
        return lteLockLabel(reported, setEarfcn)
    }

    private fun lteLockKey(sim: Int) = "qcLteLock.sim$sim"

    private fun lteLockLabel(
        lock: JSONObject,
        setEarfcn: Long? = null,
    ): String {
        val cellsByEarfcn = lock.getJSONArray("cells").objects().groupBy { it.optLong("earfcn") }
        return cellsByEarfcn.entries.joinToString(context.getString(R.string.modem_lock_separator)) { (reported, cells) ->
            val earfcn = setEarfcn?.takeIf { it and REPORTED_EARFCN_BITS == reported } ?: reported
            context.getString(R.string.modem_lock_channel_pci, "EARFCN", earfcn.toString(), context.orList(cells.map { it.optInt("pci").toString() }))
        }
    }

    private fun nrLockLabel(lock: JSONObject): String =
        when (val type = lock.optInt("type")) {
            Qualcomm.NR_LOCK_TYPE_PCI -> {
                context.getString(R.string.modem_lock_nr_pci, lock.optLong("arfcn"), lock.optInt("pci"), lock.optInt("band"), lock.optInt("scs"))
            }

            Qualcomm.NR_LOCK_TYPE_ARFCN -> {
                val arfcns =
                    lock
                        .optJSONArray("arfcns")
                        ?.objects()
                        .orEmpty()
                        .map { it.optLong("arfcn").toString() }
                if (arfcns.isEmpty()) {
                    context.getString(R.string.modem_lock_frequency_only)
                } else {
                    context.getString(R.string.modem_lock_channel_frequency_only, "ARFCN", context.orList(arfcns))
                }
            }

            Qualcomm.NR_LOCK_TYPE_MULTI_PCI -> {
                val pcis = lock.intList("pcis").orEmpty()
                when {
                    pcis.isNotEmpty() -> {
                        context.getString(R.string.modem_lock_channel_pci, "ARFCN", lock.optLong("arfcn").toString(), context.orList(pcis.map { it.toString() }))
                    }

                    lock.has("arfcn") -> context.getString(R.string.modem_lock_channel_several_pcis, "ARFCN", lock.optLong("arfcn").toString())

                    else -> context.getString(R.string.modem_lock_several_pcis)
                }
            }

            else -> context.getString(R.string.modem_lock_type, type)
        }

    // Some firmware only says that selection is manual, not for which network. The one bunnylock last set is shown then.
    private fun plmnLabel(
        netSel: JSONObject,
        manualPlmn: JSONObject?,
        sim: Int,
    ): String {
        val source =
            manualPlmn ?: netSel.takeIf { it.has("mcc") }
                ?: return prefs.getString(plmnKey(sim), null)?.let(::formatPlmn) ?: context.getString(R.string.modem_plmn_manual)
        val mnc = source.optInt("mnc")
        val threeDigit = manualPlmn?.optBoolean("pcs") == true || mnc >= 100
        val mncText = if (threeDigit) "%03d".format(mnc) else "%02d".format(mnc)
        return "%03d %s".format(source.optInt("mcc"), mncText)
    }

    private fun plmnKey(sim: Int) = "plmn.sim$sim"

    override suspend fun setBands(
        group: Group,
        bands: List<Int>,
        liftsLock: Boolean,
        done: Text,
    ): Text? {
        val kind =
            when (group) {
                Group.LTE -> "lte"
                Group.NR -> "nr"
                Group.NR_SA -> "nrSa"
                Group.NR_NSA -> "nrNsa"
                Group.WCDMA -> "wcdma"
                Group.GSM -> "gsm"
            }
        val result =
            session.call("qc.setBands") {
                put("kind", kind)
                put("bands", JSONArray(bandsToSend(bands, liftsLock)))
                put("persist", current.persist)
            }
        return result.optTexts("notes").firstOrNull()
    }

    override suspend fun setNetworkMode(
        mask: Long,
        throughAndroid: suspend () -> JSONObject,
    ): NetModeResult =
        try {
            NetModeResult.fromAndroid(throughAndroid())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.call("qc.setRat") {
                put("mask", mask)
                put("persist", current.persist)
            }
            NetModeResult(throughAndroid = false)
        }

    override suspend fun setNrMode(mode: NrMode) {
        try {
            session.call("qc.setNrMode") {
                put("mode", QMI_NR_MODES.getValue(mode))
                put("persist", current.persist)
            }
            samsungNrModeSims -= current.sim
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!samsung.available) throw e
            samsung.set(mode)
            samsungNrModeSims += current.sim
        }
    }

    override suspend fun lockCells(lock: CellLock): Text? {
        val lteNote =
            if (lock.lte) {
                if (lock.ltePcis.isEmpty()) fail(R.string.modem_qc_lte_needs_pci)
                val result =
                    session.call("qc.lteLock", LTE_LOCK_TIMEOUT) {
                        put("earfcn", lock.earfcn)
                        put("pcis", JSONArray(lock.ltePcis))
                    }
                val cells = JSONArray(lock.ltePcis.map { JSONObject().put("pci", it).put("earfcn", lock.earfcn) })
                prefs.edit().putString(lteLockKey(current.sim), JSONObject().put("cells", cells).toString()).apply()
                if (result.optBoolean("airplaneMode")) Text(R.string.modem_note_qc_lte_off) else null
            } else {
                null
            }
        if (lock.nr) {
            session.call("qc.nrLock") {
                put("arfcn", lock.arfcn)
                put("scs", lock.nrScs)
                lock.nrBand?.let { put("band", it) }
                when (lock.nrPcis.size) {
                    0 -> {
                        put("type", "arfcn")
                    }

                    1 -> {
                        put("type", "pci")
                        put("pci", lock.nrPcis[0])
                    }

                    else -> {
                        put("type", "multi")
                        put("pcis", JSONArray(lock.nrPcis))
                    }
                }
            }
        }
        return lteNote?.let { Text(R.string.modem_cell_locked).followedBy(it) }
    }

    override suspend fun unlockCells(): Text? {
        val lteUnlock = if (current.lteLock !== null) session.call("qc.lteUnlock", LTE_LOCK_TIMEOUT) else null
        prefs.edit().remove(lteLockKey(current.sim)).apply()
        if (current.nrLock !== null) session.call("qc.nrUnlock")
        if (lteUnlock?.optBoolean("airplaneMode") == true) return Text(R.string.modem_cell_unlocked).followedBy(Text(R.string.modem_note_qc_lte_off))
        return null
    }

    override suspend fun lockPlmn(
        mcc: String,
        mnc: String,
        throughAndroid: suspend () -> Text,
    ): Text {
        val numeric = mcc + mnc
        try {
            session.call("qc.setPlmn") {
                put("mcc", mcc.toInt())
                put("mnc", mnc.toInt())
                put("threeDigit", mnc.length == 3)
                put("persist", current.persist)
            }
            prefs.edit().putString(plmnKey(current.sim), numeric).apply()
            return Text(R.string.modem_plmn_locked_to, current.sim, formatPlmn(numeric))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.log("The QMI network lock didn't work (${session.englishReason(e).asClause()}), so Android network selection is being tried instead.")
        }
        return throughAndroid()
    }

    override suspend fun clearPlmn(throughAndroid: suspend () -> Unit) {
        runSuspendCatching { session.call("qc.clearPlmn") { put("persist", current.persist) } }
        throughAndroid()
        prefs.edit().remove(plmnKey(current.sim)).apply()
    }

    override suspend fun unlockAll(): Text? {
        session.call("qc.reset") { put("persist", current.persist) }
        runSuspendCatching { session.call("qc.lteUnlock", LTE_LOCK_TIMEOUT) }
            .onSuccess { prefs.edit().remove(lteLockKey(current.sim)).apply() }
        runSuspendCatching { session.call("qc.nrUnlock") }
        return null
    }

    override suspend fun resendLocks() {
        resendBandLocks()
        hal.resend()
    }

    override suspend fun refreshLive(sim: Int) {
        val serving = runSuspendCatching { session.call("qc.serving", sim = sim) }.getOrNull() ?: return
        servingReport = serving
        session.backendCells = runCatching { qualcommCells(serving) }.getOrDefault(session.backendCells)
        session.backendCarriers = Carriers.fromReport(serving.optJSONArray("carriers"))
    }

    override fun diagnostics() = listOf("QMI serving report: ${servingReport ?: "not read"}")

    suspend fun reattach(): Text? {
        val result = session.call("qc.reattach", 60.seconds)
        if (result.optString("method") != "radio") return null
        val qmiError = result.optText("qmiError") ?: Text(R.string.something_went_wrong)
        return Text(R.string.modem_note_reattach_radio, current.sim, qmiError)
    }

    suspend fun reboot() {
        session.call("qc.reboot", 60.seconds)
    }

    suspend fun send(
        service: Int,
        msg: Int,
        payload: String,
    ): QmiReply {
        val response =
            session.call("qc.raw") {
                put("service", service)
                put("msg", msg)
                put("payload", payload)
            }
        val error = response.optText("error")
        val englishError = error?.let(session::inEnglish).orEmpty()
        if (error !== null) response.put("error", englishError)
        session.log("Sent message 0x%04X. The modem replied: %s.".format(msg, englishError))
        return QmiReply(response.toString(2), error)
    }

    companion object : BackendProfile {
        override val chipset = Chipset.QUALCOMM

        override fun suits(device: DeviceInfo) = device.qrtr

        override fun reaches(
            device: DeviceInfo,
            shellOnly: Boolean,
        ) = !shellOnly

        override fun features(state: ModemState) =
            BackendFeatures(
                cellHint = Text(R.string.cell_hint_qualcomm),
                plmnHint = Text(R.string.cell_plmn_hint_qualcomm),
                nrMode = true,
                pciLock = true,
                nrSubcarrierSpacing = true,
                persistSwitch = true,
                reattach = true,
                qmiConsole = true,
            )
    }
}
