package com.nvmex.networkhelper.viewmodel.menu

import android.app.Application
import android.telephony.SubscriptionManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.qmi.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QmiNoticeLevel { INFO, SUCCESS, WARNING, ERROR }

data class QmiBandUiState(
    val sim: Int = 1, val busy: Boolean = false, val device: QmiDeviceState? = null,
    val draft: QmiProfile? = null, val combinedNr: Boolean = false,
    val error: String? = null, val message: String? = null,
    val noticeLevel: QmiNoticeLevel = QmiNoticeLevel.SUCCESS,
    val mainlandMode: Boolean = true, val mcc: String = "", val mnc: String = "",
    val activeBands: Map<QmiBand, Set<Int>> = emptyMap(),
    val servingCells: List<QmiServingCell> = emptyList(),
    val cellLockState: QmiCellLockState = QmiCellLockState.UNKNOWN,
    val quickPrefill: QmiQuickLockRequest? = null
)

class QmiBandLockViewModel(app: Application) : AndroidViewModel(app) {
    private var client = QmiModemClient(app)
    init { com.nvmex.networkhelper.util.modem.SystemQmiServices.registerDisconnector(this) { releaseDriver() } }

    /** Switching driver releases only this owner's session, without changing modem locks. */
    suspend fun releaseDriver() {
        cancelVerification()
        client.close()
        client = QmiModemClient(getApplication())
        mutable.update { it.copy(device = null, draft = null, error = null, message = null) }
    }
    private val mutable = MutableStateFlow(QmiBandUiState())
    val state = mutable.asStateFlow()
    private var alertJob: Job? = null
    private var verificationJob: Job? = null
    private var statusGeneration = 0L
    private var pending: PendingRegistration? = null
    private var cellVerificationJob: Job? = null
    private var pendingCell: PendingCellRegistration? = null
    private var recentCellTargets: Pair<Int, List<QmiCellTarget>>? = null
    private var networkSubId = -1
    private var requestedQuickId: String? = null
    private var pendingQuick: QmiQuickLockRequest? = null
    private var quickSubscription: Pair<Int, Int>? = null
    private val cellRestore = app.getSharedPreferences("qmi_cell_rat_restore", Application.MODE_PRIVATE)

    fun updateNetwork(sim: Int, network: NetworkPanelUiState?) {
        if (sim != state.value.sim) return
        networkSubId = network?.subId ?: -1
        val observation = qmiObserveBands(network, sim - 1, System.currentTimeMillis())
        val serving = qmiServingCells(network, System.currentTimeMillis())
        mutable.update { ui ->
            val mcc = network?.mcc.orEmpty(); val mnc = network?.mnc.orEmpty()
            ui.copy(mcc = mcc, mnc = mnc, activeBands = QmiBand.entries.associateWith(observation::highlighted),
                servingCells = serving, cellLockState = qmiCellLockState(ui.device, serving, recentTargets(sim)),
                draft = ui.draft?.let { qmiPruneSelection(it, ui.device?.hardware, ui.mainlandMode, mcc, mnc) })
        }
        checkCellVerification(serving)
        val check = pending ?: return
        if (check.sim != sim || observation.sampleAt <= check.issuedAt) return
        val matching = qmiMatchingRegistration(check.selection, observation, check.issuedAt)
        if (matching.isEmpty()) { check.firstMatchAt = 0; return }
        if (check.firstMatchAt == 0L) { check.firstMatchAt = observation.sampleAt; return }
        if (observation.sampleAt - check.firstMatchAt < 500) return
        cancelVerification()
        postStatus(message = getApplication<Application>().getString(R.string.qmi_target_registered, matching.joinToString("、")))
    }

    fun configureMainlandMode(enabled: Boolean) {
        if (state.value.mainlandMode == enabled) return
        cancelVerification()
        mutable.update { ui -> ui.copy(mainlandMode = enabled,
            draft = ui.draft?.let { qmiPruneSelection(it, ui.device?.hardware, enabled, ui.mcc, ui.mnc) }) }
    }

    fun refresh(sim: Int = state.value.sim) {
        if (state.value.busy) return
        if (sim != state.value.sim) networkSubId = -1
        if (pendingQuick?.sim != sim) pendingQuick = null
        if (quickSubscription?.first != sim) quickSubscription = null
        cancelVerification()
        recentCellTargets = null
        mutable.update { it.copy(sim = sim, device = null, draft = null, combinedNr = false,
            servingCells = if (sim == it.sim) it.servingCells else emptyList(), cellLockState = QmiCellLockState.UNKNOWN,
            activeBands = if (sim == it.sim) it.activeBands else emptyMap(),
            mcc = if (sim == it.sim) it.mcc else "", mnc = if (sim == it.sim) it.mnc else "") }
        operation(sim) { client.read(sim) }
    }

