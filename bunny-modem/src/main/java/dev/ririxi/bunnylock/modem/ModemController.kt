package dev.ririxi.bunnylock.modem

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.StringRes
import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.core.Ran
import dev.ririxi.bunnylock.core.toIntList
import dev.ririxi.bunnylock.data.Carrier
import dev.ririxi.bunnylock.data.Carriers
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Cells
import dev.ririxi.bunnylock.data.PrivilegedClient
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.RootClient
import dev.ririxi.bunnylock.data.RootException
import dev.ririxi.bunnylock.data.Signal
import dev.ririxi.bunnylock.data.reasonText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private data class BackendReadout(
    val groups: List<BandGroupState> = emptyList(),
    val nrMode: NrMode? = null,
    val lteLock: String? = null,
    val nrLock: String? = null,
    val legacyCellLock: String? = null,
    val mtkCellLockRefused: Boolean = false,
    val plmnLock: String? = null,
    /** The modem answered about network selection, so the framework's stored value must not override it. */
    val plmnReported: Boolean = false,
    val cells: List<Cell> = emptyList(),
    val carriers: List<Carrier> = emptyList(),
    val allowedMask: Long? = null,
    val carrierAggregation: Boolean? = null,
)

private const val MAX_HAL_CHANNELS = 32

// Old Qualcomm phones reject more than 8 bands per entry.
private const val MAX_HAL_BANDS_PER_SPEC = 8

// The highest LTE band Android 11 knows.
private const val NEWEST_OLD_HAL_LTE_BAND = 88

// LTE bands even old Qualcomm modems know.
private val WIDELY_DEFINED_LTE_BANDS =
    buildList {
        addAll(1..14)
        addAll(17..21)
        addAll(23..43)
        addAll(listOf(46, 47, 48, 66, 71))
    }
private val WIDELY_DEFINED_NR_BANDS = listOf(1, 2, 3, 5, 7, 8, 12, 20, 25, 28, 38, 40, 41, 48, 66, 71, 77, 78, 79)
private val EUROPE_MCCS = 200..299
private val NORTH_AMERICA_MCCS = 302..316
private val COMMON_NR_BANDS_EUROPE = listOf(1, 3, 7, 8, 20, 28, 38, 78)
private val COMMON_NR_BANDS_NORTH_AMERICA = listOf(2, 5, 25, 41, 66, 71, 77, 78)
private val COMMON_NR_BANDS_ELSEWHERE = listOf(1, 3, 5, 8, 28, 40, 41, 78)

// GSM 850, 900 (P, E and R), 1800 and 1900, as AccessNetworkConstants.GeranBand numbers.
private val GERAN_BANDS = mapOf(850 to listOf(8), 900 to listOf(9, 10, 11), 1800 to listOf(12), 1900 to listOf(13))
private val GERAN_RESTORE_BANDS = GERAN_BANDS.values.flatten()

// AccessNetworkConstants.UtranBand has no bands 15 to 18.
private val HAL_WCDMA_BANDS = Bands.WCDMA.filter { it !in 15..18 }
private val UTRAN_RESTORE_BANDS = listOf(1, 2, 3, 4, 5, 8, 9, 19)
private const val MAX_LOG_LINES = 80

/** QMI's network selection preference. 0 is automatic. */
private const val QMI_NET_SEL_MANUAL = 1

// QMI's NR cell lock types. 2, the one missing here, means no lock.
private const val QMI_NR_LOCK_PCI = 0
private const val QMI_NR_LOCK_FREQUENCY = 1
private const val QMI_NR_LOCK_SEVERAL_PCIS = 3

// Long enough for the helper to go through airplane mode when the modem only changes the lock with LTE off.
private val QC_LTE_LOCK_TIMEOUT = 60.seconds
private val NET_MODE_TIMEOUT = 60.seconds
private const val NET_MODE_SWITCH_CHECKS = 10
private val NET_MODE_SWITCH_CHECK = 1.seconds
private const val QC_REPORTED_EARFCN_BITS = 0xFFFFL

// The access technology numbers MediaTek's cell lock command takes and reports.
private const val MTK_ACT_GSM = 0
private const val MTK_ACT_UTRAN = 2
private const val MTK_ACT_LTE = 7
private const val MTK_ACT_NR = 11

private const val MTK_PCI_UNSET = 65535

// The states TelephonyManager.setNrDualConnectivityState takes. They are system API, so the SDK doesn't expose them.
private const val NR_DUAL_CONNECTIVITY_ENABLE = 1
private const val NR_DUAL_CONNECTIVITY_DISABLE = 2

private val HAL_LOCK_SETTLE = 8.seconds
private val HAL_LOCK_RECHECK = 6.seconds
private const val HAL_LOCK_RECHECKS = 2
private const val BLIND_LOCK_RECHECKS = 8
private const val MAX_MISSED_SCANS = 2

private const val PIXEL_AT_NODE = "/dev/umts_router"

private val LIVE_CELL_SCAN_WAIT = 1500.milliseconds

/** An AT node that starts like this sends commands through MediaTek's RIL passthrough instead of a modem tty. */
private const val RIL_NODE_PREFIX = "ril:"

private fun rilNode(sim: Int) = "$RIL_NODE_PREFIX$sim"

private open class Failure(
    val text: Text,
) : IllegalStateException()

private class LockUnanswered : Failure(Text(R.string.modem_lock_unanswered))

private fun fail(
    @StringRes id: Int,
    vararg args: Any,
): Nothing = throw Failure(Text(id, *args))

private fun reasonOf(e: Throwable): Text = (e as? Failure)?.text ?: e.reasonText() ?: Text(R.string.something_went_wrong)

private fun Text.followedBy(next: Text) = Text(R.string.modem_sentences, this, next)

/** [runCatching] that lets coroutine cancellation through. */
private inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

