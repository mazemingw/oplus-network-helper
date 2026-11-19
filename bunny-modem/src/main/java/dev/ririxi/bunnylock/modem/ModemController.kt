package dev.ririxi.bunnylock.modem

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dev.ririxi.bunnylock.backend.CellLock
import dev.ririxi.bunnylock.backend.HalLocks
import dev.ririxi.bunnylock.backend.LocksBandsTogether
import dev.ririxi.bunnylock.backend.ModemSession
import dev.ririxi.bunnylock.backend.samsung.SamsungBandMenu
import dev.ririxi.bunnylock.backend.withReadout
import dev.ririxi.bunnylock.backend.withStrayBands
import dev.ririxi.bunnylock.data.Carriers
import dev.ririxi.bunnylock.data.Cells
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.RootException
import dev.ririxi.bunnylock.data.reasonText
import dev.ririxi.bunnylock.root.OemHook
import dev.ririxi.bunnylock.root.fail
import dev.ririxi.bunnylock.root.rilNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val NET_MODE_TIMEOUT = 60.seconds
private const val NET_MODE_SWITCH_CHECKS = 10
private val NET_MODE_SWITCH_CHECK = 1.seconds

private val LIVE_CELL_SCAN_WAIT = 1500.milliseconds

class ModemController(
    context: Context,
    private val scope: CoroutineScope,
    private val starter: HelperStarter,
    private val onConnected: () -> Unit = {},
    private val onRefreshed: (ModemState) -> Unit = {},
    shizukuMenuLauncher: (suspend () -> Unit)? = null,
) {
    private val session = ModemSession(context, ::featuresOf)
    private val context get() = session.context
    private val prefs get() = session.prefs
    private val hal = HalLocks(session, refresh = ::refreshNow)
    private val backends = Backends(session, hal)
    private val backend get() = backends[current.backend]
    private val bandMenu = SamsungBandMenu(session, shizukuMenuLauncher)

    val state = session.state
    val events = session.events

    val helperRunning get() = session.helper.running

    private val current get() = session.current

    /** The host distinguishes a failed readback from an acknowledged write. */
    var lastRefreshFailure: String? = null
        private set

    private var connecting: Job? = null

    fun close() = session.helper.stop()

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
            if (current.root != RootState.Granted || !session.helper.running) connect()
        }
    }

    fun helperLost(reason: Text) {
        session.helper.stop()
        log(session.inEnglish(reason))
        session.update { copy(root = RootState.Denied(context.string(reason))) }
    }

    private suspend fun connectNow() {
        session.update { copy(root = RootState.Checking) }
        session.helper.stop()
        try {
            val started = starter.start(::showConnectStep)
            session.helper = started.client
            val hello = started.hello
            val access = started.access
            val shellOnly = access == Access.SHIZUKU_ADB
            if (!shellOnly && !hello.optBoolean("qrtrLib")) log("The QRTR library didn't load (${hello.optString("libError").asClause()}), so QMI won't work.")
            showConnectStep(context.getString(R.string.modem_connect_checking))
            // Probing every vendor telephony service for a modem passthrough is slow on phones that have many.
            val device = DeviceInfo.from(session.call("detect", 90.seconds))

            val available = availableBackends(device, shellOnly)
            val suggested = suggestedBackend(device, available)
            val savedBackendName = prefs.getString("backend", null)
            val backend = Backend.entries.firstOrNull { it.name == savedBackendName }?.takeIf { it in available } ?: suggested
            session.update {
                copy(
                    root = RootState.Granted,
                    access = access,
                    device = device,
                    offeredBackends = offeredBackends(device),
                    availableBackends = available,
                    autoBackend = suggested,
                    backend = backend,
                    halNeedsChannels = hal.needsChannels(device),
                )
            }
            val node =
                when {
                    shellOnly && device.rilPassthrough -> rilNode(current.sim)
                    current.atNode.isNotEmpty() -> nodeForSim(current.atNode, current.sim)
                    else -> device.defaultAtNode
                }
            session.update { copy(atNode = node) }
            val accessSummary =
                when {
                    access == Access.ROOT -> "Root access granted."
                    access == Access.SHIZUKU_ROOT -> "Connected through Shizuku running as root."
                    device.rilPassthrough && (backend == Backend.MEDIATEK || backend == Backend.UNISOC) ->
                        "Connected through Shizuku with ADB access. The RIL's AT passthrough is available, so band and cell locking work without root."
                    else -> "Connected through Shizuku with ADB access, so only the Android radio HAL features are available."
                }
            log("$accessSummary Using ${session.englishContext.getString(backend.label)} (chipset: ${session.englishContext.getString(device.chipset.label)}).")
            started.note?.let(::log)
            loadSims()
            refreshNow()
            onConnected()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = e.reasonText() ?: Text(R.string.modem_root_denied)
            log("Couldn't connect. ${session.inEnglish(why)}")
            session.update { copy(root = RootState.Denied(context.string(why))) }
        }
    }

    private fun showConnectStep(text: String) = session.update { copy(connectStep = text) }

    private var simLookup = ""

    @SuppressLint("MissingPermission")
    fun loadSims() {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val listed = ArrayList<SimInfo>()
        val listing =
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                "not read"
            } else {
                runCatching {
                    subscriptions.activeSubscriptionInfoList.orEmpty().mapTo(listed) { subscription ->
                        val name = subscription.displayName?.toString() ?: context.getString(R.string.modem_sim, subscription.simSlotIndex + 1)
                        SimInfo(subscription.simSlotIndex, subscription.subscriptionId, name)
                    }
                    "${listed.size}"
                }.getOrElse { "failed with $it" }
            }
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val modems = if (Build.VERSION.SDK_INT >= 30) telephony.activeModemCount else @Suppress("DEPRECATION") telephony.phoneCount
        // Looking up a slot needs no permission, so it also finds a SIM that the subscription list leaves out.
        val foundBySlot =
            (0 until modems)
                .filter { slot -> listed.none { it.slot == slot } }
                .mapNotNull { slot -> subIdInSlot(subscriptions, slot)?.let { SimInfo(slot, it, context.getString(R.string.modem_sim, slot + 1)) } }
        val cards = (0 until modems).joinToString { slot -> "SIM ${slot + 1} ${simStateName(telephony.getSimState(slot))}" }
        simLookup = "Listed subscriptions: $listing. Cards: $cards. Found by slot: ${foundBySlot.joinToString { "SIM ${it.slot + 1}" }.ifEmpty { "none" }}."
        val sims =
            (listed + foundBySlot)
                .sortedBy { it.slot }
                .ifEmpty { listOf(SimInfo(0, SubscriptionManager.getDefaultSubscriptionId(), context.getString(R.string.modem_sim, 1))) }
        val savedSim = prefs.getInt("sim", 1)
        session.update {
            val selected = listOf(savedSim, sim).firstOrNull { wanted -> sims.any { it.slot == wanted - 1 } } ?: (sims.first().slot + 1)
            copy(sims = sims, sim = selected, atNode = if (selected == sim) atNode else nodeForSim(atNode, selected))
        }
    }

    private fun nodeForSim(
        node: String,
        sim: Int,
    ) = if (node.startsWith(OemHook.PREFIX)) rilNode(sim) else node

    private fun subIdInSlot(
        subscriptions: SubscriptionManager,
        slot: Int,
    ): Int? {
        val subId =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    SubscriptionManager.getSubscriptionId(slot)
                } else {
                    @Suppress("DEPRECATION")
                    subscriptions.getSubscriptionIds(slot)?.firstOrNull()
                }
            }.getOrNull()
        return subId?.takeIf(SubscriptionManager::isValidSubscriptionId)
    }

    private fun simStateName(state: Int): String =
        when (state) {
            TelephonyManager.SIM_STATE_ABSENT -> "absent"
            TelephonyManager.SIM_STATE_READY -> "ready"
            TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "disabled"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "card error"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "restricted"
            else -> "unknown"
        }

    fun setSim(sim: Int) {
        prefs.edit().putInt("sim", sim).apply()
        session.update { copy(sim = sim, atNode = nodeForSim(atNode, sim)) }
        refresh()
    }

    suspend fun setSimAndRefresh(sim: Int) {
        check(current.sims.any { it.slot == sim - 1 }) { "SIM is unavailable" }
        prefs.edit().putInt("sim", sim).apply()
        val atNode = nodeForSim(current.atNode, sim)
        session.update { copy(sim = sim, atNode = atNode) }
        refreshNow()
    }

    fun setBackend(backend: Backend) {
        if (!current.allows(backend)) return
        prefs.edit().putString("backend", backend.name).apply()
        session.update { copy(backend = backend, groups = emptyList(), lteLock = null, nrLock = null, legacyCellLock = null, strayChannel = null, carrierAggregation = null) }
        refresh()
    }

    suspend fun selectBackendAndRefresh(backend: Backend) {
        check(current.allows(backend)) { "Backend is unavailable with the current access" }
        prefs.edit().putString("backend", backend.name).apply()
        session.update { copy(backend = backend, groups = emptyList(), lteLock = null, nrLock = null, legacyCellLock = null, strayChannel = null, carrierAggregation = null) }
        refreshNow()
    }

    fun setPersist(enabled: Boolean) {
        prefs.edit().putBoolean("persist", enabled).apply()
        session.update { copy(persist = enabled) }
    }

    fun setAtNode(node: String) = session.setAtNode(node)

    fun log(message: String) = session.log(message)

    private suspend fun change(
        goal: Text,
        success: Text,
        block: suspend () -> Text?,
    ): String = session.logAndLocalize(attempt(goal) { block() ?: success })

    private suspend fun quietChange(
        goal: Text,
        success: Text,
        block: suspend () -> Text?,
    ): String? {
        val result = attempt(goal, block) ?: success
        return session.logAndLocalize(result).takeUnless { result === success }
    }

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
            throw ModemException(session.logAndLocalize(failure), failure)
        }

    fun refresh() =
        scope.launch {
            if (current.root == RootState.Granted && !session.helper.running) reconnect() else refreshNow()
        }

    suspend fun refreshNow() {
        lastRefreshFailure = null
        if (!session.helper.running) return
        session.update { copy(refreshing = true) }
        try {
            // Subscription ids change when a SIM is swapped or switched off and on.
            loadSims()
            val sim = current.sim
            val subscriptionId = session.subId(sim)
            val refreshedBackend = current.backend
            coroutineScope {
                val androidCells = async { Cells.scan(context, subscriptionId) }
                val aospState = session.aospState(sim)
                val ims = loadIms(sim)
                val readout = backends[refreshedBackend].read(sim, aospState)
                // A backend picked during the refresh gets a refresh of its own, and this readout isn't its.
                if (current.backend != refreshedBackend) return@coroutineScope
                val telephony =
                    context
                        .getSystemService(TelephonyManager::class.java)
                        .let { if (subscriptionId >= 0) it.createForSubscriptionId(subscriptionId) else it }
                session.backendCells = readout.cells
                session.backendCarriers = readout.carriers
                val cells = Cells.merge(readout.cells, androidCells.await())
                val signals = Carriers.signals(context, subscriptionId)
                val carriers = session.connectionCarriers(sim, cells, signals)
                val shown =
                    readout.copy(
                        groups = if (readout.bandLocksThroughHal) withStrayBands(readout.groups, readout.lteLock, readout.nrLock, cells, carriers) else readout.groups,
                        strayChannel = if (readout.cellLocksThroughHal) hal.strayChannel(cells, hal.channelSpecs(aospState, sim)) else null,
                        plmnLock = if (readout.plmnReported) readout.plmnLock else halPlmnLock(aospState),
                    )
                if (current.backend != refreshedBackend) return@coroutineScope
                session.update {
                    withReadout(shown).copy(
                        registeredPlmn = telephony.networkOperator?.takeIf { it.length >= 5 },
                        registeredName = telephony.networkOperatorName?.takeIf { it.isNotBlank() },
                        homePlmn = telephony.simOperator?.takeIf { it.length >= 5 },
                        homeName = telephony.simOperatorName?.takeIf { it.isNotBlank() },
                        cells = cells,
                        carriers = carriers,
                        signals = signals,
                        allowedMask = readout.allowedMask ?: aospState?.takeIf { it.has("allowed") }?.optLong("allowed"),
                        supportedTypes = aospState?.takeIf { it.has("supportedTypes") }?.optLong("supportedTypes"),
                        netModeHeldBack = aospState?.optText("modeHeldBack")?.let { context.string(it) },
                        netModeMasks = aospState?.optString("modeMasks")?.takeIf { it.isNotEmpty() },
                        nrDc = aospState?.takeIf { it.has("nrDc") }?.optBoolean("nrDc"),
                        ims = ims,
                    )
                }
                onRefreshed(current)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = Text(R.string.modem_couldnt, Text(R.string.modem_goal_refresh), reasonOf(e))
            lastRefreshFailure = context.string(error)
            session.report(error)
        } finally {
            session.update { copy(refreshing = false) }
        }
    }

    private suspend fun loadIms(sim: Int): Map<ImsFeature, ImsSetting> =
        runSuspendCatching { session.call("aosp.ims", sim = sim) }.getOrNull()?.let(::imsSettingsFrom).orEmpty()

    suspend fun refreshIms() {
        if (!session.helper.running) return
        val sim = current.sim
        val subscriptionId = session.subId(sim)
        val ims = loadIms(sim)
        if (session.subId() == subscriptionId) session.update { copy(ims = ims) }
    }

    suspend fun refreshConnection() {
        if (!session.helper.running || current.refreshing || current.root != RootState.Granted) return
        val sim = current.sim
        val subscriptionId = session.subId(sim)
        backend.refreshLive(sim)
        val cells = Cells.merge(session.backendCells, Cells.scan(context, subscriptionId, LIVE_CELL_SCAN_WAIT))
        val signals = Carriers.signals(context, subscriptionId)
        val carriers = session.connectionCarriers(sim, cells, signals)
        if (current.refreshing || current.sim != sim) return
        session.update {
            val liveGroups = if (bandLocksThroughHal && !settling) withStrayBands(groups, lteLock, nrLock, cells, carriers) else groups
            copy(cells = cells, carriers = carriers, signals = signals, groups = liveGroups)
        }
    }

    private fun halPlmnLock(aospState: JSONObject?): String? {
        if (aospState?.optInt("selectionMode") != TelephonyManager.NETWORK_SELECTION_MODE_MANUAL) return null
        val manualPlmn = aospState.optString("manualPlmn")
        return if (manualPlmn.isEmpty()) context.getString(R.string.modem_plmn_manual) else formatPlmn(manualPlmn)
    }

    fun stopWaiting() = hal.stopWaiting()

    fun forgetNeedsChannels() = hal.forgetNeedsChannels()

    fun forgetKeptOffBands() {
        backend.forgetKeptOffBands()
        refresh()
    }

    /**
     * With [exclusive], every network without a band lock of its own is turned off unless it's in [keepOn], and unlocking turns
     * networks back on.
     */
    suspend fun lockBands(
        group: Group,
        bands: Collection<Int>,
        exclusive: Boolean = false,
        keepOn: Collection<Group> = emptyList(),
    ): String = applyGroup(group, unlock = false, exclusive, bands, keepOn)

    val locksBandsTogether get() = backend is LocksBandsTogether

    suspend fun lockBands(
        locks: Map<Group, Collection<Int>>,
        exclusive: Boolean = false,
        keepOn: Collection<Group> = emptyList(),
    ): String {
        val together = checkNotNull(backend as? LocksBandsTogether) { "${current.backend} locks one network at a time. Check locksBandsTogether first." }
        require(locks.isNotEmpty() && locks.all { (group, bands) -> current.groups.any { it.group == group && !bands.containsAll(it.supported) } }) {
            "Every list has to leave some of its network's bands out. Use the one-network lockBands or unlockBands otherwise."
        }
        val labels = locks.keys.map { it.label }
        val done = Text(R.string.modem_bands_locked, labels)
        return change(Text(R.string.modem_goal_lock_bands, labels), done) {
            val bands = locks.mapValues { (_, selected) -> selected.distinct().sorted() }
            if (bands.values.any { it.isEmpty() }) fail(R.string.modem_pick_band)
            if (!exclusive) return@change together.lockBands(bands)
            sendAfterSwitching(current.maskKeepingLocks(locks.keys, alsoKept = keepOn)) { together.lockBands(bands) ?: done }
        }
    }

    suspend fun unlockBands(
        group: Group,
        exclusive: Boolean = false,
    ): String = applyGroup(group, unlock = true, exclusive)

    /** Takes [group]'s network out of the network mode. */
    suspend fun turnOff(group: Group): String =
        change(Text(R.string.modem_goal_network_off, group.label), Text(R.string.modem_network_off, group.label)) {
            switchNetworks(current.netModeMask and group.generation.inv()).also { backend.keepLocksAfterModeChange() }
        }

    private suspend fun applyGroup(
        group: Group,
        unlock: Boolean,
        exclusive: Boolean,
        selected: Collection<Int> = emptyList(),
        keepOn: Collection<Group> = emptyList(),
    ) = change(
        Text(if (unlock) R.string.modem_goal_unlock_bands else R.string.modem_goal_lock_bands, group.label),
        bandsDone(group, unlock),
    ) {
        val groupState = current.groups.first { it.group == group }
        val bands = if (unlock) groupState.supported else selected.distinct().sorted()
        if (bands.isEmpty()) fail(R.string.modem_pick_band)
        if (exclusive) applyExclusive(group, groupState, bands, unlock, keepOn) else sendBands(group, groupState, bands, unlock)
    }

    /** Changes the network mode before the bands, since some radios ignore a lock sent before the change. */
    private suspend fun applyExclusive(
        group: Group,
        groupState: BandGroupState,
        bands: List<Int>,
        unlock: Boolean,
        keepOn: Collection<Group>,
    ): Text? {
        val othersLocked = current.groups.any { it.group != group && it.locked }
        val mask =
            when {
                !unlock -> current.maskKeepingLocks(listOf(group), alsoKept = keepOn)
                othersLocked -> current.netModeMask or group.generation
                else -> maskBeforeLock ?: (current.netModeMask or group.generation)
            }
        // A network that's only turned off has no lock to lift.
        val onlyTurnsOn = unlock && !groupState.locked && current.isOff(group)
        val outcome = sendAfterSwitching(mask) { if (onlyTurnsOn) null else sendBands(group, groupState, bands, unlock) ?: bandsDone(group, unlock) }
        if (unlock && !othersLocked) maskBeforeLock = null
        return outcome
    }

    // A send that fails switches the network mode back.
    private suspend fun sendAfterSwitching(
        mask: Long,
        send: suspend () -> Text?,
    ): Text? {
        val before = current.netModeMask
        val switched = switchNetworks(mask)
        val sent =
            runSuspendCatching { send() }
                .onFailure { runSuspendCatching { switchNetworks(before) } }
                .getOrThrow()
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
    ): Text? = backend.setBands(group, bands, liftsLock = bands.containsAll(groupState.supported), done = bandsDone(group, unlock))

    private fun bandsDone(
        group: Group,
        unlock: Boolean,
    ) = Text(if (unlock) R.string.modem_bands_unlocked else R.string.modem_bands_locked, group.label)

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
        val sim = current.sim
        val subscriptionId = session.subId(sim)
        val mask = requested and current.missingGenerations.inv()
        if (mask and Masks.GENERATIONS == 0L) fail(R.string.modem_last_network)
        val result = backend.setNetworkMode(mask) { session.call("aosp.setAllowed", NET_MODE_TIMEOUT, sim) { put("mask", mask) } }
        // Some radios report the new mode while the SIM stays on its old network.
        val stayed = result.throughAndroid && !result.airplaneMode && result.heldBack === null && !reachesNetworkIn(mask, subscriptionId)
        val reapplied = stayed && reapplyNetMode(sim, mask)
        val notes =
            buildList {
                result.note?.let(::add)
                if (result.airplaneMode) add(Text(R.string.modem_note_mode_airplane))
                if (reapplied) add(Text(R.string.modem_note_mode_reapplied, current.sim))
                if (stayed && !reapplied) add(Text(R.string.modem_note_mode_stayed, current.sim))
                result.heldBack?.let { add(Text(R.string.modem_note_mode_held_back, it)) }
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
        sim: Int,
        mask: Long,
    ): Boolean =
        try {
            session.call("aosp.reapplyAllowed", NET_MODE_TIMEOUT, sim) { put("mask", mask) }
            true
        } catch (e: RootException) {
            log("Setting the network mode again with the radio off didn't work (${session.englishReason(e).asClause()}).")
            false
        }

    suspend fun setNrMode(mode: NrMode) =
        quietChange(Text(R.string.modem_goal_nr_mode, Text(mode.label)), Text(R.string.modem_nr_mode_set, Text(mode.label))) {
            backend.setNrMode(mode)
            null
        }

    suspend fun setNrDc(enabled: Boolean) =
        quietChange(
            Text(if (enabled) R.string.modem_goal_nr_dc_on else R.string.modem_goal_nr_dc_off),
            Text(if (enabled) R.string.modem_nr_dc_on else R.string.modem_nr_dc_off),
        ) {
            session.call("aosp.setNrDc") { put("enabled", enabled) }
            null
        }

    suspend fun setIms(
        feature: ImsFeature,
        enabled: Boolean,
    ): String? {
        val (goal, success) =
            when (feature) {
                ImsFeature.VOLTE ->
                    if (enabled) R.string.modem_goal_volte_on to R.string.modem_volte_on else R.string.modem_goal_volte_off to R.string.modem_volte_off
                ImsFeature.VOWIFI ->
                    if (enabled) R.string.modem_goal_vowifi_on to R.string.modem_vowifi_on else R.string.modem_goal_vowifi_off to R.string.modem_vowifi_off
            }
        return quietChange(Text(goal), Text(success)) {
            val subscriptionId = session.subId()
            val result =
                session.call("aosp.setIms") {
                    put("feature", feature.key)
                    put("enabled", enabled)
                }
            if (session.subId() == subscriptionId) session.update { copy(ims = imsSettingsFrom(result)) }
            null
        }
    }

    suspend fun setCarrierAggregation(enabled: Boolean): String? {
        val done = Text(if (enabled) R.string.modem_ca_on else R.string.modem_ca_off)
        return quietChange(Text(if (enabled) R.string.modem_goal_ca_on else R.string.modem_goal_ca_off), done) {
            backend.setCarrierAggregation(enabled, done)
        }
    }

    suspend fun lockCells(request: CellLockRequest) =
        change(Text(R.string.modem_goal_lock_cell), Text(R.string.modem_cell_locked)) {
            val lockLte = request.rat != LockRat.NR
            val lockNr = request.rat != LockRat.LTE
            val lock =
                CellLock(
                    lte = lockLte,
                    earfcn = if (lockLte) request.earfcn ?: fail(R.string.modem_enter_earfcn) else 0L,
                    ltePcis = request.ltePcis,
                    nr = lockNr,
                    arfcn = if (lockNr) request.nrArfcn ?: fail(R.string.modem_enter_nr_arfcn) else 0L,
                    nrPcis = request.nrPcis,
                    nrBand = request.nrBand,
                    nrScs = request.nrScs,
                )
            backend.lockCells(lock)
        }

    suspend fun unlockCells() = change(Text(R.string.modem_goal_unlock_cell), Text(R.string.modem_cell_unlocked)) { backend.unlockCells() }

    suspend fun lockPlmn(
        mcc: String,
        mnc: String,
    ) =
        change(Text(R.string.modem_goal_lock_plmn), Text(R.string.modem_plmn_locked)) {
            if (mcc.length != 3) fail(R.string.modem_mcc_digits)
            if (mnc.length !in 2..3) fail(R.string.modem_mnc_digits)
            val numeric = mcc + mnc
            backend.lockPlmn(mcc, mnc) {
                // Android answers only once the modem has registered on the network or given up, hence the long timeout.
                session.call("aosp.setPlmn", 180.seconds) {
                    put("plmn", numeric)
                    put("persist", true)
                }
                Text(R.string.modem_plmn_locked_to, current.sim, formatPlmn(numeric))
            }
        }

    suspend fun unlockPlmn() =
        change(Text(R.string.modem_goal_unlock_plmn), Text(R.string.modem_plmn_unlocked)) {
            clearPlmn()
            null
        }

    private suspend fun clearPlmn() = backend.clearPlmn { session.call("aosp.setPlmnAuto", 60.seconds) }

    /** With [exclusive], the network mode from before the first exclusive lock comes back too. */
    suspend fun unlockEverything(exclusive: Boolean = false) =
        change(Text(R.string.modem_goal_unlock_all), Text(R.string.modem_all_unlocked)) {
            if (current.anyPlmnLock) runSuspendCatching { clearPlmn() }
            val note = backend.unlockAll()
            val restored = if (exclusive) restoreNetworks() else null
            if (restored === null) note else (note ?: Text(R.string.modem_all_unlocked)).followedBy(restored)
        }

    suspend fun radioCycle() =
        change(Text(R.string.modem_goal_restart_radio), Text(R.string.modem_radio_restarted)) {
            session.restartRadio()
            null
        }

    suspend fun reattach() =
        change(Text(R.string.modem_goal_reattach, current.sim), Text(R.string.modem_reattached, current.sim)) {
            backends.qualcomm.reattach()
        }

    /** Makes the phone pick a cell again under the locks it holds. The locks go out after the restart, since a restart can lose them. */
    suspend fun relock() =
        change(Text(R.string.modem_goal_relock, current.sim), Text(R.string.modem_relocked, current.sim)) {
            session.restartSimRadio()
            backend.resendLocks()
            null
        }

    suspend fun rebootModem() =
        change(Text(R.string.modem_goal_reboot), Text(R.string.modem_rebooting)) {
            if (!current.limited && current.device?.qrtr == true) {
                backends.qualcomm.reboot()
                null
            } else {
                session.rebootModemThroughAndroid()
            }
        }

    suspend fun samsungMenu() =
        change(Text(R.string.modem_goal_samsung_menu), Text(R.string.modem_samsung_menu_opened)) {
            bandMenu.open()
            null
        }

    suspend fun sendAt(command: String): String =
        attempt(Text(R.string.modem_goal_send_at)) {
            val response =
                session.call("at", 15.seconds) {
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
    ): QmiReply = attempt(Text(R.string.modem_goal_send_qmi, msg)) { backends.qualcomm.send(service, msg, payload) }

    /** The subscriptions the app sees. A lock for one Android doesn't have is refused outright. */
    private fun simDiagnostics(): String {
        val phonePermission = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val sims = current.sims.joinToString(", ") { "SIM ${it.slot + 1} (subscription ${it.subId})" }
        return "SIMs: $sims. Selected: SIM ${current.sim}. Phone permission: ${if (phonePermission) "granted" else "denied"}. $simLookup"
    }

    fun diagnostics(
        title: String,
        accessLines: List<String> = emptyList(),
    ): String {
        val device = current.device
        val english = session.englishContext
        return buildString {
            appendLine(title)
            appendLine(
                "Device: ${device?.manufacturer ?: Build.MANUFACTURER} ${device?.model ?: Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("Chipset: ${device?.chipset?.label?.let(english::getString)}, platform ${device?.platform}, SoC ${device?.socModel}")
            appendLine("Baseband: ${device?.baseband}")
            appendLine("RIL: ${device?.ril}")
            appendLine("Access: ${current.access?.label}, backend: ${current.backend?.label?.let(english::getString)}")
            appendLine(simDiagnostics())
            accessLines.forEach { appendLine(it) }
            appendLine(
                "QMI over QRTR: ${device?.qrtr}, HAL channel-lock API: ${if (device?.aospChannels == true) "present" else "absent"}, EN-DC control: ${device?.aospNrDc}",
            )
            appendLine(hal.diagnostics())
            appendLine("Supported network types: ${current.supportedTypes?.let { "0x%X".format(it) } ?: "not reported"}")
            appendLine("Network mode masks: ${current.netModeMasks ?: "not reported"}")
            appendLine("AT ports: ${device?.atNodes?.joinToString(", ")}")
            appendLine("Modem command passthrough: ${device?.rilPassthroughStatus?.let(session::inEnglish)}")
            device?.rilPassthroughChecked?.takeIf { it.isNotEmpty() }?.let { checked -> appendLine("Checked: ${checked.joinToString("; ") { session.inEnglish(it) }}") }
            appendLine()
            appendLine("Carriers: ${current.carriers}")
            appendLine("Serving and 5G cells: ${current.cells.filter { it.registered || it.rat == Rat.NR }}")
            appendLine("Android physical channels: ${session.physicalChannelReport ?: "not read"}")
            backend.diagnostics().forEach { appendLine(it) }
            appendLine()
            appendLine("Log:")
            current.log.forEach { appendLine(it) }
        }
    }
}