    /** Cleanup must await the actual NAS read, not just enqueue a UI refresh. No write is replayed. */
    suspend fun refreshForRecovery() {
        check(!state.value.busy) { "请等待当前操作完成" }
        cancelVerification(); dismissStatus()
        mutable.update { it.copy(busy = true, error = null, message = null) }
        try {
            val device = client.read(state.value.sim)
            // Control and foreground observation need two simultaneously usable NAS clients.
            val probe = QmiModemClient(getApplication())
            try { probe.monitor(state.value.sim, includeRx = false) }
            finally { withContext(NonCancellable) { probe.close() } }
            mutable.update { it.copy(device = device, draft = qmiEmptyBandSelection(device.profile), combinedNr = false,
                cellLockState = qmiCellLockState(device, it.servingCells, recentTargets(it.sim))) }
        } catch (e: Exception) {
            mutable.update { it.copy(device = null, draft = null) }
            postStatus(error = getApplication<Application>().getString(R.string.qmi_read_failed, e.message.orEmpty()))
            throw e
        } finally { mutable.update { it.copy(busy = false) } }
    }

    fun prepareQuickLock(request: QmiQuickLockRequest) {
        if (requestedQuickId == request.id || state.value.busy) return
        try {
            request.validate()
            require(activeSubscription(request.sim) == request.subId) {
                getApplication<Application>().getString(R.string.qmi_quick_sim_changed)
            }
        } catch (e: IllegalArgumentException) { invalid(e); return }
        requestedQuickId = request.id
        pendingQuick = request
        quickSubscription = request.sim to request.subId
        refresh(request.sim)
    }

    private fun activeSubscription(sim: Int): Int? = runCatching {
        getApplication<Application>().getSystemService(SubscriptionManager::class.java)
            .getActiveSubscriptionInfoForSimSlotIndex(sim - 1)?.subscriptionId
    }.getOrNull()

    private fun requireQuickSubscription() {
        quickSubscription?.let { (sim, subId) ->
            require(state.value.sim == sim && activeSubscription(sim) == subId) {
                getApplication<Application>().getString(R.string.qmi_quick_sim_changed)
            }
        }
    }

    private fun applyQuickPrefill(request: QmiQuickLockRequest, device: QmiDeviceState) {
        requireQuickSubscription()
        val ui = state.value
        val selection = qmiQuickBandSelection(request, device.profile,
            device.hardware ?: throw IllegalArgumentException(getApplication<Application>().getString(R.string.qmi_no_hardware)),
            ui.mainlandMode, ui.mcc, ui.mnc)
        mutable.update { it.copy(quickPrefill = request,
            draft = if (request.kind == QmiQuickLockKind.BAND) selection else qmiEmptyBandSelection(device.profile),
            combinedNr = request.kind == QmiQuickLockKind.BAND && request.rat == "NR") }
        postStatus(message = getApplication<Application>().getString(R.string.qmi_quick_ready, request.sim), level = QmiNoticeLevel.INFO)
    }

    fun resetSession() {
        val sim = state.value.sim
        if (state.value.busy) return
        recentCellTargets = null
        var stoppedConflictingApp = false
        operation(sim, successMessage = {
            getApplication<Application>().getString(if (stoppedConflictingApp)
                R.string.qmi_session_conflict_recovered else R.string.qmi_session_reset_done)
        }) { client.resetSession(sim) { stoppedConflictingApp = true } }
    }

    fun edit(transform: (QmiProfile) -> QmiProfile) {
        if (state.value.busy) return
        cancelVerification(); dismissStatus()
        mutable.update { ui ->
            val draft = ui.draft?.let(transform)?.let { qmiPruneSelection(it, ui.device?.hardware, ui.mainlandMode, ui.mcc, ui.mnc) }
            ui.copy(draft = draft, combinedNr = ui.combinedNr && draft?.rats?.contains("nr") == true)
        }
    }

    fun setCombined(value: Boolean) {
        if (state.value.busy) return
        cancelVerification(); dismissStatus()
        mutable.update { ui ->
            val draft = ui.draft
            val bands = draft?.bands.orEmpty()
            val union = bands[QmiBand.NR_SA].orEmpty() + bands[QmiBand.NR_NSA].orEmpty()
            ui.copy(combinedNr = value, draft = if (value) draft?.copy(bands = bands + mapOf(QmiBand.NR_SA to union, QmiBand.NR_NSA to union)) else draft)
        }
    }