class ModemController(
    context: Context,
    private val scope: CoroutineScope,
    private val starter: HelperStarter,
    private val onConnected: () -> Unit = {},
    private val onRefreshed: (ModemState) -> Unit = {},
    private val shizukuMenuLauncher: (suspend () -> Unit)? = null,
) {
    private val context: Context = context.applicationContext
    private val englishContext: Context =
        this.context.createConfigurationContext(Configuration(this.context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
    private var helper: PrivilegedClient = RootClient(this.context)
    private val prefs = this.context.getSharedPreferences("bunnylock", Context.MODE_PRIVATE)

    /** SIMs whose 5G mode went through Samsung's service because QMI refused it, so it is read back from there too. */
    private val samsungNrModeReadbackSims = mutableSetOf<Int>()

    private val waitStopped = MutableStateFlow(false)

    private val _state =
        MutableStateFlow(
            ModemState(
                persist = prefs.getBoolean("persist", true),
                atNode = prefs.getString("atNode", "") ?: "",
                sim = prefs.getInt("sim", 1),
            ),
        )
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val helperRunning get() = helper.running
    /** Host uses readback failure separately from a setter's acknowledgement. */
    var lastRefreshFailure: String? = null
        private set

    private val current get() = _state.value

    // From the last full refresh, reused by the live ones.
    private var backendCells: List<Cell> = emptyList()
    private var backendCarriers: List<Carrier> = emptyList()

    // The raw reports behind the carriers, for diagnostics.
    private var physicalChannelReport: JSONArray? = null
    private var qualcommServingReport: JSONObject? = null

    private inline fun updateState(transform: ModemState.() -> ModemState) = _state.update(transform)

    private var connecting: Job? = null

    fun close() = helper.stop()

    fun connect() {
        if (connecting?.isActive == true) return
        connecting = scope.launch { connectNow() }
    }

    // Host integration: await discovery before selecting a driver or executing an operation.
    suspend fun connectAndWait() {
        connect()
        connecting?.join()
    }

    /** For a helper that showed up or went away on its own: waits out a running attempt, which may already have settled it. */
    fun reconnect() {
        scope.launch {
            connecting?.join()
            if (current.root != RootState.Granted || !helper.running) connect()
        }
    }

    fun helperLost(reason: Text) {
        helper.stop()
        log(inEnglish(reason))
        updateState { copy(root = RootState.Denied(context.string(reason))) }
    }

    private suspend fun connectNow() {
        updateState { copy(root = RootState.Checking) }
        helper.stop()
        try {
            val started = starter.start(::showConnectStep)
            helper = started.client
            val hello = started.hello
            val access = started.access
            val shellOnly = access == Access.SHIZUKU_ADB
            if (!shellOnly && !hello.optBoolean("qrtrLib")) log("The QRTR library didn't load (${hello.optString("libError").asClause()}), so QMI won't work.")
            showConnectStep(context.getString(R.string.modem_connect_checking))
            // Probing every vendor telephony service for a modem passthrough is slow on phones that have many.
            val device = DeviceInfo.from(helper.call("detect", 90.seconds))

            fun allowed(candidate: Backend) = device.allows(candidate, shellOnly)
            val suggestedBackend = device.suggested().takeIf(::allowed) ?: Backend.ANDROID
            val savedBackendName = prefs.getString("backend", null)
            val savedBackend = Backend.entries.firstOrNull { it.name == savedBackendName }
            val backend = savedBackend?.takeIf(::allowed) ?: suggestedBackend
            val halNeedsChannels = prefs.getBoolean(channelsKey(device), false)
            updateState {
                copy(
                    root = RootState.Granted,
                    access = access,
                    device = device,
                    autoBackend = suggestedBackend,
                    backend = backend,
                    halNeedsChannels = halNeedsChannels,
                )
            }
            val node =
                when {
                    shellOnly && device.mtkOemHook -> rilNode(sim = 1)
                    current.atNode.isNotEmpty() -> current.atNode
                    device.pixelRouter -> PIXEL_AT_NODE
                    else -> device.atNodes.firstOrNull { it.startsWith("/dev/radio") } ?: device.atNodes.firstOrNull() ?: ""
                }
            updateState { copy(atNode = node) }
            val accessSummary =
                when {
                    access == Access.ROOT -> "Root access granted."
                    access == Access.SHIZUKU_ROOT -> "Connected through Shizuku running as root."
                    device.mtkOemHook -> "Connected through Shizuku with ADB access. MediaTek's RIL passthrough is available, so band and cell locking work without root."
                    else -> "Connected through Shizuku with ADB access, so only the Android radio HAL features are available."
                }
            log("$accessSummary Using ${current.backend?.label?.let { englishContext.getString(it) }} (chipset: ${englishContext.getString(device.vendorLabel)}).")
            started.note?.let(::log)
            loadSims()
            refreshNow()
            onConnected()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = e.reasonText() ?: Text(R.string.modem_root_denied)
            log("Couldn't connect. ${inEnglish(why)}")
            updateState { copy(root = RootState.Denied(context.string(why))) }
        }
    }

    private fun showConnectStep(text: String) = updateState { copy(connectStep = text) }

    @SuppressLint("MissingPermission")
    fun loadSims() {
        val sims = ArrayList<SimInfo>()
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?.forEach { subscription ->
                    val name = subscription.displayName?.toString() ?: context.getString(R.string.modem_sim, subscription.simSlotIndex + 1)
                    sims += SimInfo(subscription.simSlotIndex, subscription.subscriptionId, name)
                }
            }
        }
        if (sims.isEmpty()) sims += SimInfo(0, SubscriptionManager.getDefaultSubscriptionId(), context.getString(R.string.modem_sim, 1))
        sims.sortBy { it.slot }
        val savedSim = prefs.getInt("sim", 1)
        updateState {
            val selected = listOf(savedSim, sim).firstOrNull { wanted -> sims.any { it.slot == wanted - 1 } } ?: (sims.first().slot + 1)
            copy(sims = sims, sim = selected)
        }
    }

    private fun subId(): Int =
        current.sims.firstOrNull { it.slot == current.sim - 1 }?.subId ?: SubscriptionManager.getDefaultSubscriptionId()

    fun setSim(sim: Int) {
        prefs.edit().putInt("sim", sim).apply()
        val atNode = if (current.atNode.startsWith(RIL_NODE_PREFIX)) rilNode(sim) else current.atNode
        updateState { copy(sim = sim, atNode = atNode) }
        refresh()
    }

    suspend fun setSimAndRefresh(sim: Int) {
        check(current.sims.any { it.slot == sim - 1 }) { "SIM is unavailable" }
        prefs.edit().putInt("sim", sim).apply()
        val atNode = if (current.atNode.startsWith(RIL_NODE_PREFIX)) rilNode(sim) else current.atNode
        updateState { copy(sim = sim, atNode = atNode) }
        refreshNow()
    }

    fun setBackend(backend: Backend) {
        if (!current.allows(backend)) return
        prefs.edit().putString("backend", backend.name).apply()
        updateState {
            copy(backend = backend, groups = emptyList(), lteLock = null, nrLock = null, legacyCellLock = null, strayChannel = null, carrierAggregation = null)
        }
        refresh()
    }

    suspend fun selectBackendAndRefresh(backend: Backend) {
        check(current.allows(backend)) { "Backend is unavailable with the current access" }
        prefs.edit().putString("backend", backend.name).apply()
        updateState { copy(backend = backend, groups = emptyList(), lteLock = null, nrLock = null, legacyCellLock = null, strayChannel = null, carrierAggregation = null) }
        refreshNow()
    }

    fun setPersist(enabled: Boolean) {
        prefs.edit().putBoolean("persist", enabled).apply()
        updateState { copy(persist = enabled) }
    }

    fun setAtNode(node: String) {
        prefs.edit().putString("atNode", node).apply()
        updateState { copy(atNode = node) }
    }

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        updateState { copy(log = (listOf("$time  $message") + log).take(MAX_LOG_LINES)) }
    }

    private suspend fun change(
        goal: Text,
        success: Text,
        block: suspend () -> Text?,
    ): String = logAndLocalize(attempt(goal) { block() ?: success })

    private suspend fun <T> attempt(
        goal: Text,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = Text(R.string.modem_couldnt, goal, reasonOf(e))
            throw ModemException(logAndLocalize(failure), failure)
        }

    private suspend fun report(message: Text) {
        _events.emit(logAndLocalize(message))
    }

    private fun logAndLocalize(text: Text): String {
        log(inEnglish(text))
        return context.string(text)
    }

    fun inEnglish(text: Text): String = englishContext.string(text)

    private fun englishReason(e: Throwable): String = inEnglish(reasonOf(e))

    private fun englishMessage(e: Throwable): String? {
        val text =
            when (e) {
                is Failure -> e.text
                is RootException -> e.text
                is ModemException -> e.text
                else -> null
            }
        return text?.let(::inEnglish) ?: e.message
    }

    /**
     * The AT port for MediaTek commands. The selected one is kept unless it can't serve: the Pixel port isn't a MediaTek
     * one, and ADB-level access can't open a tty. In that case the helper looks for a port that answers.
     */
    private suspend fun mtkNode(): String {
        val selected = current.atNode
        if (selected.startsWith(RIL_NODE_PREFIX)) return rilNode(current.sim)
        if (selected.isNotEmpty() && selected != PIXEL_AT_NODE && !current.limited) return selected
        // The helper answers JSON null when no port replied, and optString turns that into "null".
        val found = helper.call("mtk.findNode") { put("sim", current.sim) }.optString("node")
        if (found.isEmpty() || found == "null") fail(R.string.modem_no_mtk_port)
        setAtNode(found)
        return found
    }

    /**
     * A modem tty is shared with the phone's own radio software, which can swallow the reply. When a command fails
     * there and the RIL passthrough works, the command is redone through it and the passthrough stays selected.
     */
    private suspend fun callMediatek(
        command: String,
        build: JSONObject.() -> Unit = {},
    ): JSONObject {
        val node = mtkNode()
        return try {
            helper.call(command) {
                put("node", node)
                build()
            }
        } catch (e: RootException) {
            if (node.startsWith(RIL_NODE_PREFIX) || current.device?.mtkOemHook != true) throw e
            val ril = rilNode(current.sim)
            log("$node failed (${englishReason(e).asClause()}), so MediaTek commands go through the RIL from now on.")
            setAtNode(ril)
            helper.call(command) {
                put("node", ril)
                build()
            }
        }
    }

    private suspend fun restartRadio() = helper.call("radio.cycle", 30.seconds)

    /** Restarts only the selected SIM's radio so the other stays connected. Falls back to airplane mode. */
    private suspend fun restartSimRadio() {
        val result =
            try {
                helper.call("radio.cycleSim", 60.seconds) {
                    put("sim", current.sim)
                    put("subId", subId())
                }
            } catch (e: RootException) {
                log("Couldn't restart only SIM ${current.sim}'s radio (${englishReason(e).asClause()}), so airplane mode was toggled instead.")
                restartRadio()
                return
            }
        if (result.optBoolean("airplaneMode")) log("SIM ${current.sim}'s radio didn't restart on its own, so airplane mode was toggled instead.")
    }

    /**
     * [specs] is every lock bunnylock holds and is saved whole. A band lock is left out of the request while a cell lock
     * on its network stands in for it, so clearing the cell lock brings it back.
     */
    private suspend fun setChannels(specs: JSONArray) {
        sendLock(requestOf(specs.objects()))
        prefs.edit().putString(halSpecsKey(current.sim), specs.toString()).apply()
    }

    /** Some radios refuse the remaining locks when they're sent alone, so every band of the [freed] network goes along with them. */
    private suspend fun setChannelsFreeing(
        specs: JSONArray,
        freed: Int,
    ) {
        sendWithEveryBand(requestOf(specs.objects()), setOf(freed))
        prefs.edit().putString(halSpecsKey(current.sim), specs.toString()).apply()
    }

    private fun requestOf(held: List<JSONObject>): List<JSONObject> {
        val cellLockRans = held.filter { it.isCellLock }.map { it.optInt("ran") }
        return held.filter { it.isCellLock || it.optInt("ran") !in cellLockRans }
    }

    /** Some phones switch off every network a request leaves out. Once one does, its requests name all of them, unlocks too. */
    private suspend fun sendLock(request: List<JSONObject>) {
        if (current.device?.vendor != "qualcomm") return sendChannels(JSONArray(request))
        val leftOut = leftOutNetworks(request)
        if (clearsLeftOutNetworks) {
            sendWithEveryBand(request, leftOut)
            return
        }
        if (request.isEmpty()) return sendChannels(JSONArray())
        val before = reportedChannels()
        sendChannels(JSONArray(request))
        val after = reportedChannels() ?: return
        val switchedOff = leftOut.filter { bandsOn(before.orEmpty(), it).isNotEmpty() && bandsOn(after, it).isEmpty() }
        if (switchedOff.isEmpty()) return
        clearsLeftOutNetworks = true
        log("SIM ${current.sim} switched off the networks the lock left out, so bunnylock names every network in its locks from now on.")
        sendWithEveryBand(request, leftOut)
    }

    private fun leftOutNetworks(request: List<JSONObject>): Set<Int> {
        val hasNr = current.groups.any { it.group == Group.NR }
        val networks = setOfNotNull(Ran.GERAN, Ran.UTRAN, Ran.EUTRAN, Ran.NGRAN.takeIf { hasNr })
        return networks - request.map { it.optInt("ran") }.toSet()
    }

    private suspend fun sendChannels(
        specs: JSONArray,
        splitRefused: Boolean = true,
    ) {
        val result =
            helper.call("aosp.setChannels") {
                put("subId", subId())
                put("specs", specs)
                put("split", splitRefused)
            }
        result.optString("diagnostic").takeIf { it.isNotBlank() }?.let { log("HAL channel lock: $it") }
        if (!result.optBoolean("answered", true)) throw LockUnanswered()
    }

    /** Old Qualcomm phones that can't report their bands, where an unlock only brings back the saved lock. */
    private suspend fun keepsSavedBands(): Boolean = current.device?.vendor == "qualcomm" && reportedChannels() === null

    private suspend fun aospState(): JSONObject? = runSuspendCatching { helper.call("aosp.state") { put("subId", subId()) } }.getOrNull()

    private suspend fun reportedChannels(): List<JSONObject>? = aospState()?.optJSONArray("channels")?.objects()

    /** On phones that [keepsSavedBands], every band of the freed networks is sent first, so the unlock can't bring the old lock back. */
    private suspend fun replaceSavedBands(freedRans: Collection<Int>) {
        val legacy =
            listOfNotNull(
                channelSpec(Ran.GERAN, GERAN_RESTORE_BANDS, emptyList()).takeIf { Ran.GERAN in freedRans },
                channelSpec(Ran.UTRAN, UTRAN_RESTORE_BANDS, emptyList()).takeIf { Ran.UTRAN in freedRans },
            )
        if (Ran.EUTRAN !in freedRans && legacy.isEmpty()) return
        if (!prefs.contains(halSpecsKey(current.sim)) || !keepsSavedBands()) return
        if (Ran.EUTRAN in freedRans) replaceLteBands()
        if (legacy.isEmpty()) return
        try {
            sendChannels(JSONArray(legacy))
        } catch (e: RootException) {
            log("Sending every 2G and 3G band didn't work (${englishReason(e).asClause()}).")
        }
    }

    private suspend fun replaceLteBands() {
        val lte = supportedBands(Group.LTE) ?: return
        // One unknown band makes the modem reject the whole list, so shorter lists are tried next.
        val bandLists = listOf(lte, lte.filter { it <= NEWEST_OLD_HAL_LTE_BAND }, lte.filter { it in WIDELY_DEFINED_LTE_BANDS })
        for (bands in bandLists.distinct()) {
            val specs = bands.chunked(MAX_HAL_BANDS_PER_SPEC).map { channelSpec(Ran.EUTRAN, it, emptyList()) }
            try {
                sendChannels(JSONArray(specs))
                return
            } catch (e: RootException) {
                log("Sending ${bands.size} LTE bands didn't work (${englishReason(e).asClause()}).")
            }
        }
    }

    private fun supportedBands(group: Group): List<Int>? = current.groups.firstOrNull { it.group == group }?.supported

    private suspend fun sendWithEveryBand(
        named: List<JSONObject>,
        fill: Set<Int>,
    ): List<Int> {
        val lte = if (Ran.EUTRAN in fill) supportedBands(Group.LTE).orEmpty() else emptyList()
        val nr = if (Ran.NGRAN in fill) supportedBands(Group.NR).orEmpty() else emptyList()
        val bandLists =
            listOf(
                lte to nr,
                lte.filter { it <= NEWEST_OLD_HAL_LTE_BAND } to nr.filter { it in WIDELY_DEFINED_NR_BANDS },
                lte.filter { it in WIDELY_DEFINED_LTE_BANDS } to nr.filter { it in WIDELY_DEFINED_NR_BANDS },
                lte.filter { it in WIDELY_DEFINED_LTE_BANDS } to nr.filter { it in commonNrBands() },
            ).distinct()
        var refusal: RootException? = null
        // Newer phones take long entries, older ones 8 entries of 8 bands. One unknown band fails the whole request.
        for (bandsPerEntry in listOf(Int.MAX_VALUE, MAX_HAL_BANDS_PER_SPEC)) {
            for ((lteBands, nrBands) in bandLists) {
                val lteEntries = lteBands.chunked(bandsPerEntry)
                val specs = named.flatMap { splitLteEntry(it, bandsPerEntry) }.toMutableList()
                specs += lteEntries.map { channelSpec(Ran.EUTRAN, it, emptyList()) }
                if (nrBands.isNotEmpty()) specs += channelSpec(Ran.NGRAN, nrBands, emptyList())
                if (Ran.GERAN in fill) specs += channelSpec(Ran.GERAN, GERAN_RESTORE_BANDS, emptyList())
                if (Ran.UTRAN in fill) specs += channelSpec(Ran.UTRAN, UTRAN_RESTORE_BANDS, emptyList())
                try {
                    // Splitting would make several 5G entries, and only the last one counts.
                    sendChannels(JSONArray(specs), splitRefused = false)
                } catch (e: RootException) {
                    refusal = e
                    log("A request with ${specs.size} entries, ${lteBands.size} added LTE and ${nrBands.size} added 5G bands didn't work (${englishReason(e).asClause()}).")
                    continue
                }
                if (named.isEmpty() && lteEntries.size > 1 && keptOnlyLastEntry(lteEntries)) {
                    // Such a phone can't have every LTE band named, so a full unlock falls back to the plain one.
                    log("SIM ${current.sim} kept only the last of ${lteEntries.size} LTE entries, so bunnylock sent a plain unlock instead.")
                    sendChannels(JSONArray())
                    return emptyList()
                }
                return nrBands
            }
        }
        throw checkNotNull(refusal)
    }

    private suspend fun keptOnlyLastEntry(lteEntries: List<List<Int>>): Boolean {
        val lte = bandsOn(reportedChannels() ?: return false, Ran.EUTRAN)
        return lte.isNotEmpty() && lteEntries.last().containsAll(lte)
    }

    private fun splitLteEntry(
        spec: JSONObject,
        bandsPerEntry: Int,
    ): List<JSONObject> {
        val bands = spec.intList("bands").orEmpty()
        if (spec.optInt("ran") != Ran.EUTRAN || spec.isCellLock || bands.isEmpty()) return listOf(spec)
        return bands.chunked(bandsPerEntry).map { channelSpec(Ran.EUTRAN, it, emptyList()).put("bandLock", true) }
    }

    private var clearsLeftOutNetworks: Boolean
        get() = prefs.getBoolean(clearsLeftOutNetworksKey(), false)
        set(value) = prefs.edit().putBoolean(clearsLeftOutNetworksKey(), value).apply()

    private fun clearsLeftOutNetworksKey() = "halClearsLeftOut.${current.device?.manufacturer}.${current.device?.model}"

    private fun commonNrBands(): List<Int> =
        when (current.homePlmn?.take(3)?.toIntOrNull()) {
            in EUROPE_MCCS -> COMMON_NR_BANDS_EUROPE
            in NORTH_AMERICA_MCCS -> COMMON_NR_BANDS_NORTH_AMERICA
            else -> COMMON_NR_BANDS_ELSEWHERE
        }

    /** Returns a note for the user, or null. */
    private suspend fun finishHalUnlock(freed: List<JSONObject>): Text? {
        if (current.device?.vendor == "mediatek") {
            return try {
                restartSimRadio()
                Text(R.string.modem_note_radio_restarted)
            } catch (e: RootException) {
                Text(R.string.modem_note_radio_restart_failed, reasonOf(e))
            }
        }
        if (current.device?.vendor != "qualcomm" || clearsLeftOutNetworks) return null
        val freedRans = freed.map { it.optInt("ran") }.toSet()
        val state = aospState()
        val reported = state?.optJSONArray("channels")?.objects()
        if (reported === null) {
            if (Ran.NGRAN !in freedRans) return null
            return Text(R.string.modem_note_old_nr_bands, MAX_HAL_BANDS_PER_SPEC)
        }
        val remaining = requestOf(savedChannelSpecs())
        val lockedRans = remaining.map { it.optInt("ran") }.toSet()
        // A locked 2G or 3G network still reports its bands, so only the unlocked ones show whether the modem switched them off.
        val unlockedLegacy = listOf(Ran.GERAN, Ran.UTRAN) - lockedRans
        val lostLegacy = unlockedLegacy.isNotEmpty() && unlockedLegacy.all { bandsOn(reported, it).isEmpty() }
        val lostLte = bandsOn(reported, Ran.EUTRAN).isEmpty()
        val lostNr = has5g(state) && bandsOn(reported, Ran.NGRAN).isEmpty()
        val keptRans = freedRans.filter { keptOldBands(freed, reported, it) }.toSet()
        if (!lostLegacy && !lostLte && !lostNr && keptRans.isEmpty()) return null

        log("SIM ${current.sim} still had a lock or switched-off networks after the unlock, so every band was named again.")
        if (lostLegacy) clearsLeftOutNetworks = true
        val fill =
            if (clearsLeftOutNetworks) {
                leftOutNetworks(remaining)
            } else {
                buildSet {
                    add(Ran.EUTRAN)
                    if (lostNr) add(Ran.NGRAN)
                    addAll(keptRans)
                    removeAll(lockedRans)
                }
            }
        val nrBands =
            try {
                sendWithEveryBand(remaining, fill)
            } catch (e: RootException) {
                return Text(R.string.modem_note_kept_lock_refused)
            }
        // On newer phones a plain unlock brings back every band.
        if (!clearsLeftOutNetworks && remaining.isEmpty()) {
            try {
                sendChannels(JSONArray())
            } catch (e: RootException) {
                log("The unlock after naming every band didn't go through (${englishReason(e).asClause()}).")
            }
        }
        val notes = mutableListOf(Text(R.string.modem_note_kept_lock_renamed))
        if (nrBands.size in 1..MAX_HAL_BANDS_PER_SPEC) notes += Text(R.string.modem_note_nr_bands_limit, MAX_HAL_BANDS_PER_SPEC, nrBands.joinToString { "n$it" })
        if (lostLegacy) notes += Text(R.string.modem_note_legacy_back)
        return notes.reduce(Text::followedBy)
    }

    private fun keptOldBands(
        freed: List<JSONObject>,
        reported: List<JSONObject>,
        ran: Int,
    ): Boolean {
        val locked = bandsOn(freed, ran)
        val now = bandsOn(reported, ran)
        return locked.isNotEmpty() && now.isNotEmpty() && locked.containsAll(now)
    }

    private fun bandsOn(
        specs: List<JSONObject>,
        ran: Int,
    ): List<Int> = specs.filter { it.optInt("ran") == ran }.flatMap { it.intList("bands").orEmpty() }

    private fun channelSpec(
        ran: Int,
        bands: List<Int>,
        channels: List<Long>,
    ): JSONObject = JSONObject().put("ran", ran).put("bands", JSONArray(bands)).put("channels", JSONArray(channels))

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun JSONObject.intList(key: String): List<Int>? = optJSONArray(key)?.toIntList()

    /** A band lock pins channels too on phones that need them, so [bandSpecs] marks its entries to tell them from a cell lock. */
    private val JSONObject.isCellLock get() = !optBoolean("bandLock") && !intList("channels").isNullOrEmpty()

    private fun ranOf(group: Group) =
        when (group) {
            Group.GSM -> Ran.GERAN
            Group.WCDMA -> Ran.UTRAN
            Group.LTE -> Ran.EUTRAN
            Group.NR, Group.NR_SA, Group.NR_NSA -> Ran.NGRAN
        }

    private fun ratOf(group: Group) =
        when (group) {
            Group.GSM -> Rat.GSM
            Group.WCDMA -> Rat.WCDMA
            Group.LTE -> Rat.LTE
            Group.NR, Group.NR_SA, Group.NR_NSA -> Rat.NR
        }

    private val Group.isLegacy get() = this == Group.GSM || this == Group.WCDMA

    /** bunnylock names GSM bands by MHz, the radio HAL by GeranBand number. */
    private fun halBandsOf(
        group: Group,
        bands: List<Int>,
    ): List<Int> = if (group == Group.GSM) bands.flatMap { GERAN_BANDS[it].orEmpty() } else bands

    private fun gsmBandsOf(geranBands: List<Int>): List<Int> = GERAN_BANDS.filterValues { bands -> bands.any { it in geranBands } }.keys.toList()

    /** A GSM channel from 512 to 810 may be 1800 or 1900, so its cell has no band of its own. */
    private fun Cell.isOnAnyOf(bands: List<Int>): Boolean = if (band !== null) band in bands else bandsAt(this).any { it in bands }

    private fun bandsAt(cell: Cell): List<Int> {
        val channel = cell.arfcn.toInt()
        return when (cell.rat) {
            Rat.GSM -> Bands.gsmBandsAt(channel)
            Rat.WCDMA -> Bands.wcdmaBandsAt(channel)
            Rat.LTE -> Bands.lteBandsAt(channel)
            Rat.NR -> Bands.nrBandsOf(channel)
        }
    }

    /**
     * [supportedBands] falls back to the standard list for [group] when the modem doesn't report its own.
     * [currentBands] is null when no restriction is known.
     */
    private fun bandGroupState(
        group: Group,
        supportedBands: List<Int>?,
        currentBands: List<Int>?,
    ): BandGroupState {
        val knownBands = supportedBands?.takeIf { it.isNotEmpty() } ?: group.defaults()
        val supported = (knownBands + currentBands.orEmpty()).distinct().sorted()
        val current = currentBands?.toSet()
        return BandGroupState(group, supported, current)
    }

    private fun has5g(aospState: JSONObject?): Boolean = supportsNetwork(aospState, Masks.G5)

    private fun supportsNetwork(
        aospState: JSONObject?,
        mask: Long,
    ): Boolean {
        val supported = aospState?.optLong("supportedTypes") ?: 0L
        val unknown = supported and Masks.G4 == 0L
        return unknown || supported and mask != 0L
    }

    fun refresh() =
        scope.launch {
            if (current.root == RootState.Granted && !helper.running) reconnect() else refreshNow()
        }

    suspend fun refreshNow() {
        lastRefreshFailure = null
        if (!helper.running) return
        updateState { copy(refreshing = true) }
        try {
            // Subscription ids change when a SIM is swapped or switched off and on.
            loadSims()
            val sim = current.sim
            val subscriptionId = subId()
            coroutineScope {
                val androidCells = async { Cells.scan(context, subscriptionId) }
                val aospState = runSuspendCatching { helper.call("aosp.state") { put("subId", subscriptionId) } }.getOrNull()
                val ims = loadIms(subscriptionId)
                val modem =
                    when (current.backend) {
                        Backend.QUALCOMM -> loadQualcomm(sim, aospState)
                        Backend.MEDIATEK -> loadMediatek(sim, aospState)
                        Backend.PIXEL -> loadPixel(sim, aospState)
                        Backend.ANDROID, null -> loadHal(sim, aospState)
                    }
                val plmnLock = if (modem.plmnReported) modem.plmnLock else halPlmnLock(aospState)
                val telephony =
                    context
                        .getSystemService(TelephonyManager::class.java)
                        .let { if (subscriptionId >= 0) it.createForSubscriptionId(subscriptionId) else it }
                backendCells = modem.cells
                backendCarriers = modem.carriers
                val cells = Cells.merge(modem.cells, androidCells.await())
                val signals = Carriers.signals(context, subscriptionId)
                val carriers = connectionCarriers(sim, cells, signals)
                val groups =
                    when (current.backend) {
                        Backend.ANDROID, null -> withStrayBands(modem.groups, modem.lteLock, modem.nrLock, cells, carriers)
                        else -> modem.groups
                    }
                val strayChannel =
                    when (current.backend) {
                        Backend.QUALCOMM -> null
                        Backend.MEDIATEK -> if (modem.mtkCellLockRefused) strayChannel(cells, channelSpecs(aospState, sim)) else null
                        Backend.PIXEL, Backend.ANDROID, null -> strayChannel(cells, channelSpecs(aospState, sim))
                    }
                updateState {
                    copy(
                        groups = groups,
                        strayChannel = strayChannel,
                        mtkCellLockRefused = modem.mtkCellLockRefused,
                        nrMode = modem.nrMode,
                        lteLock = modem.lteLock,
                        nrLock = modem.nrLock,
                        legacyCellLock = modem.legacyCellLock,
                        plmnLock = plmnLock,
                        registeredPlmn = telephony.networkOperator?.takeIf { it.length >= 5 },
                        registeredName = telephony.networkOperatorName?.takeIf { it.isNotBlank() },
                        homePlmn = telephony.simOperator?.takeIf { it.length >= 5 },
                        homeName = telephony.simOperatorName?.takeIf { it.isNotBlank() },
                        cells = cells,
                        carriers = carriers,
                        signals = signals,
                        allowedMask = modem.allowedMask ?: aospState?.takeIf { it.has("allowed") }?.optLong("allowed"),
                        supportedTypes = aospState?.takeIf { it.has("supportedTypes") }?.optLong("supportedTypes"),
                        netModeHeldBack = aospState?.optText("modeHeldBack")?.let { context.string(it) },
                        netModeMasks = aospState?.optString("modeMasks")?.takeIf { it.isNotEmpty() },
                        nrDc = aospState?.takeIf { it.has("nrDc") }?.optBoolean("nrDc"),
                        ims = ims,
                        carrierAggregation = modem.carrierAggregation,
                    )
                }
                onRefreshed(current)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = Text(R.string.modem_couldnt, Text(R.string.modem_goal_refresh), reasonOf(e))
            lastRefreshFailure = context.string(error)
            report(error)
        } finally {
            updateState { copy(refreshing = false) }
        }
    }

    private suspend fun loadIms(subscriptionId: Int): Map<ImsFeature, ImsSetting> =
        runSuspendCatching { helper.call("aosp.ims") { put("subId", subscriptionId) } }.getOrNull()?.let(::imsSettingsFrom).orEmpty()

    suspend fun refreshIms() {
        if (!helper.running) return
        val subscriptionId = subId()
        val ims = loadIms(subscriptionId)
        if (subId() == subscriptionId) updateState { copy(ims = ims) }
    }

    private suspend fun connectionCarriers(
        sim: Int,
        cells: List<Cell>,
        signals: List<Signal>,
    ): List<Carrier> {
        physicalChannelReport = physicalChannels(sim)
        val reported = Carriers.fromReport(physicalChannelReport)
        val carriers =
            Rat.entries.flatMap { rat ->
                val android = reported.filter { it.rat == rat }
                val modem = backendCarriers.filter { it.rat == rat }
                // Some radio HALs leave out the channel and PCI on some carriers. For those networks the modem's own list is used instead.
                val identified = android.isNotEmpty() && android.all { it.arfcn !== null || it.pci !== null }
                if (identified || modem.isEmpty()) android else modem
            }
        return Carriers.complete(carriers, cells, signals)
    }

    suspend fun refreshConnection() {
        if (!helper.running || current.refreshing || current.root != RootState.Granted) return
        val sim = current.sim
        val subscriptionId = subId()
        if (current.backend == Backend.QUALCOMM) {
            runSuspendCatching { helper.call("qc.serving") { put("sim", sim) } }.getOrNull()?.let { serving ->
                qualcommServingReport = serving
                backendCells = runCatching { Cells.fromQualcomm(serving) }.getOrDefault(backendCells)
                backendCarriers = Carriers.fromReport(serving.optJSONArray("carriers"))
            }
        }
        val cells = Cells.merge(backendCells, Cells.scan(context, subscriptionId, LIVE_CELL_SCAN_WAIT))
        val signals = Carriers.signals(context, subscriptionId)
        val carriers = connectionCarriers(sim, cells, signals)
        if (current.refreshing || current.sim != sim) return
        val halBackend = current.backend == Backend.ANDROID || current.backend === null
        updateState {
            val liveGroups = if (halBackend && !settling) withStrayBands(groups, lteLock, nrLock, cells, carriers) else groups
            copy(cells = cells, carriers = carriers, signals = signals, groups = liveGroups)
        }
    }

    private fun withStrayBands(
        groups: List<BandGroupState>,
        lteLock: String?,
        nrLock: String?,
        cells: List<Cell>,
        carriers: List<Carrier>,
    ): List<BandGroupState> =
        groups.map { groupState ->
            // A HAL band lock stands by while a cell lock holds its network, and that cell may be on another band.
            val cellLock =
                when (groupState.group) {
                    Group.LTE -> lteLock
                    Group.NR -> nrLock
                    else -> null
                }
            val standingBy = cellLock !== null
            val stray =
                if (groupState.locked && !standingBy) strayBand(cells, groupState.group, groupState.current.orEmpty(), carriers) else null
            groupState.copy(stray = stray)
        }

    private suspend fun loadSamsungNrMode(sim: Int): NrMode? {
        if (current.device?.samsungNrMode != true) return null
        // 3 and 4 are temporary IMS states, and -1 is a failed read.
        val mode =
            runSuspendCatching {
                helper
                    .call("samsung.nrMode") {
                        put("sim", sim)
                        put("dataSubId", SubscriptionManager.getDefaultDataSubscriptionId())
                    }.optInt("mode", -1)
            }.getOrNull()
        return mode?.let { NrMode.entries.getOrNull(it) }
    }

    private suspend fun setSamsungNrMode(mode: NrMode) {
        helper.call("samsung.setNrMode") {
            put("sim", current.sim)
            put("dataSubId", SubscriptionManager.getDefaultDataSubscriptionId())
            put("mode", mode.ordinal)
        }
    }

    private suspend fun loadQualcomm(
        sim: Int,
        aospState: JSONObject?,
    ): BackendReadout {
        val qcState = helper.call("qc.state") { put("sim", sim) }
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
        val samsungNrMode = if (sim in samsungNrModeReadbackSims) loadSamsungNrMode(sim) else null
        val serving = runSuspendCatching { helper.call("qc.serving") { put("sim", sim) } }.getOrNull()
        qualcommServingReport = serving
        val manualSelection = qcState.optJSONObject("netSel")?.takeIf { it.optInt("mode") == QMI_NET_SEL_MANUAL }
        return BackendReadout(
            groups = groups,
            nrMode = samsungNrMode ?: qcNrMode(qcState.optInt("nrMode", -1)),
            lteLock = qcLteLock(qcState.optJSONObject("lteLock"), sim),
            nrLock = qcState.optJSONObject("nrLock")?.takeIf { it.optBoolean("active") }?.let(::qcNrLockLabel),
            plmnLock = manualSelection?.let { qcPlmnLabel(it, qcState.optJSONObject("manualPlmn"), sim) },
            plmnReported = qcState.has("netSel"),
            cells = serving?.let { runCatching { Cells.fromQualcomm(it) }.getOrNull() }.orEmpty(),
            carriers = Carriers.fromReport(serving?.optJSONArray("carriers")),
        )
    }

    private fun qcNrMode(code: Int): NrMode? =
        when (code) {
            0 -> NrMode.BOTH
            1 -> NrMode.NSA
            2 -> NrMode.SA
            else -> null
        }

    private fun qcLteLock(
        reported: JSONObject?,
        sim: Int,
    ): String? {
        val saved = prefs.getString(qcLteLockKey(sim), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (reported === null) return saved?.let { context.getString(R.string.modem_lock_unconfirmed, qcLteLockLabel(it)) }
        if (!reported.optBoolean("active")) {
            prefs.edit().remove(qcLteLockKey(sim)).apply()
            return null
        }
        val setEarfcn = if (reported.optBoolean("earfcnTruncated")) saved?.optJSONArray("cells")?.optJSONObject(0)?.optLong("earfcn") else null
        return qcLteLockLabel(reported, setEarfcn)
    }

    private fun qcLteLockKey(sim: Int) = "qcLteLock.sim$sim"

    private fun qcLteLockLabel(
        lock: JSONObject,
        setEarfcn: Long? = null,
    ): String {
        val cellsByEarfcn = lock.getJSONArray("cells").objects().groupBy { it.optLong("earfcn") }
        return cellsByEarfcn.entries.joinToString(context.getString(R.string.modem_lock_separator)) { (reported, cells) ->
            val earfcn = setEarfcn?.takeIf { it and QC_REPORTED_EARFCN_BITS == reported } ?: reported
            context.getString(R.string.modem_lock_channel_pci, "EARFCN", earfcn.toString(), context.orList(cells.map { it.optInt("pci").toString() }))
        }
    }

    private fun qcNrLockLabel(lock: JSONObject): String =
        when (val type = lock.optInt("type")) {
            QMI_NR_LOCK_PCI -> {
                context.getString(R.string.modem_lock_nr_pci, lock.optLong("arfcn"), lock.optInt("pci"), lock.optInt("band"), lock.optInt("scs"))
            }

            QMI_NR_LOCK_FREQUENCY -> {
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

            QMI_NR_LOCK_SEVERAL_PCIS -> {
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

    /** Some firmware only says that selection is manual, not for which network. The one bunnylock last set is shown then. */
    private fun qcPlmnLabel(
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

    private suspend fun loadMediatek(
        sim: Int,
        aospState: JSONObject?,
    ): BackendReadout {
        val mtkState = callMediatek("mtk.state")
        val supported = mtkState.optJSONObject("supported") ?: JSONObject()
        val current = mtkState.optJSONObject("current") ?: JSONObject()
        val groups = mutableListOf(bandGroupState(Group.LTE, supported.intList("lte"), current.intList("lte")))
        val supportedNr = supported.intList("nr")
        if (supportedNr !== null && (supportedNr.isNotEmpty() || has5g(aospState))) {
            groups += bandGroupState(Group.NR, supportedNr, current.intList("nr"))
        }
        groups += bandGroupState(Group.WCDMA, supported.intList("wcdma"), current.intList("wcdma"))
        groups += bandGroupState(Group.GSM, supported.intList("gsm"), current.intList("gsm"))
        // While Android holds part of the mode back, its mask stays, so the note can explain the difference.
        val allowedMask = mtkState.takeIf { it.has("ratMask") && aospState?.has("modeHeldBack") != true }?.optLong("ratMask")
        val carrierAggregation = mtkState.takeIf { it.has("ca") }?.optBoolean("ca")
        // A modem that refuses AT+EMMCHLCK gets its cell lock through the radio HAL, so the lock is read from there too.
        val cellLockState = mtkState.optJSONObject("cellLock")
        if (cellLockState?.optBoolean("refused") == true || mtkCellLockIgnored) {
            val specs = channelSpecs(aospState, sim)
            return BackendReadout(
                groups = groups,
                lteLock = channelLock(specs, Ran.EUTRAN),
                nrLock = channelLock(specs, Ran.NGRAN),
                mtkCellLockRefused = true,
                allowedMask = allowedMask,
                carrierAggregation = carrierAggregation,
            )
        }
        val cellLock = cellLockState?.takeIf { it.optBoolean("active") }
        val lockLabel = cellLock?.let(::mtkCellLockLabel)
        // A reply too short to name the technology is shown as an LTE lock, the kind bunnylock sets most.
        val lockedAct = cellLock?.optInt("act", MTK_ACT_LTE)
        val nrLocked = lockedAct == MTK_ACT_NR
        val legacyLocked = lockedAct == MTK_ACT_GSM || lockedAct == MTK_ACT_UTRAN
        return BackendReadout(
            groups = groups,
            lteLock = lockLabel.takeIf { !nrLocked && !legacyLocked },
            nrLock = lockLabel.takeIf { nrLocked },
            legacyCellLock = lockLabel.takeIf { legacyLocked },
            allowedMask = allowedMask,
            carrierAggregation = carrierAggregation,
        )
    }

    private fun mtkCellLockLabel(lock: JSONObject): String {
        val pci = lock.optInt("pci", -1).takeIf { it >= 0 && it != MTK_PCI_UNSET }
        val channelName =
            when (lock.optInt("act")) {
                MTK_ACT_LTE -> "EARFCN"
                MTK_ACT_UTRAN -> "UARFCN"
                else -> "ARFCN"
            }
        val arfcn = lock.optInt("arfcn").toString()
        return if (pci === null) "$channelName $arfcn" else context.getString(R.string.modem_lock_channel_pci, channelName, arfcn, pci.toString())
    }

    private suspend fun loadPixel(
        sim: Int,
        aospState: JSONObject?,
    ): BackendReadout {
        val pixelState = helper.call("pixel.state") { put("sim", sim) }
        val specs = channelSpecs(aospState, sim)
        val lteBands = if (pixelState.optBoolean("lteLocked")) pixelState.intList("lte") else null
        val nrBands = if (pixelState.optBoolean("nrLocked")) pixelState.intList("nr") else null
        return BackendReadout(
            groups =
                listOf(
                    bandGroupState(Group.LTE, supportedBands = null, currentBands = lteBands),
                    bandGroupState(Group.NR, supportedBands = null, currentBands = nrBands),
                ),
            nrMode = pixelNrMode(pixelState.optString("nrMode")),
            lteLock = channelLock(specs, Ran.EUTRAN),
            nrLock = channelLock(specs, Ran.NGRAN),
        )
    }

    private fun pixelNrMode(value: String): NrMode? =
        when (value) {
            "11" -> NrMode.BOTH
            "01" -> NrMode.NSA
            "10" -> NrMode.SA
            else -> null
        }

    private suspend fun loadHal(
        sim: Int,
        aospState: JSONObject?,
    ): BackendReadout {
        val specs = channelSpecs(aospState, sim)
        val nrBands = channelBands(specs, Ran.NGRAN)
        val groups = mutableListOf(bandGroupState(Group.LTE, supportedBands = null, currentBands = channelBands(specs, Ran.EUTRAN)))
        if (has5g(aospState) || nrBands !== null) groups += bandGroupState(Group.NR, supportedBands = null, currentBands = nrBands)
        val wcdmaBands = channelBands(specs, Ran.UTRAN)
        if (supportsNetwork(aospState, Masks.G3) || wcdmaBands !== null) groups += bandGroupState(Group.WCDMA, supportedBands = HAL_WCDMA_BANDS, currentBands = wcdmaBands)
        val gsmBands = channelBands(specs, Ran.GERAN)?.let(::gsmBandsOf)
        if (supportsNetwork(aospState, Masks.G2) || gsmBands !== null) groups += bandGroupState(Group.GSM, supportedBands = null, currentBands = gsmBands)
        return BackendReadout(
            nrMode = loadSamsungNrMode(sim),
            groups = groups,
            lteLock = channelLock(specs, Ran.EUTRAN),
            nrLock = channelLock(specs, Ran.NGRAN),
        )
    }

    /**
     * What bunnylock last set is what counts as a lock. The list the radio HAL reports can't tell one apart:
     * Samsung's lists the first eight bands of each network while nothing is locked. Without a request of our own,
     * only reported entries that pin channels are taken as a lock.
     */
    private fun channelSpecs(
        aospState: JSONObject?,
        sim: Int,
    ): List<JSONObject> {
        val saved = prefs.getString(halSpecsKey(sim), null)
        if (saved !== null) return runCatching { JSONArray(saved).objects() }.getOrDefault(emptyList())
        return aospState?.optJSONArray("channels")?.objects()?.filter { !it.intList("channels").isNullOrEmpty() } ?: emptyList()
    }

    /** The locks bunnylock itself holds on the current SIM, never what the radio HAL reports. */
    private fun savedChannelSpecs(): List<JSONObject> = channelSpecs(aospState = null, sim = current.sim)

    /**
     * The HAL only says that Android took a lock, so a serving cell that no locked band can reach is the one sign the modem ignores it.
     * A 5G NSA cell never counts as registered, so a 5G carrier on another band is that sign too.
     */
    private fun strayBand(
        cells: List<Cell>,
        group: Group,
        lockedBands: Collection<Int>,
        carriers: List<Carrier> = emptyList(),
    ): Int? {
        val rat = ratOf(group)
        val strayCell =
            cells
                .filter { it.registered && it.rat == rat }
                .firstOrNull { cell ->
                    val coveringBands = bandsAt(cell)
                    coveringBands.isNotEmpty() && coveringBands.none { it in lockedBands }
                }
        if (strayCell !== null) return strayCell.band ?: bandsAt(strayCell).first()
        if (rat != Rat.NR) return null
        return carriers.filter { it.rat == Rat.NR }.firstNotNullOfOrNull { carrier ->
            val coveringBands = carrier.nrCoveringBands()
            if (coveringBands.isNotEmpty() && coveringBands.none { it in lockedBands }) carrier.band ?: coveringBands.first() else null
        }
    }

    private fun Carrier.nrCoveringBands(): List<Int> = arfcn?.let { Bands.nrBandsOf(it.toInt()) }.orEmpty().ifEmpty { listOfNotNull(band) }

    private suspend fun strayAfterLock(
        cells: List<Cell>,
        group: Group,
        bands: List<Int>,
    ): Int? {
        val strayCell = strayBand(cells, group, bands)
        if (strayCell !== null || ratOf(group) != Rat.NR) return strayCell
        return strayBand(cells, group, bands, connectionCarriers(current.sim, cells, Carriers.signals(context, subId())))
    }

    private suspend fun physicalChannels(sim: Int): JSONArray? =
        runSuspendCatching { helper.call("aosp.carriers") { put("sim", sim) }.optJSONArray("carriers") }.getOrNull()

    private fun channelBands(
        specs: List<JSONObject>,
        ran: Int,
    ): List<Int>? = specs.lastOrNull { it.optInt("ran") == ran && !it.isCellLock }?.intList("bands")?.takeIf { it.isNotEmpty() }

    private fun lockedChannels(
        specs: List<JSONObject>,
        ran: Int,
    ): List<Int>? = specs.lastOrNull { it.optInt("ran") == ran && it.isCellLock }?.intList("channels")

    private fun channelName(ran: Int) = if (ran == Ran.EUTRAN) "EARFCN" else "ARFCN"

    private fun channelLock(
        specs: List<JSONObject>,
        ran: Int,
    ): String? =
        lockedChannels(specs, ran)?.let {
            context.getString(R.string.modem_lock_channel_frequency_only, channelName(ran), context.orList(it.map(Int::toString)))
        }

    /** e.g. "EARFCN 75": the serving cell's channel when a HAL frequency lock excludes it, so the modem ignores that lock too. */
    private fun strayChannel(
        cells: List<Cell>,
        specs: List<JSONObject>,
    ): String? =
        listOf(Ran.EUTRAN to Rat.LTE, Ran.NGRAN to Rat.NR).firstNotNullOfOrNull { (ran, rat) ->
            val locked = lockedChannels(specs, ran) ?: return@firstNotNullOfOrNull null
            cells.firstOrNull { it.registered && it.rat == rat && it.arfcn.toInt() !in locked }?.let { "${channelName(ran)} ${it.arfcn}" }
        }

    fun stopWaiting() {
        waitStopped.value = true
    }

    private suspend fun stoppedDuring(wait: Duration): Boolean = withTimeoutOrNull(wait) { waitStopped.first { it } } !== null

    private suspend fun settledCells(
        rechecks: Int = HAL_LOCK_RECHECKS,
        hasStray: suspend (List<Cell>) -> Boolean,
    ): List<Cell>? {
        waitStopped.value = false
        updateState { copy(settling = true) }
        try {
            val cells = settledCellsAfterRestart(rechecks, hasStray)
            when {
                waitStopped.value -> {
                    log("Waiting for SIM ${current.sim} after the lock was stopped, so the lock stays unchecked.")
                    return null
                }

                cells === null -> log("SIM ${current.sim} sent no new cell list after the lock.")
            }
            return cells
        } finally {
            updateState { copy(settling = false) }
        }
    }

    /**
     * A MediaTek modem takes a HAL lock right away but may only move to it once the radio has restarted, so it gets the restart after the usual wait.
     */
    private suspend fun settledCellsAfterRestart(
        rechecks: Int,
        hasStray: suspend (List<Cell>) -> Boolean,
    ): List<Cell>? {
        val mediatek = current.device?.vendor == "mediatek"
        val cells = scanUntilSettled(if (mediatek) HAL_LOCK_RECHECKS else rechecks, hasStray)
        if (waitStopped.value || !mediatek || (cells !== null && !hasStray(cells))) return cells
        log("The phone didn't move to the locked bands, so SIM ${current.sim}'s radio is restarted to make it move.")
        runSuspendCatching { restartSimRadio() }
        return scanUntilSettled(rechecks, hasStray) ?: cells
    }

    /** A modem that follows a HAL lock leaves the old cell within seconds, so the wait only grows while [hasStray] still finds one. */
    private suspend fun scanUntilSettled(
        rechecks: Int,
        hasStray: suspend (List<Cell>) -> Boolean,
    ): List<Cell>? {
        if (stoppedDuring(HAL_LOCK_SETTLE)) return null
        var cells = Cells.freshScan(context, subId())
        var missedScans = if (cells === null) 1 else 0
        repeat(rechecks) {
            if (cells !== null && !hasStray(cells)) return cells
            if (missedScans == MAX_MISSED_SCANS) return cells
            if (stoppedDuring(HAL_LOCK_RECHECK)) return null
            val scanned = Cells.freshScan(context, subId())
            if (scanned === null) {
                missedScans++
            } else {
                missedScans = 0
                cells = scanned
            }
        }
        return cells
    }

    private fun halPlmnLock(aospState: JSONObject?): String? {
        if (aospState?.optInt("selectionMode") != TelephonyManager.NETWORK_SELECTION_MODE_MANUAL) return null
        val manualPlmn = aospState.optString("manualPlmn")
        return if (manualPlmn.isEmpty()) context.getString(R.string.modem_plmn_manual) else formatPlmn(manualPlmn)
    }

    /** With [exclusive], every network without a band lock of its own is turned off, and unlocking turns networks back on. */
    suspend fun lockBands(
        group: Group,
        bands: Collection<Int>,
        exclusive: Boolean = false,
    ): String = applyGroup(group, unlock = false, exclusive, bands)

    suspend fun unlockBands(
        group: Group,
        exclusive: Boolean = false,
    ): String = applyGroup(group, unlock = true, exclusive)

    /** Takes [group]'s network out of the network mode. */
    suspend fun turnOff(group: Group): String =
        change(Text(R.string.modem_goal_network_off, group.label), Text(R.string.modem_network_off, group.label)) {
            switchNetworks(current.netModeMask and group.generation.inv()).also { resendHalLocks() }
        }

    /** Samsung's RIL stops following radio HAL locks once the network mode changes. */
    private suspend fun resendHalLocks() {
        if (current.backend != Backend.ANDROID && current.backend !== null) return
        val held = savedChannelSpecs()
        if (held.isNotEmpty()) setChannels(JSONArray(held))
    }

    private suspend fun applyGroup(
        group: Group,
        unlock: Boolean,
        exclusive: Boolean,
        selected: Collection<Int> = emptyList(),
    ) = change(
        Text(if (unlock) R.string.modem_goal_unlock_bands else R.string.modem_goal_lock_bands, group.label),
        bandsDone(group, unlock),
    ) {
        val groupState = current.groups.first { it.group == group }
        val bands = if (unlock) groupState.supported else selected.distinct().sorted()
        if (bands.isEmpty()) fail(R.string.modem_pick_band)
        if (exclusive) applyExclusive(group, groupState, bands, unlock) else sendBands(group, groupState, bands, unlock)
    }

    /** Changes the network mode before the bands, since a lock sent before the change is ignored (see [resendHalLocks]). */
    private suspend fun applyExclusive(
        group: Group,
        groupState: BandGroupState,
        bands: List<Int>,
        unlock: Boolean,
    ): Text? {
        val othersLocked = current.groups.any { it.group != group && it.locked }
        val mask =
            when {
                !unlock -> current.maskKeepingLocks(group)
                othersLocked -> current.netModeMask or group.generation
                else -> maskBeforeLock ?: (current.netModeMask or group.generation)
            }
        // A network that's only turned off has no lock to lift.
        val onlyTurnsOn = unlock && !groupState.locked && current.isOff(group)
        val before = current.netModeMask
        val switched = switchNetworks(mask)
        val sent =
            if (onlyTurnsOn) {
                null
            } else {
                runSuspendCatching { sendBands(group, groupState, bands, unlock) }
                    .onFailure { runSuspendCatching { switchNetworks(before) } }
                    .getOrThrow() ?: bandsDone(group, unlock)
            }
        if (unlock && !othersLocked) maskBeforeLock = null
        return listOfNotNull(sent, switched).reduceOrNull(Text::followedBy)
    }

    /** Sets the network mode to [mask] and says which networks that turned off or on. */
    private suspend fun switchNetworks(mask: Long): Text? {
        val before = current.netModeMask
        val turnedOff = current.networkNames(before and mask.inv())
        val turnedOn = current.networkNames(mask and before.inv())
        if (turnedOff.isEmpty() && turnedOn.isEmpty()) return null
        if (turnedOff.isNotEmpty() && maskBeforeLock === null) maskBeforeLock = before
        val changes =
            listOfNotNull(
                turnedOff.takeIf { it.isNotEmpty() }?.let { Text(R.string.modem_network_off, it) },
                turnedOn.takeIf { it.isNotEmpty() }?.let { Text(R.string.modem_network_back_on, it) },
            ).reduce(Text::followedBy)
        return setAllowedMask(mask, changes) ?: changes
    }

    /** Brings back the network mode from before a lock turned networks off. */
    private suspend fun restoreNetworks(): Text? {
        val mask = maskBeforeLock ?: return null
        return switchNetworks(mask).also { maskBeforeLock = null }
    }

    private var maskBeforeLock: Long?
        get() = prefs.getLong(maskBeforeLockKey(), -1L).takeIf { it >= 0 }
        set(value) {
            val editor = prefs.edit()
            if (value === null) editor.remove(maskBeforeLockKey()) else editor.putLong(maskBeforeLockKey(), value)
            editor.apply()
        }

    private fun maskBeforeLockKey() = "maskBeforeLock.sim${current.sim}"

    private suspend fun sendBands(
        group: Group,
        groupState: BandGroupState,
        bands: List<Int>,
        unlock: Boolean,
    ): Text? {
        val allBandsSelected = bands.containsAll(groupState.supported)
        // An empty list lifts the restriction on every backend.
        val bandsToSend = if (allBandsSelected) emptyList() else bands
        val bandsJson = JSONArray(bandsToSend)
        return when (current.backend) {
            Backend.QUALCOMM -> {
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
                    helper.call("qc.setBands") {
                        put("sim", current.sim)
                        put("kind", kind)
                        put("bands", bandsJson)
                        put("persist", current.persist)
                    }
                result.optTexts("notes").firstOrNull()
            }

            Backend.MEDIATEK -> {
                val key =
                    when (group) {
                        Group.LTE -> "lte"
                        Group.WCDMA -> "wcdma"
                        Group.GSM -> "gsm"
                        else -> "nr"
                    }
                callMediatek("mtk.setBands") { put(key, bandsJson) }
                null
            }

            Backend.PIXEL -> {
                helper.call(if (group == Group.LTE) "pixel.setLte" else "pixel.setNr") { put("bands", bandsJson) }
                restartRadio()
                bandsDone(group, unlock).followedBy(Text(R.string.modem_note_radio_restarted))
            }

            Backend.ANDROID, null -> {
                setBandsThroughHal(group, bands, allBandsSelected)
            }
        }
    }

    private fun bandsDone(
        group: Group,
        unlock: Boolean,
    ) = Text(if (unlock) R.string.modem_bands_unlocked else R.string.modem_bands_locked, group.label)

    /**
     * Locks [bands] through the radio HAL, or lifts the band lock when [allBandsSelected]. Returns what the user should
     * know beyond plain success: that the lock needed a workaround, or that the phone doesn't follow it.
     */
    private suspend fun setBandsThroughHal(
        group: Group,
        bands: List<Int>,
        allBandsSelected: Boolean,
    ): Text? {
        // Old Qualcomm phones keep only part of a longer 5G list.
        val nrListTooLong = group == Group.NR && !allBandsSelected && bands.size > MAX_HAL_BANDS_PER_SPEC && keepsSavedBands()
        if (nrListTooLong) fail(R.string.modem_nr_list_too_long, MAX_HAL_BANDS_PER_SPEC)
        val override = mapOf(group to if (allBandsSelected) emptyList() else bands)
        val freed = if (allBandsSelected) savedChannelSpecs().filter { it.optInt("ran") == ranOf(group) && !it.isCellLock } else emptyList()
        if (allBandsSelected) replaceSavedBands(listOf(ranOf(group)))
        val noMatchingCell = !allBandsSelected && !group.isLegacy && visibleChannels(ratOf(group), bands).isEmpty()
        val servingCellKnown = current.cells.any { it.registered }
        if (noMatchingCell && !servingCellKnown && needsChannels) fail(R.string.modem_needs_visible_cell, group.label)
        val onMatchingNrCarrier =
            ratOf(group) == Rat.NR && current.carriers.any { it.rat == Rat.NR && it.nrCoveringBands().any { band -> band in bands } }
        val blind = noMatchingCell && servingCellKnown && !onMatchingNrCarrier
        if (blind) log("HAL band-lock diagnostic: ${bandLockProbe(group, bands)}; no matching channel is visible, so the lock goes out with bands only.")
        val savedBefore = prefs.getString(halSpecsKey(current.sim), null)
        val bandsOnlySpecs = bandSpecs(override)
        val bandsOnlyResult = runSuspendCatching { setChannels(bandsOnlySpecs) }
        val failure = bandsOnlyResult.exceptionOrNull()
        val error = failure?.let { englishMessage(it)?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
        var note: Text? = null
        when {
            failure === null -> {}

            failure is LockUnanswered -> throw failure

            blind -> {
                log("HAL band-lock diagnostic: request=$bandsOnlySpecs; error=$error; retry unavailable.")
                if (needsChannels) fail(R.string.modem_needs_visible_cell, group.label)
                throw failure
            }

            !allBandsSelected && !needsChannels && !group.isLegacy -> {
                val retry = if (noMatchingCell) "unavailable" else "band+channel"
                log("HAL band-lock diagnostic: request=$bandsOnlySpecs; error=$error; ${bandLockProbe(group, bands)}; retry=$retry.")
                if (noMatchingCell) fail(R.string.modem_needs_visible_cell, group.label)
                setChannels(bandSpecs(override, withChannels = true))
                needsChannels = true
                note = Text(R.string.modem_note_needs_channels)
            }

            group == Group.GSM && bands == listOf(1800) -> {
                log("HAL band-lock diagnostic: request=$bandsOnlySpecs; error=$error.")
                fail(R.string.modem_no_gsm_1800)
            }

            allBandsSelected && bandsOnlySpecs.length() > 0 -> {
                log("HAL band-unlock diagnostic: request=$bandsOnlySpecs; error=$error; retry=every ${group.label} band named.")
                setChannelsFreeing(bandsOnlySpecs, ranOf(group))
            }

            else -> throw failure
        }
        if (allBandsSelected) return finishHalUnlock(freed)?.let { bandsDone(group, unlock = true).followedBy(it) }
        if (blind) {
            report(Text(R.string.modem_blind_lock_searching, group.label))
            val cells = settledCells(BLIND_LOCK_RECHECKS) { !onLteOrNr(it) || strayAfterLock(it, group, bands) !== null }
            if (cells === null) {
                log("Whether SIM ${current.sim} found a cell after the lock is unknown, so the lock stays.")
                return null
            }
            if (!onLteOrNr(cells)) revertBlindLock(group, savedBefore)
            return strayAfterLock(cells, group, bands)?.let { ignoredLockNote(group, it) }
        }
        val cells = settledCells { strayAfterLock(it, group, bands) !== null }.orEmpty()
        val registered = cells.filter { it.registered }
        // Some Shannon RILs read a bands-only lock as "no allowed channels", so the fall to 2G/3G is detected here.
        if (!group.isLegacy && !needsChannels && registered.isNotEmpty() && registered.none { it.rat == Rat.LTE || it.rat == Rat.NR }) {
            if (noMatchingCell) revertBlindLock(group, savedBefore)
            needsChannels = true
            setChannels(bandSpecs(override, withChannels = true))
            return Text(R.string.modem_note_fell_back)
        }
        return strayAfterLock(cells, group, bands)?.let { ignoredLockNote(group, it) } ?: note
    }

    private fun onLteOrNr(cells: List<Cell>) = cells.any { it.registered && (it.rat == Rat.LTE || it.rat == Rat.NR) }

    private suspend fun revertBlindLock(
        group: Group,
        savedBefore: String?,
    ): Nothing {
        log("The phone found no ${group.label} cell on the selected bands after the lock, so the previous bands are sent again.")
        val previous = savedBefore?.let { runCatching { JSONArray(it).objects() }.getOrNull() }.orEmpty()
        runSuspendCatching {
            if (previous.none { it.optInt("ran") == ranOf(group) && !it.isCellLock }) replaceSavedBands(listOf(ranOf(group)))
            sendLock(requestOf(previous))
        }.onFailure {
            log("Sending the previous bands again didn't work (${englishReason(it).asClause()}).")
            refreshNow()
            fail(R.string.modem_blind_lock_restore_failed)
        }
        val editor = prefs.edit()
        if (savedBefore === null) editor.remove(halSpecsKey(current.sim)) else editor.putString(halSpecsKey(current.sim), savedBefore)
        editor.apply()
        if (current.device?.vendor == "mediatek") runSuspendCatching { restartSimRadio() }
        fail(R.string.modem_blind_lock_reverted)
    }

    private fun ignoredLockNote(
        group: Group,
        stray: Int,
    ): Text {
        val note = Text(R.string.modem_note_band_lock_ignored, group.label, group.bandLabel(stray))
        return if (current.device?.samsungMenu == true) note.followedBy(Text(R.string.modem_note_samsung_menu_hint)) else note
    }

    /** Set once this phone model refused a bands-only lock or dropped to 2G/3G under one. Its RIL then needs channels too. */
    private var needsChannels: Boolean
        get() = prefs.getBoolean(channelsKey(), false)
        set(value) {
            prefs.edit().putBoolean(channelsKey(), value).apply()
            updateState { copy(halNeedsChannels = value) }
        }

    fun forgetNeedsChannels() {
        needsChannels = false
        log("bunnylock forgot that this phone needs visible frequencies in band locks, so it tries bands alone first again.")
    }

    private fun channelsKey(device: DeviceInfo? = current.device) = "halChannels.${device?.manufacturer}.${device?.model}"

    private var mtkCellLockIgnored: Boolean
        get() = prefs.getBoolean(mtkCellLockIgnoredKey(), false)
        set(value) = prefs.edit().putBoolean(mtkCellLockIgnoredKey(), value).apply()

    private fun mtkCellLockIgnoredKey() = "mtkCellLockIgnored.${current.device?.manufacturer}.${current.device?.model}"

    private fun halSpecsKey(sim: Int) = "halSpecs.sim$sim"

    private fun visibleChannels(
        rat: Rat,
        bands: List<Int>,
    ): List<Long> =
        current.cells
            .filter { it.rat == rat && it.isOnAnyOf(bands) }
            .map { it.arfcn }
            .distinct()
            .take(MAX_HAL_CHANNELS)

    private fun heldBandChannels(ran: Int): List<Long> =
        savedChannelSpecs().lastOrNull { it.optInt("ran") == ran && !it.isCellLock }?.intList("channels").orEmpty().map(Int::toLong)

    private fun bandLockProbe(
        group: Group,
        bands: List<Int>,
    ): String {
        val rat = ratOf(group)
        val matching = visibleChannels(rat, bands).joinToString().ifEmpty { "none" }
        val visible =
            current.cells
                .filter { it.rat == rat }
                .take(MAX_HAL_CHANNELS)
                .joinToString { cell ->
                    "${cell.bandLabel}@${cell.arfcn}/PCI${cell.pci}${if (cell.registered) "/serving" else ""}"
                }.ifEmpty { "none" }
        return "selected=${group.label}[${bands.joinToString()}], matching channels=$matching, visible cells=$visible"
    }

    /**
     * Every lock the radio HAL should hold once [override] replaces the bands of the groups it names: the band locks
     * of every network, then the cell locks on the networks [override] leaves alone.
     */
    private fun bandSpecs(
        override: Map<Group, List<Int>>,
        withChannels: Boolean = needsChannels,
    ): JSONArray {
        val specs = JSONArray()
        for (group in listOf(Group.LTE, Group.NR, Group.WCDMA, Group.GSM)) {
            val groupState = current.groups.firstOrNull { it.group == group } ?: continue
            val bands = override[group] ?: groupState.current?.sorted().orEmpty()
            if (bands.isEmpty() || bands.containsAll(groupState.supported)) continue
            // Only LTE and 5G locks have needed channels so far, and 2G and 3G cells are rarely visible from LTE.
            val pinsChannels = withChannels && !group.isLegacy
            val channels =
                when {
                    !pinsChannels -> emptyList()
                    group in override -> visibleChannels(ratOf(group), bands)
                    else -> visibleChannels(ratOf(group), bands).ifEmpty { heldBandChannels(ranOf(group)) }
                }
            specs.put(channelSpec(ranOf(group), halBandsOf(group, bands), channels).put("bandLock", true))
        }
        // Applying bands replaces whatever is locked on that network. A cell lock on the other one stays.
        val replacedRans = override.keys.map(::ranOf)
        savedChannelSpecs().filter { it.isCellLock && it.optInt("ran") !in replacedRans }.forEach { specs.put(it) }
        return specs
    }

    suspend fun setNetMode(mode: NetMode, requestedMask: Long = mode.mask, modeTitle: Text = Text(mode.label)): String {
        val done = Text(R.string.modem_net_mode_set, modeTitle)
        return change(Text(R.string.modem_goal_net_mode), done) {
            // A mode picked by hand replaces the one an unlock would bring back.
            setAllowedMask(requestedMask, done).also { maskBeforeLock = null }
        }
    }

    /** Returns [done] with any notes added, or null if there are none. */
    private suspend fun setAllowedMask(
        requested: Long,
        done: Text,
    ): Text? {
        val subscriptionId = subId()
        val mask = requested and current.missingGenerations.inv()
        if (mask and Masks.GENERATIONS == 0L) fail(R.string.modem_last_network)
        var setThroughAndroid = true
        val result =
            try {
                helper.call("aosp.setAllowed", NET_MODE_TIMEOUT) {
                    put("subId", subscriptionId)
                    put("mask", mask)
                    put("slot", current.sim - 1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setThroughAndroid = false
                when (current.backend) {
                    Backend.QUALCOMM ->
                        helper.call("qc.setRat") {
                            put("sim", current.sim)
                            put("mask", Masks.toQmi(mask))
                            put("persist", current.persist)
                        }

                    Backend.MEDIATEK -> {
                        try {
                            callMediatek("mtk.setRat") { put("mask", mask) }
                        } catch (atError: CancellationException) {
                            throw atError
                        } catch (atError: Exception) {
                            log("AT+ERAT didn't set the network mode either (${englishReason(atError).asClause()}).")
                            throw e
                        }
                        log("Android refused the network mode (${englishReason(e).asClause()}), so bunnylock set it with AT+ERAT.")
                        JSONObject().put("setByAt", true)
                    }

                    else -> throw e
                }
            }
        // MediaTek's RIL skips a mode it thinks is already set, and it doesn't see one set with AT+ERAT.
        if (current.backend == Backend.MEDIATEK && !result.optBoolean("setByAt")) runSuspendCatching { callMediatek("mtk.setRat") { put("mask", mask) } }
        val heldBack = result.optText("heldBack")
        // Some radios report the new mode while the SIM stays on its old network.
        val stayed = setThroughAndroid && !result.optBoolean("airplaneMode") && heldBack === null && !reachesNetworkIn(mask, subscriptionId)
        val reapplied = stayed && reapplyNetMode(subscriptionId, mask)
        val notes =
            buildList {
                if (result.optBoolean("setByAt")) add(Text(R.string.modem_note_set_by_at))
                if (result.optBoolean("airplaneMode")) add(Text(R.string.modem_note_mode_airplane))
                if (reapplied) add(Text(R.string.modem_note_mode_reapplied, current.sim))
                if (stayed && !reapplied) add(Text(R.string.modem_note_mode_stayed, current.sim))
                heldBack?.let { add(Text(R.string.modem_note_mode_held_back, it)) }
            }
        if (notes.isEmpty()) return null
        return notes.fold(done, Text::followedBy)
    }

    /** True once the SIM is on a network [mask] allows. Also true when its network type is unknown, since there's nothing to compare. */
    @SuppressLint("MissingPermission")
    private suspend fun reachesNetworkIn(
        mask: Long,
        subscriptionId: Int,
    ): Boolean {
        val defaultTelephony = context.getSystemService(TelephonyManager::class.java) ?: return true
        val telephony = if (subscriptionId >= 0) defaultTelephony.createForSubscriptionId(subscriptionId) else defaultTelephony
        repeat(NET_MODE_SWITCH_CHECKS) { check ->
            if (check > 0) delay(NET_MODE_SWITCH_CHECK)
            val networkType =
                runCatching { telephony.voiceNetworkType.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN } ?: telephony.dataNetworkType }
                    .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
            val unknown = networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN || networkType == TelephonyManager.NETWORK_TYPE_IWLAN
            if (unknown || Masks.allows(mask, networkType)) return true
        }
        return false
    }

    private suspend fun reapplyNetMode(
        subscriptionId: Int,
        mask: Long,
    ): Boolean =
        try {
            helper.call("aosp.reapplyAllowed", NET_MODE_TIMEOUT) {
                put("subId", subscriptionId)
                put("mask", mask)
            }
            true
        } catch (e: RootException) {
            log("Setting the network mode again with the radio off didn't work (${englishReason(e).asClause()}).")
            false
        }

    suspend fun setNrMode(mode: NrMode) =
        change(Text(R.string.modem_goal_nr_mode, Text(mode.label)), Text(R.string.modem_nr_mode_set, Text(mode.label))) {
            when (current.backend) {
                Backend.QUALCOMM -> {
                    try {
                        // QMI numbers the modes in NrMode's declaration order: 0 both, 1 NSA only, 2 SA only.
                        helper.call("qc.setNrMode") {
                            put("sim", current.sim)
                            put("mode", mode.ordinal)
                            put("persist", current.persist)
                        }
                        samsungNrModeReadbackSims -= current.sim
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (current.device?.samsungNrMode != true) throw e
                        setSamsungNrMode(mode)
                        samsungNrModeReadbackSims += current.sim
                    }
                }

                Backend.PIXEL -> {
                    val value =
                        when (mode) {
                            NrMode.BOTH -> "11"
                            NrMode.NSA -> "01"
                            NrMode.SA -> "10"
                        }
                    helper.call("pixel.setNrMode") {
                        put("sim", current.sim)
                        put("value", value)
                    }
                    restartRadio()
                }

                Backend.ANDROID if current.device?.samsungNrMode == true -> {
                    setSamsungNrMode(mode)
                }

                else -> {
                    val backend = current.backend
                    if (backend === null) fail(R.string.modem_current_backend_no_nr_mode)
                    fail(R.string.modem_backend_no_nr_mode, Text(backend.label))
                }
            }
            null
        }

    suspend fun setNrDc(enabled: Boolean) =
        change(
            Text(if (enabled) R.string.modem_goal_nr_dc_on else R.string.modem_goal_nr_dc_off),
            Text(if (enabled) R.string.modem_nr_dc_on else R.string.modem_nr_dc_off),
        ) {
            val requestedState = if (enabled) NR_DUAL_CONNECTIVITY_ENABLE else NR_DUAL_CONNECTIVITY_DISABLE
            helper.call("aosp.setNrDc") {
                put("subId", subId())
                put("state", requestedState)
            }
            null
        }

    suspend fun setIms(
        feature: ImsFeature,
        enabled: Boolean,
    ): String {
        val (goal, success) =
            when (feature) {
                ImsFeature.VOLTE ->
                    if (enabled) R.string.modem_goal_volte_on to R.string.modem_volte_on else R.string.modem_goal_volte_off to R.string.modem_volte_off
                ImsFeature.VOWIFI ->
                    if (enabled) R.string.modem_goal_vowifi_on to R.string.modem_vowifi_on else R.string.modem_goal_vowifi_off to R.string.modem_vowifi_off
            }
        return change(Text(goal), Text(success)) {
            val subscriptionId = subId()
            val result =
                helper.call("aosp.setIms") {
                    put("subId", subscriptionId)
                    put("feature", feature.key)
                    put("enabled", enabled)
                }
            if (subId() == subscriptionId) updateState { copy(ims = imsSettingsFrom(result)) }
            null
        }
    }

    suspend fun setCarrierAggregation(enabled: Boolean): String {
        val done = Text(if (enabled) R.string.modem_ca_on else R.string.modem_ca_off)
        return change(Text(if (enabled) R.string.modem_goal_ca_on else R.string.modem_goal_ca_off), done) {
            if (current.backend != Backend.MEDIATEK) fail(R.string.modem_no_ca_control)
            val result = callMediatek("mtk.setCa") { put("enabled", enabled) }
            updateState { copy(carrierAggregation = result.optBoolean("ca", enabled)) }
            // MediaTek modems only apply AT+ECASW after they restart.
            val restart = runSuspendCatching { restartModem() }
            restart.exceptionOrNull()?.let { return@change done.followedBy(Text(R.string.modem_ca_restart_failed, reasonOf(it))) }
            done.followedBy(restart.getOrNull() ?: Text(R.string.modem_rebooting))
        }
    }

    suspend fun lockCells(request: CellLockRequest) =
        change(Text(R.string.modem_goal_lock_cell), Text(R.string.modem_cell_locked)) {
            val lockLte = request.rat != LockRat.NR
            val lockNr = request.rat != LockRat.LTE
            val earfcn = if (lockLte) request.earfcn ?: fail(R.string.modem_enter_earfcn) else 0L
            val ltePcis = request.ltePcis
            val arfcn = if (lockNr) request.nrArfcn ?: fail(R.string.modem_enter_nr_arfcn) else 0L
            val nrPcis = request.nrPcis
            val nrBand = request.nrBand
            val pcisGiven = ltePcis.isNotEmpty() || nrPcis.isNotEmpty()

            when {
                current.backend == Backend.QUALCOMM -> {
                    val lteNote =
                        if (lockLte) {
                            if (ltePcis.isEmpty()) fail(R.string.modem_qc_lte_needs_pci)
                            val result =
                                helper.call("qc.lteLock", QC_LTE_LOCK_TIMEOUT) {
                                    put("sim", current.sim)
                                    put("earfcn", earfcn)
                                    put("pcis", JSONArray(ltePcis))
                                }
                            val cells = JSONArray(ltePcis.map { JSONObject().put("pci", it).put("earfcn", earfcn) })
                            prefs.edit().putString(qcLteLockKey(current.sim), JSONObject().put("cells", cells).toString()).apply()
                            if (result.optBoolean("airplaneMode")) Text(R.string.modem_note_qc_lte_off) else null
                        } else {
                            null
                        }
                    if (lockNr) {
                        helper.call("qc.nrLock") {
                            put("sim", current.sim)
                            put("arfcn", arfcn)
                            put("scs", request.nrScs)
                            nrBand?.let { put("band", it) }
                            when (nrPcis.size) {
                                0 -> {
                                    put("type", "arfcn")
                                }

                                1 -> {
                                    put("type", "pci")
                                    put("pci", nrPcis[0])
                                }

                                else -> {
                                    put("type", "multi")
                                    put("pcis", JSONArray(nrPcis))
                                }
                            }
                        }
                    }
                    lteNote?.let { Text(R.string.modem_cell_locked).followedBy(it) }
                }

                current.mtkAtCellLock -> {
                    val pcis = if (lockLte) ltePcis else nrPcis
                    val lock =
                        if (lockLte) {
                            mtkCellLock(MTK_ACT_LTE, earfcn, pcis.firstOrNull())
                        } else {
                            mtkCellLock(MTK_ACT_NR, arfcn, pcis.firstOrNull())
                        }
                    if (!lock.optBoolean("active")) {
                        log("The modem answered AT+EMMCHLCK but didn't keep the lock, so cell locks go through the radio HAL from now on.")
                        mtkCellLockIgnored = true
                        runSuspendCatching { callMediatek("mtk.cellUnlock") }
                        return@change lockCellsThroughHal(lockLte, earfcn, lockNr, arfcn, nrBand, pcisGiven)
                    }
                    restartSimRadio()
                    val anchorOnly = lockLte && lockNr
                    val outcome = Text(if (anchorOnly) R.string.modem_lte_anchor_locked else R.string.modem_cell_locked)
                    val pciNote = if (pcis.size > 1) Text(R.string.modem_note_mtk_one_pci, pcis[0]) else null
                    if (anchorOnly || pciNote !== null) listOfNotNull(outcome, pciNote).reduce(Text::followedBy) else null
                }

                else -> {
                    lockCellsThroughHal(lockLte, earfcn, lockNr, arfcn, nrBand, pcisGiven)
                }
            }
        }

    /**
     * Locks to a frequency through the radio HAL. [earfcn] and [arfcn] only count when their network is being locked.
     * Returns what the user should know when the lock ended up weaker than asked for, otherwise null.
     */
    private suspend fun lockCellsThroughHal(
        lockLte: Boolean,
        earfcn: Long,
        lockNr: Boolean,
        arfcn: Long,
        nrBand: Int?,
        pcisGiven: Boolean,
    ): Text? {
        val lteBand = if (lockLte) Bands.lteBandOf(earfcn.toInt()) ?: fail(R.string.modem_unknown_earfcn, earfcn) else null
        val nrLockBand = if (lockNr) nrBand ?: fail(R.string.modem_unknown_nr_arfcn, arfcn) else null
        val lockedRans = listOfNotNull(Ran.EUTRAN.takeIf { lockLte }, Ran.NGRAN.takeIf { lockNr })
        val bandLocks = savedChannelSpecs().filter { !it.isCellLock }

        fun cellSpec(
            ran: Int,
            band: Int,
            channel: Long,
            withBands: Boolean,
            withChannels: Boolean,
        ) = channelSpec(
            ran,
            bands = if (withBands) listOf(band) else emptyList(),
            channels = if (withChannels) listOf(channel) else emptyList(),
        )

        fun specs(
            withBands: Boolean,
            withChannels: Boolean,
        ) = JSONArray().apply {
            lteBand?.let { put(cellSpec(Ran.EUTRAN, it, earfcn, withBands, withChannels)) }
            nrLockBand?.let { put(cellSpec(Ran.NGRAN, it, arfcn, withBands, withChannels)) }
            // Without channels this is a band lock itself, so it replaces the one on its network instead of standing in for it.
            bandLocks.filter { withChannels || it.optInt("ran") !in lockedRans }.forEach { put(it) }
        }
        // Radio HALs differ: some want band + channel, some only the channel, some refuse channels entirely.
        val attempts = listOf(specs(withBands = true, withChannels = true), specs(withBands = false, withChannels = true))
        val channelLocked = attempts.any { runSuspendCatching { setChannels(it) }.isSuccess }
        if (!channelLocked) {
            setChannels(specs(withBands = true, withChannels = false))
            val bands = listOfNotNull(lteBand?.let { "B$it" }, nrLockBand?.let { "n$it" })
            return Text(R.string.modem_note_band_only, bands)
        }
        val heldSpecs = savedChannelSpecs()

        val stray = strayChannel(settledCells { strayChannel(it, heldSpecs) !== null }.orEmpty(), heldSpecs)
        if (stray !== null) return Text(R.string.modem_note_cell_lock_ignored, stray)
        return if (pcisGiven) Text(R.string.modem_note_frequency_only) else null
    }

    private suspend fun mtkCellLock(
        act: Int,
        arfcn: Long,
        pci: Int?,
    ) = callMediatek("mtk.cellLock") {
        put("act", act)
        put("arfcn", arfcn)
        pci?.let { put("pci", it) }
        put("mode", 3)
    }

    suspend fun unlockCells() =
        change(Text(R.string.modem_goal_unlock_cell), Text(R.string.modem_cell_unlocked)) {
            when {
                current.backend == Backend.QUALCOMM -> {
                    val lteUnlock =
                        if (current.lteLock !== null) helper.call("qc.lteUnlock", QC_LTE_LOCK_TIMEOUT) { put("sim", current.sim) } else null
                    prefs.edit().remove(qcLteLockKey(current.sim)).apply()
                    if (current.nrLock !== null) helper.call("qc.nrUnlock") { put("sim", current.sim) }
                    if (lteUnlock?.optBoolean("airplaneMode") == true) return@change Text(R.string.modem_cell_unlocked).followedBy(Text(R.string.modem_note_qc_lte_off))
                }

                current.mtkAtCellLock -> {
                    callMediatek("mtk.cellUnlock")
                    restartSimRadio()
                }

                else -> {
                    val (cellLocks, bandLocks) = savedChannelSpecs().partition { it.isCellLock }
                    // A network with a band lock stays locked, so only the others are freed.
                    val freedRans = cellLocks.map { it.optInt("ran") } - bandLocks.map { it.optInt("ran") }.toSet()
                    replaceSavedBands(freedRans)
                    setChannels(JSONArray(bandLocks))
                    finishHalUnlock(cellLocks.filter { it.optInt("ran") in freedRans })?.let { return@change Text(R.string.modem_cell_unlocked).followedBy(it) }
                }
            }
            null
        }

    suspend fun lockPlmn(
        mcc: String,
        mnc: String,
    ) =
        change(Text(R.string.modem_goal_lock_plmn), Text(R.string.modem_plmn_locked)) {
            if (mcc.length != 3) fail(R.string.modem_mcc_digits)
            if (mnc.length !in 2..3) fail(R.string.modem_mnc_digits)
            val numeric = mcc + mnc
            if (current.backend == Backend.QUALCOMM) {
                try {
                    helper.call("qc.setPlmn") {
                        put("sim", current.sim)
                        put("mcc", mcc.toInt())
                        put("mnc", mnc.toInt())
                        put("threeDigit", mnc.length == 3)
                        put("persist", current.persist)
                    }
                    prefs.edit().putString(plmnKey(current.sim), numeric).apply()
                    return@change Text(R.string.modem_plmn_locked_to, current.sim, formatPlmn(numeric))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(
                        "The QMI network lock didn't work (${englishReason(e).asClause()}), so Android network selection is being tried instead.",
                    )
                }
            }
            // Android answers only once the modem has registered on the network or given up, hence the long timeout.
            helper.call("aosp.setPlmn", 180.seconds) {
                put("subId", subId())
                put("plmn", numeric)
                put("persist", true)
            }
            Text(R.string.modem_plmn_locked_to, current.sim, formatPlmn(numeric))
        }

    suspend fun unlockPlmn() =
        change(Text(R.string.modem_goal_unlock_plmn), Text(R.string.modem_plmn_unlocked)) {
            clearPlmn()
            null
        }

    private suspend fun clearPlmn() {
        if (current.backend == Backend.QUALCOMM) {
            runSuspendCatching {
                helper.call("qc.clearPlmn") {
                    put("sim", current.sim)
                    put("persist", current.persist)
                }
            }
        }
        helper.call("aosp.setPlmnAuto", 60.seconds) { put("subId", subId()) }
        prefs.edit().remove(plmnKey(current.sim)).apply()
    }

    /** With [exclusive], the network mode from before the first exclusive lock comes back too. */
    suspend fun unlockEverything(exclusive: Boolean = false) =
        change(Text(R.string.modem_goal_unlock_all), Text(R.string.modem_all_unlocked)) {
            val note = unlockAllLocks()
            val restored = if (exclusive) restoreNetworks() else null
            if (restored === null) note else (note ?: Text(R.string.modem_all_unlocked)).followedBy(restored)
        }

    private suspend fun unlockAllLocks(): Text? {
        if (current.anyPlmnLock) runSuspendCatching { clearPlmn() }
        when (current.backend) {
            Backend.QUALCOMM -> {
                helper.call("qc.reset") {
                    put("sim", current.sim)
                    put("persist", current.persist)
                }
                runSuspendCatching { helper.call("qc.lteUnlock", QC_LTE_LOCK_TIMEOUT) { put("sim", current.sim) } }
                    .onSuccess { prefs.edit().remove(qcLteLockKey(current.sim)).apply() }
                runSuspendCatching { helper.call("qc.nrUnlock") { put("sim", current.sim) } }
            }

            Backend.MEDIATEK -> {
                val empty = JSONArray()
                val hasNr = current.groups.any { it.group == Group.NR }
                callMediatek("mtk.setBands") {
                    put("gsm", empty)
                    put("wcdma", empty)
                    put("lte", empty)
                    if (hasNr) put("nr", empty)
                }
                if (current.mtkCellLockRefused) {
                    val held = savedChannelSpecs()
                    runSuspendCatching { setChannels(JSONArray()) }
                    if (held.isNotEmpty()) finishHalUnlock(held)?.let { return Text(R.string.modem_all_unlocked).followedBy(it) }
                } else {
                    val heldCellLock = current.anyCellLock
                    runSuspendCatching { callMediatek("mtk.cellUnlock") }
                    if (heldCellLock) restartSimRadio()
                }
            }

            Backend.PIXEL -> {
                helper.call("pixel.setLte") { put("bands", JSONArray()) }
                helper.call("pixel.setNr") { put("bands", JSONArray()) }
                setChannels(JSONArray())
                restartRadio()
            }

            Backend.ANDROID, null -> {
                val held = savedChannelSpecs()
                replaceSavedBands(held.map { it.optInt("ran") } + Ran.EUTRAN)
                setChannels(JSONArray())
                finishHalUnlock(held)?.let { return Text(R.string.modem_all_unlocked).followedBy(it) }
            }
        }
        return null
    }

    suspend fun radioCycle() =
        change(Text(R.string.modem_goal_restart_radio), Text(R.string.modem_radio_restarted)) {
            restartRadio()
            null
        }

    suspend fun reattach() =
        change(Text(R.string.modem_goal_reattach, current.sim), Text(R.string.modem_reattached, current.sim)) {
            val result =
                helper.call("qc.reattach", 60.seconds) {
                    put("sim", current.sim)
                    put("subId", subId())
                }
            if (result.optString("method") != "radio") return@change null
            val qmiError = result.optText("qmiError") ?: Text(R.string.something_went_wrong)
            Text(R.string.modem_note_reattach_radio, current.sim, qmiError)
        }

    /** Makes the phone pick a cell again under the locks it holds. The locks go out after the restart, since a restart can lose them. */
    suspend fun relock() =
        change(Text(R.string.modem_goal_relock, current.sim), Text(R.string.modem_relocked, current.sim)) {
            restartSimRadio()
            resendLocks()
            null
        }

    /** A Pixel's band lock is left alone, since sending it restarts the radio again. */
    private suspend fun resendLocks() {
        if (current.backend == Backend.QUALCOMM || current.backend == Backend.MEDIATEK) {
            current.groups.filter { it.locked }.forEach { sendBands(it.group, it, it.current.orEmpty().sorted(), unlock = false) }
        }
        val held = savedChannelSpecs()
        if (held.isNotEmpty()) setChannels(JSONArray(held))
    }

    suspend fun rebootModem() = change(Text(R.string.modem_goal_reboot), Text(R.string.modem_rebooting)) { restartModem() }

    private suspend fun restartModem(): Text? {
        if (!current.limited && current.device?.qrtr == true) {
            helper.call("qc.reboot", 60.seconds)
            return null
        }
        val result =
            helper.call("aosp.rebootModem", 30.seconds) {
                put("sim", current.sim)
                put("subId", subId())
                put("slot", current.sim - 1)
            }
        if (result.optString("method") != "radio") return null
        val rebootError = result.optText("rebootError")?.let(::inEnglish).orEmpty()
        log("Modem reboot fallback: $rebootError; api=${result.optString("api")}")
        return Text(R.string.modem_note_reboot_radio)
    }

    suspend fun samsungMenu() =
        change(Text(R.string.modem_goal_samsung_menu), Text(R.string.modem_samsung_menu_opened)) {
            // Samsung refuses `am start` from the shell user, so with ADB access the app opens the menu itself.
            if (current.limited && shizukuMenuLauncher !== null) shizukuMenuLauncher() else helper.call("samsung.menu", 40.seconds)
            null
        }

    suspend fun sendAt(command: String): String =
        attempt(Text(R.string.modem_goal_send_at)) {
            val response =
                helper.call("at", 15.seconds) {
                    put("node", current.atNode)
                    put("command", command)
                    put("timeout", 6000)
                }
            response.optString("reply")
        }

    suspend fun sendQmi(
        service: Int,
        msg: Int,
        payload: String,
    ): JSONObject =
        attempt(Text(R.string.modem_goal_send_qmi, msg)) {
            helper.call("qc.raw") {
                put("sim", current.sim)
                put("service", service)
                put("msg", msg)
                put("payload", payload)
            }
        }

    /** The subscriptions the app sees. A lock for one Android doesn't have is refused outright. */
    private fun simDiagnostics(): String {
        val phonePermission = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val sims = current.sims.joinToString(", ") { "SIM ${it.slot + 1} (subscription ${it.subId})" }
        return "SIMs: $sims. Selected: SIM ${current.sim}. Phone permission: ${if (phonePermission) "granted" else "denied"}."
    }

    fun diagnostics(
        title: String,
        accessLines: List<String> = emptyList(),
    ): String {
        val device = current.device
        return buildString {
            appendLine(title)
            appendLine(
                "Device: ${device?.manufacturer ?: Build.MANUFACTURER} ${device?.model ?: Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("Chipset: ${device?.vendorLabel?.let { englishContext.getString(it) }}, platform ${device?.platform}, SoC ${device?.socModel}")
            appendLine("Baseband: ${device?.baseband}")
            appendLine("RIL: ${device?.ril}")
            appendLine("Access: ${current.access?.label}, backend: ${current.backend?.label?.let { englishContext.getString(it) }}")
            appendLine(simDiagnostics())
            accessLines.forEach { appendLine(it) }
            appendLine(
                "QMI over QRTR: ${device?.qrtr}, HAL channel-lock API: ${if (device?.aospChannels == true) "present" else "absent"}, EN-DC control: ${device?.aospNrDc}",
            )
            appendLine("Supported network types: ${current.supportedTypes?.let { "0x%X".format(it) } ?: "not reported"}")
            appendLine("Network mode masks: ${current.netModeMasks ?: "not reported"}")
            appendLine("AT ports: ${device?.atNodes?.joinToString(", ")}")
            appendLine("Modem command passthrough: ${device?.mtkOemHookStatus?.let(::inEnglish)}")
            device?.mtkOemHookChecked?.takeIf { it.isNotEmpty() }?.let { checked -> appendLine("Checked: ${checked.joinToString("; ") { inEnglish(it) }}") }
            appendLine()
            appendLine("Carriers: ${current.carriers}")
            appendLine("Serving and 5G cells: ${current.cells.filter { it.registered || it.rat == Rat.NR }}")
            appendLine("Android physical channels: ${physicalChannelReport ?: "not read"}")
            if (current.backend == Backend.QUALCOMM) appendLine("QMI serving report: ${qualcommServingReport ?: "not read"}")
            appendLine()
            appendLine("Log:")
            current.log.forEach { appendLine(it) }
        }
    }
}
