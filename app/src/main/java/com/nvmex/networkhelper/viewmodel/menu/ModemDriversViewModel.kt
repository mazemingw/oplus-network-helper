package com.nvmex.networkhelper.viewmodel.menu

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.modem.MobileLock
import com.nvmex.networkhelper.util.modem.MobileLockStatus
import com.nvmex.networkhelper.util.modem.MobileLockTarget
import com.nvmex.networkhelper.util.modem.ModemDriver
import com.nvmex.networkhelper.util.modem.driverBackend
import com.nvmex.networkhelper.util.network.SocVendorDetector
import com.nvmex.networkhelper.util.qmi.QmiSessionRecovery
import com.nvmex.networkhelper.util.qmi.QmiQuickLockRequest
import com.nvmex.networkhelper.util.modem.strongModemCleanup
import com.nvmex.networkhelper.util.modem.SystemQmiServices
import dev.ririxi.bunnylock.modem.*
import dev.ririxi.bunnylock.shizuku.RootOrShizuku
import dev.ririxi.bunnylock.shizuku.ShizukuClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One action at a time, one backend owner. A driver change never changes modem locks. */
class ModemDriversViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("modem_drivers", 0)
    private val lockStatus = MobileLockStatus(app)
    private fun ratRestoreKey(modem: ModemState = _state.value) = "cellRat.${modem.backend}.${modem.sim}"
    fun saveCellRatRestore(mask: Long?, mode: NrMode?) {
        val key = ratRestoreKey()
        if (mask != null && !prefs.contains(key)) prefs.edit().putLong(key, mask).putString("$key.nr", mode?.name).apply()
    }
    fun cellRatRestore(): Pair<Long, NrMode?>? {
        val key = ratRestoreKey()
        if (!prefs.contains(key)) return null
        return prefs.getLong(key, 0) to NrMode.entries.firstOrNull { it.name == prefs.getString("$key.nr", null) }
    }
    fun clearCellRatRestore(modem: ModemState = _state.value) { val key = ratRestoreKey(modem); prefs.edit().remove(key).remove("$key.nr").apply() }
    private val defaultDriver = if (SocVendorDetector.isQualcomm()) ModemDriver.ORIGINAL_QMI else ModemDriver.AUTO
    private val _selected = MutableStateFlow(ModemDriver.entries.firstOrNull { it.name == prefs.getString("driver", null) } ?: defaultDriver)
    val selected = _selected.asStateFlow()
    private val _state = MutableStateFlow(ModemState())
    val controls = BunnyControlPresentation()
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private var sessionScope: CoroutineScope? = null
    private var controller: ModemController? = null
    private var entrySim: Int? = null
    private var noticeJob: Job? = null
    private var lastCleanup = ""
    val systemRecovery = SystemQmiServices.state
    private val _systemDialog = MutableStateFlow(false)
    val systemDialog = _systemDialog.asStateFlow()
    private val _systemReason = MutableStateFlow("")
    val systemReason = _systemReason.asStateFlow()
    private val _recoveryProgress = MutableStateFlow("")
    val recoveryProgress = _recoveryProgress.asStateFlow()
    private val _capacityFailure = MutableStateFlow(false)
    val capacityFailure = _capacityFailure.asStateFlow()
    init { SystemQmiServices.registerDisconnector(this) { stop() } }
    private val stopWatchingShizuku = ShizukuClient.onBinderReceived {
        if (_selected.value.isBunny && _state.value.root is RootState.Denied) refresh()
    }

    fun dismiss() { _notice.value = null }

    private fun show(text: String, error: Boolean = false) {
        noticeJob?.cancel()
        _failed.value = error
        _notice.value = text
        noticeJob = viewModelScope.launch { delay(if (error) 8000 else 5000); dismiss() }
    }

    private suspend fun stop() {
        val oldController = controller
        controller = null
        sessionScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        sessionScope = null
        withContext(Dispatchers.IO) { oldController?.close() }
        _state.value = _state.value.copy(root = RootState.Denied("后端服务已释放"), refreshing = false)
        controls.observe(_state.value)
    }

    private suspend fun open(): ModemController {
        controller?.takeIf { it.helperRunning && it.state.value.root == RootState.Granted }?.let { return it }
        stop()
        val app = getApplication<Application>()
        val child = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        sessionScope = child
        val next = ModemController(app, child, RootOrShizuku(app) { show("后端服务已断开，请重新连接", true) },
            onRefreshed = ::recordLocks)
        controller = next
        child.launch { next.state.collect { _state.value = it; controls.observe(it) } }
        child.launch { next.events.collect { show(it, true) } }
        next.connectAndWait()
        val connected = next.state.value
        check(connected.root == RootState.Granted) { (connected.root as? RootState.Denied)?.reason ?: "后端未连接" }
        if (_selected.value.isBunny) {
            val backend = driverBackend(_selected.value, connected.autoBackend)
            if (backend != null && connected.backend != backend) next.selectBackendAndRefresh(backend)
        }
        return next
    }

    private fun action(block: suspend () -> Unit) { tryAction(null, block) }

    private fun tryAction(onComplete: ((Boolean) -> Unit)?, block: suspend () -> Unit): Boolean {
        if (_busy.value) return false
        _busy.value = true
        viewModelScope.launch {
            var succeeded = false
            try { block(); succeeded = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (_systemDialog.value) _systemReason.value += "\n${e.message ?: "系统组件操作失败"}"
                show(e.message ?: "后端操作失败", true)
            }
            finally { _busy.value = false; onComplete?.invoke(succeeded) }
        }
        return true
    }

    fun select(driver: ModemDriver, releaseLegacy: suspend () -> Unit, nativeReady: suspend () -> Unit) = action {
        if (driver == ModemDriver.ORIGINAL_QMI) check(SocVendorDetector.isQualcomm()) { "当前设备不支持原有高通 QMI 驱动" }
        releaseLegacy()
        stop()
        _selected.value = driver
        prefs.edit().putString("driver", driver.name).apply()
        if (driver.isBunny) refreshSelectedSim(open()) else nativeReady()
    }

    fun connect(sim: Int?, releaseLegacy: suspend () -> Unit) = action {
        entrySim = sim
        releaseLegacy()
        refreshSelectedSim(open())
    }

    fun refresh() = action {
        val modem = open()
        if (!refreshSelectedSim(modem)) modem.refreshNow()
    }

    private suspend fun refreshSelectedSim(modem: ModemController): Boolean {
        val sim = entrySim ?: return false
        entrySim = null
        if (modem.state.value.sim == sim || modem.state.value.sims.none { it.slot == sim - 1 }) return false
        modem.setSimAndRefresh(sim)
        return true
    }

    fun validateQuick(request: QmiQuickLockRequest): QmiQuickLockRequest? = try {
        request.validate()
        val subscriptions = getApplication<Application>().getSystemService(android.telephony.SubscriptionManager::class.java)
        check(subscriptions.getActiveSubscriptionInfoForSimSlotIndex(request.sim - 1)?.subscriptionId == request.subId) { "SIM 已变更，请重新选择目标" }
        show("已填入 SIM ${request.sim}，点击锁定即可执行")
        request
    } catch (e: Exception) { show(e.message ?: "目标不可用", true); null }
    fun prepareQuickControl(request: QmiQuickLockRequest) {
        val target = validateQuick(request) ?: return
        try { controls.prepareQuick(target) }
        catch (e: IllegalArgumentException) { show(e.message ?: "当前后端不支持目标", true) }
    }
    fun sim(sim: Int) = action {
        val modem = open()
        modem.setSimAndRefresh(sim)
    }
    private fun ModemState.lockTarget() = sims.firstOrNull { it.slot == sim - 1 }
        ?.let { MobileLockTarget(sim, it.subId) }?.takeIf { it.isActive(getApplication()) }

    private fun recordLocks(modem: ModemState) {
        val target = modem.lockTarget() ?: return
        lockStatus.record(target, mapOf(
            MobileLock.BANDS to modem.groups.takeIf { it.isNotEmpty() }?.any { it.locked },
            MobileLock.LTE_CELL to (modem.lteLock != null), MobileLock.NR_CELL to (modem.nrLock != null),
            MobileLock.LEGACY_CELL to (modem.legacyCellLock != null), MobileLock.PLMN to modem.anyPlmnLock,
            MobileLock.MODE to false.takeIf { modem.netMode == NetMode.AUTO && modem.nrMode !in setOf(NrMode.SA, NrMode.NSA) }
        ))
    }

    internal fun recordLockAttempt(modem: ModemState, lock: MobileLock) {
        modem.lockTarget()?.let { lockStatus.record(it, mapOf(lock to true)) }
    }

    fun execute(resetSelection: Boolean = false, trackNetworkMode: Boolean = false,
                block: suspend ModemController.() -> String?) {
        action {
            val modem = open()
            val before = modem.state.value
            val target = before.lockTarget()
            var completed = false
            val result = try { modem.block().also { completed = true } } finally {
                modem.refreshNow()
                controls.observe(modem.state.value)
                controls.syncDraft(resetSelection && completed)
                val after = modem.state.value
                if (trackNetworkMode && modem.lastRefreshFailure == null && target != null && after.lockTarget() == target) {
                    val restricted = if (after.nrMode in setOf(NrMode.SA, NrMode.NSA)) true
                        else after.allowedMask?.let { after.netMode != NetMode.AUTO }
                    if (after.allowedMask != before.allowedMask || after.nrMode != before.nrMode || completed) {
                        lockStatus.record(target, mapOf(MobileLock.MODE to restricted))
                    }
                }
            }
            // A failed write is never replayed. Readback is separate from the returned acknowledgement.
            modem.lastRefreshFailure?.let { error(it) }
            if (!result.isNullOrBlank()) show(result)
        }
    }

    internal fun recoverLocks(target: MobileLockTarget, releaseNative: suspend () -> Unit,
                              onComplete: (Boolean) -> Unit): Boolean {
        if (!_selected.value.isBunny || SystemQmiServices.channelsBlocked.value) return false
        return tryAction(onComplete) {
            val app = getApplication<Application>()
            fun requireTarget() = check(target.isDefaultData(app)) { app.getString(R.string.qmi_quick_sim_changed) }
            requireTarget()
            releaseNative()
            val saved = lockStatus.read(target)
            val modem = open()
            if (modem.state.value.sim != target.sim) modem.setSimAndRefresh(target.sim) else modem.refreshNow()
            modem.lastRefreshFailure?.let { error(it) }
            check(modem.helperRunning && modem.state.value.lockTarget() == target) { app.getString(R.string.qmi_quick_sim_changed) }
            val current = modem.state.value
            val restoreAutomatic = current.anyBandLock || MobileLock.MODE in saved || prefs.contains(ratRestoreKey(current))
            try {
                if (current.anyCellLock) { requireTarget(); modem.unlockCells() }
                if (current.anyPlmnLock) { requireTarget(); modem.unlockPlmn() }
                current.groups.filter { it.locked }.forEach { group ->
                    requireTarget(); modem.unlockBands(group.group)
                }
                if (restoreAutomatic) {
                    if (current.supportsNrMode && current.nrMode in setOf(NrMode.SA, NrMode.NSA)) {
                        requireTarget(); modem.setNrMode(NrMode.BOTH)
                    }
                    requireTarget()
                    modem.setNetMode(NetMode.AUTO)
                }
            } finally {
                modem.refreshNow()
                controls.observe(modem.state.value)
            }
            modem.lastRefreshFailure?.let { error(it) }
            val actual = modem.state.value
            check(modem.helperRunning && actual.lockTarget() == target && !actual.anyCellLock && !actual.anyPlmnLock &&
                !actual.anyBandLock && (MobileLock.BANDS !in saved || actual.groups.isNotEmpty()) &&
                (!restoreAutomatic || (actual.netMode in setOf(NetMode.AUTO, null) &&
                    (!current.supportsNrMode || actual.nrMode !in setOf(NrMode.SA, NrMode.NSA))))) {
                app.getString(R.string.qmi_unlock_readback_failed)
            }
            lockStatus.record(target, mapOf(MobileLock.MODE to false))
            clearCellRatRestore(actual)
            controls.clearTargets()
            controls.syncDraft(clearSelection = true)
        }
    }

    fun setNode(node: String) { controller?.setAtNode(node) }
    fun persist(enabled: Boolean) { controller?.setPersist(enabled) }

    fun serviceAction(releaseLegacy: suspend () -> Unit, nativeReady: suspend () -> Unit,
                      block: suspend ModemController.() -> String?) = action {
        val native = !_selected.value.isBunny
        if (native) releaseLegacy()
        try {
            val result = open().block()
            if (!result.isNullOrBlank()) show(result)
        } finally {
            if (native) { stop(); nativeReady() }
        }
    }

    private fun capacityError(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        (it as? com.nvmex.networkhelper.util.qmi.QmiCommandException)?.modemCode == 5 ||
            Regex("(?i)(QMI(?:\\s+error)?[ :]*5\\b|ClientIdsExhausted|[（(]0x0*5[)）])").containsMatchIn(it.message.orEmpty())
    }

    private suspend fun reconnectAndRead(nativeReady: suspend () -> Unit) {
        if (_selected.value.isBunny) {
            // Recovery callers have released the old controller. Opening the new one reads the selected backend.
            val modem = open()
            check(modem.helperRunning) { "后端未连接" }
            modem.lastRefreshFailure?.let { error(it) }
            controls.observe(modem.state.value)
        } else nativeReady()
        SystemQmiServices.allowChannels()
    }

    private suspend fun offerSystemRecovery(error: Throwable) {
        _capacityFailure.value = com.nvmex.networkhelper.util.network.SocVendorDetector.isQualcomm() && capacityError(error)
        _systemReason.value = "普通清理后仍无法使用：${error.message.orEmpty()}"
        _systemDialog.value = true
        try { SystemQmiServices.inspect() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _systemReason.value += "\n无法读取系统组件：${e.message}" }
    }

    fun clearChannels(releaseLegacy: suspend () -> Unit, nativeReady: suspend () -> Unit) = action {
        val access = _state.value.access
        try {
            releaseLegacy()
            stop()
            var cleanupMessage = "已成功释放后端服务"
            SystemQmiServices.releaseAll()
            try {
                if (access != Access.SHIZUKU_ADB) cleanupMessage = strongModemCleanup(getApplication()) { lastCleanup = it }
                else lastCleanup = "Shizuku ADB: released only this app's services"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { lastCleanup += "\n${e.message}" }

            _systemDialog.value = false
            try {
                reconnectAndRead(nativeReady)
                show(cleanupMessage)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                releaseLegacy(); stop()
                offerSystemRecovery(e)
                show("通道重连失败，请检查系统组件", true)
            }
        } finally {
            SystemQmiServices.allowChannels()
        }
    }

    fun showSystemComponents() = action {
        _systemReason.value = "选择组件前请查看作用与暂停影响。"
        _capacityFailure.value = false
        _systemDialog.value = true
        SystemQmiServices.inspect()
    }
    fun dismissSystemComponents() { if (!_busy.value) _systemDialog.value = false }
    fun inspectSystemComponents() = action { SystemQmiServices.inspect() }
    fun decideForMe(value: Boolean) = SystemQmiServices.setDecideForMe(value)
    fun autoRestore(value: Boolean) = action { SystemQmiServices.setAutoRestore(value) }

    fun pauseAndRetry(services: List<String>, releaseLegacy: suspend () -> Unit, nativeReady: suspend () -> Unit) = action {
        require(services.isNotEmpty()) { "请选择需要暂停的组件" }
        try {
            for (service in services.distinct()) {
                releaseLegacy(); stop()
                _recoveryProgress.value = "暂停 $service，随后验证实际可用的客户端…"
                SystemQmiServices.pause(service)
                _systemDialog.value = false
                try {
                    // init's stopped state can precede the modem releasing its client table.
                    var verified = false
                    repeat(3) { attempt ->
                        if (!verified) {
                            try { reconnectAndRead(nativeReady); verified = true }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (!capacityError(e) || attempt == 2) throw e
                                releaseLegacy(); stop()
                                delay(500)
                            }
                        }
                    }
                    _systemReason.value = "已成功释放后端服务，并完成读取验证。未继续暂停其余组件。"
                    _capacityFailure.value = false
                    show("已成功释放后端服务")
                    return@action
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    releaseLegacy(); stop()
                    _systemReason.value = "暂停 $service 后仍无法读取：${e.message}"
                    _capacityFailure.value = capacityError(e)
                    // Only confirmed capacity exhaustion justifies trying the next selected service.
                    if (!_capacityFailure.value) throw e
                }
            }
            _systemDialog.value = true
            show("所选组件已暂停，但客户端验证仍失败；可恢复组件或查看诊断", true)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            _systemDialog.value = true
            throw e
        } finally {
            _recoveryProgress.value = ""
            SystemQmiServices.allowChannels()
            if (_systemDialog.value) SystemQmiServices.inspect()
        }
    }

    fun resumeSystemComponent(service: String?, releaseLegacy: suspend () -> Unit, nativeReady: suspend () -> Unit) = action {
        try {
            _capacityFailure.value = false
            releaseLegacy(); stop()
            SystemQmiServices.resume(service)
            _systemReason.value = "${service ?: "助手暂停的组件"}已恢复。"
            try { reconnectAndRead(nativeReady) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _systemReason.value += "\n恢复后通道可能再次不足：${e.message}" }
            show("组件已恢复；读取结果见面板")
        } finally { SystemQmiServices.allowChannels() }
    }

    fun diagnostics(): String = controller?.diagnostics("欧加网络助手 · 后端诊断",
        listOf("Driver: ${_selected.value.title}", "Shizuku: ${ShizukuClient.describe(getApplication())}", "Channel cleanup: $lastCleanup"))
        ?.plus("\nSystem recovery:\n${systemRecovery.value.diagnostics}")
        ?: "Driver: ${_selected.value.title}\nDevice: ${_state.value.device}\nChannel cleanup: $lastCleanup\nSystem recovery:\n${systemRecovery.value.diagnostics}\n后端尚未连接\n${_state.value.log.joinToString("\n")}"

    override fun onCleared() {
        SystemQmiServices.unregisterDisconnector(this)
        stopWatchingShizuku()
        val current = controller
        sessionScope?.cancel()
        CoroutineScope(Dispatchers.IO).launch { current?.close() }
    }
}