    fun selectBand(domain: QmiBand, bands: Set<Int>) = edit { draft ->
        val domains = if (state.value.combinedNr && domain.rat == "nr") listOf(QmiBand.NR_SA, QmiBand.NR_NSA) else listOf(domain)
        draft.copy(bands = draft.bands + domains.associateWith { bands })
    }

    fun apply() {
        val ui = state.value
        val device = ui.device ?: return
        if (!device.controlReady) return
        val hardware = device.hardware ?: return
        val selection = ui.draft?.let { qmiPruneSelection(it, hardware, ui.mainlandMode, ui.mcc, ui.mnc) } ?: return
        val draft = qmiSelectedBandTarget(device.profile, selection)
        val commands = try { qmiBandChanges(device.profile, draft, hardware, ui.combinedNr) }
        catch (e: IllegalArgumentException) { invalid(e); return }
        if (commands.isEmpty()) {
            if (selection.bands.values.any { it.isNotEmpty() }) beginVerification(ui.sim, selection)
            else postStatus(message = getApplication<Application>().getString(R.string.qmi_no_changes))
            return
        }
        var modeRejected = false
        operation(ui.sim, true, verifySelection = selection, warningMessage = {
            if (modeRejected) getApplication<Application>().getString(R.string.qmi_nr_mode_rejected) else null
        }) {
            val result = client.executeBands(ui.sim, commands)
            modeRejected = result.nrModeRejected
            val actual = result.device
            val mismatches = commands.mapNotNull { command ->
                when (val name = command.getString("cmd")) {
                    "rat_set" -> name.takeIf { actual.profile.rats != draft.rats }
                    "mode_set" -> name.takeIf { !modeRejected && actual.profile.nrMode != draft.nrMode }
                    "nr_set" -> name.takeIf { actual.profile.bands[QmiBand.NR_SA] != draft.bands[QmiBand.NR_SA] || actual.profile.bands[QmiBand.NR_NSA] != draft.bands[QmiBand.NR_NSA] }
                    else -> QmiBand.entries.firstOrNull { "${it.key}_set" == name }?.let { domain -> name.takeIf { actual.profile.bands[domain] != draft.bands[domain] } }
                }
            }
            check(mismatches.isEmpty()) { "Readback differs: ${mismatches.joinToString()}" }
            actual
        }
    }

    fun lock(input: QmiCellInput, lockRat: Boolean = false) {
        if (state.value.device?.controlReady != true) return
        val command = try {
            qmiCellCommand(input).also {
                if (input.mode in listOf(QmiCellMode.NR_PCI, QmiCellMode.NR_MULTI_PCI)) {
                    val ui = state.value
                    require(input.band.toInt() in qmiVisibleBands(QmiBand.NR_SA, ui.device?.hardware?.get(QmiBand.NR_SA).orEmpty(),
                        ui.mainlandMode, ui.mcc, ui.mnc)) { "Unsupported NR band for this device/operator" }
                }
            }
        } catch (e: IllegalArgumentException) { invalid(e); return }
        val ui = state.value
        val modeCommands = try { if (lockRat) qmiCellRatCommands(input, ui.device!!.profile, ui.device.nrModeSettable) else emptyList() }
        catch (e: IllegalArgumentException) { invalid(e); return }
        val restoreKey = "${ui.sim}:$networkSubId"
        operation(ui.sim, true, verifyCell = input.takeUnless { it.mode == QmiCellMode.PLMN }) {
            val before = if (lockRat) client.read(ui.sim).profile else ui.device!!.profile
            val freshModeCommands = if (lockRat) qmiCellRatCommands(input, before, ui.device!!.nrModeSettable) else modeCommands
            if (lockRat && networkSubId >= 0 && !cellRestore.contains(restoreKey)) {
                cellRestore.edit().putString(restoreKey, org.json.JSONObject()
                    .put("rats", before.rats.sorted().joinToString(",")).put("nrMode", before.nrMode).toString()).apply()
            }
            val actual = client.execute(ui.sim, listOf(command) + freshModeCommands)
            if (lockRat) check(actual.profile.rats == setOf(if (input.mode == QmiCellMode.LTE) "lte" else "nr") &&
                (input.mode == QmiCellMode.LTE || actual.profile.nrMode == "sa")) { "RAT readback differs after cell lock" }
            actual
        }
    }

    fun unlockCells() {
        val sim = state.value.sim
        val restoreKey = "$sim:$networkSubId"
        operation(sim, true) {
            val current = client.read(sim)
            val commands = qmiActiveCellClearCommands(current.locks).toMutableList()
            val clearedFamilies = commands.map { it.getString("cmd").substringBefore("_cell_lock_clear") }
            var restoredRats: Set<String>? = null
            var restoredMode: String? = null
            cellRestore.getString(restoreKey, null)?.let { saved ->
                val profile = org.json.JSONObject(saved)
                val rats = profile.getString("rats").split(',').toSet()
                require(rats.isNotEmpty() && rats.all { it in setOf("nr", "lte", "wcdma", "gsm") })
                val nrMode = profile.getString("nrMode")
                restoredRats = rats
                restoredMode = nrMode.takeIf { it in setOf("sa", "nsa", "both") }
                if (nrMode in setOf("sa", "nsa", "both") && nrMode != current.profile.nrMode)
                    commands += qmiCommand("mode_set").put("mode", nrMode)
                commands += qmiCommand("rat_set").put("rat", rats.sorted().joinToString(","))
            }
            check(commands.isNotEmpty()) { getApplication<Application>().getString(R.string.qmi_cell_no_active_lock) }
            val actual = client.execute(sim, commands)
            check(clearedFamilies.all { family -> actual.locks[if (family == "plmn_lock_clear") "plmn" else family] == "" }) {
                getApplication<Application>().getString(R.string.qmi_cell_clear_unconfirmed)
            }
            check((restoredRats == null || actual.profile.rats == restoredRats) &&
                (restoredMode == null || actual.profile.nrMode == restoredMode)) {
                getApplication<Application>().getString(R.string.qmi_unlock_readback_failed)
            }
            cellRestore.edit().remove(restoreKey).apply()
            recentCellTargets = null
            actual
        }
    }

    fun unlockBands() {
        val ui = state.value
        val device = ui.device ?: return
        if (!device.controlReady) return
        val hardware = device.hardware ?: return
        var modeRejected = false
        operation(ui.sim, true, clearSelection = true, warningMessage = {
            if (modeRejected) getApplication<Application>().getString(R.string.qmi_nr_mode_rejected) else null
        }) {
            // External tools may have changed NR mode since the page last refreshed.
            val current = client.read(ui.sim)
            val plan = qmiBandUnlockPlan(current.profile, current.hardware ?: hardware, current.nrModeSettable)
            val result = client.executeBands(ui.sim, plan.commands)
            modeRejected = result.nrModeRejected
            val actual = result.device
            check(actual.profile.rats == plan.target.rats &&
                (modeRejected || "nr" !in plan.target.rats || plan.target.nrMode == "unknown" || actual.profile.nrMode == plan.target.nrMode)) {
                getApplication<Application>().getString(R.string.qmi_unlock_readback_failed)
            }
            cellRestore.edit().remove("${ui.sim}:$networkSubId").apply()
            actual
        }
    }

    fun command(name: String) {
        require(name in setOf("attach", "detach", "reattach", "lte_cell_lock_clear", "nr_cell_lock_clear", "plmn_lock_clear"))
        if (state.value.device?.controlReady != true) return
        operation(state.value.sim, true) { client.execute(state.value.sim, listOf(qmiCommand(name))) }
    }

    fun dismissStatus() {
        ++statusGeneration; alertJob?.cancel()
        mutable.update { it.copy(error = null, message = null) }
    }

    private fun postStatus(error: String? = null, message: String? = null, level: QmiNoticeLevel = if (error != null) QmiNoticeLevel.ERROR else QmiNoticeLevel.SUCCESS) {
        alertJob?.cancel()
        val generation = ++statusGeneration
        mutable.update { it.copy(error = error, message = message, noticeLevel = level) }
        alertJob = viewModelScope.launch {
            delay(if (level == QmiNoticeLevel.ERROR || level == QmiNoticeLevel.WARNING) 7000 else 4000)
            if (generation == statusGeneration && !state.value.busy) mutable.update { it.copy(error = null, message = null) }
        }
    }

    private fun beginVerification(sim: Int, selection: QmiProfile) {
        cancelVerification()
        val check = PendingRegistration(sim, selection, System.currentTimeMillis())
        pending = check
        postStatus(message = getApplication<Application>().getString(R.string.qmi_target_waiting), level = QmiNoticeLevel.INFO)
        verificationJob = viewModelScope.launch {
            delay(20_000)
            if (pending === check) {
                pending = null
                postStatus(message = getApplication<Application>().getString(R.string.qmi_target_unconfirmed), level = QmiNoticeLevel.WARNING)
            }
        }
    }

    private fun recentTargets(sim: Int) = recentCellTargets?.takeIf { it.first == sim }?.second.orEmpty()

    private fun beginCellVerification(sim: Int, input: QmiCellInput) {
        cancelVerification()
        val check = PendingCellRegistration(sim, qmiCellTargetsFromInput(input), System.currentTimeMillis())
        recentCellTargets = sim to check.targets
        pendingCell = check
        postStatus(message = getApplication<Application>().getString(R.string.qmi_cell_waiting), level = QmiNoticeLevel.INFO)
        cellVerificationJob = viewModelScope.launch {
            delay(20_000)
            if (pendingCell === check) {
                pendingCell = null
                postStatus(message = getApplication<Application>().getString(R.string.qmi_cell_unconfirmed), level = QmiNoticeLevel.WARNING)
            }
        }
    }

    private fun checkCellVerification(cells: List<QmiServingCell>) {
        val check = pendingCell ?: return
        if (check.sim != state.value.sim) return
        val matched = qmiCellRegistrationMatches(state.value.device, check.targets, cells, check.issuedAt)
        val at = matched.maxOfOrNull { it.at } ?: run { check.firstMatchAt = 0; return }
        if (check.firstMatchAt == 0L) { check.firstMatchAt = at; return }
        if (at - check.firstMatchAt < 500) return
        cancelVerification()
        postStatus(message = getApplication<Application>().getString(R.string.qmi_cell_registered, matched.joinToString("、") { it.label }))
    }

    private fun cancelVerification() {
        verificationJob?.cancel(); pending = null
        cellVerificationJob?.cancel(); pendingCell = null
    }

    private fun invalid(e: IllegalArgumentException) {
        postStatus(error = getApplication<Application>().getString(R.string.qmi_invalid_input, e.message.orEmpty()))
    }

    private fun operation(sim: Int, writing: Boolean = false, clearSelection: Boolean = !writing,
                          verifySelection: QmiProfile? = null, successMessage: (() -> String)? = null,
                          warningMessage: (() -> String?)? = null, verifyCell: QmiCellInput? = null,
                          action: suspend () -> QmiDeviceState) {
        if (state.value.busy) return
        cancelVerification(); dismissStatus()
        mutable.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                if (writing) requireQuickSubscription()
                val device = action()
                mutable.update { it.copy(device = device,
                    cellLockState = qmiCellLockState(device, it.servingCells, recentTargets(sim)),
                    draft = if (clearSelection) qmiEmptyBandSelection(device.profile) else
                        (it.draft ?: qmiEmptyBandSelection(device.profile)).copy(rats = device.profile.rats, nrMode = device.profile.nrMode),
                    combinedNr = if (clearSelection) false else it.combinedNr) }
                pendingQuick?.takeIf { it.sim == sim }?.let { request ->
                    pendingQuick = null
                    try { applyQuickPrefill(request, device) }
                    catch (e: IllegalArgumentException) { invalid(e) }
                    return@launch
                }
                val warning = warningMessage?.invoke()
                if (warning != null) postStatus(message = warning, level = QmiNoticeLevel.WARNING)
                else if (verifyCell != null) beginCellVerification(sim, verifyCell)
                else if (verifySelection?.bands?.values?.any { it.isNotEmpty() } == true) beginVerification(sim, verifySelection)
                else postStatus(message = successMessage?.invoke() ?: getApplication<Application>().getString(if (writing) R.string.qmi_applied else R.string.qmi_loaded))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A multi-command operation can partially succeed. Show fresh state, never claim rollback.
                val actual = if (writing) runCatching { client.read(sim) }.getOrNull() else (e as? QmiReadException)?.hardware
                recentCellTargets = null
                mutable.update { it.copy(device = actual,
                    cellLockState = qmiCellLockState(actual, it.servingCells),
                    draft = it.draft ?: actual?.profile?.let(::qmiEmptyBandSelection)) }
                val protocolError = (e as? QmiCommandException) ?: (e.cause as? QmiCommandException)
                val detail = if (protocolError?.modemCode == 5)
                    getApplication<Application>().getString(R.string.qmi_clients_exhausted) + "\n" + e.message.orEmpty()
                else e.message.orEmpty()
                postStatus(error = getApplication<Application>().getString(if (writing) R.string.qmi_write_failed else R.string.qmi_read_failed, detail))
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() {
        com.nvmex.networkhelper.util.modem.SystemQmiServices.unregisterDisconnector(this)
        CoroutineScope(Dispatchers.IO).launch { withContext(NonCancellable) { client.close() } }
    }
}

private data class PendingRegistration(val sim: Int, val selection: QmiProfile, val issuedAt: Long, var firstMatchAt: Long = 0)
private data class PendingCellRegistration(val sim: Int, val targets: List<QmiCellTarget>, val issuedAt: Long, var firstMatchAt: Long = 0)
