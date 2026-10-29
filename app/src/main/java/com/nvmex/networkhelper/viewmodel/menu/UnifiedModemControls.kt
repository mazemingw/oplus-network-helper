package com.nvmex.networkhelper.viewmodel.menu

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.qmi.*
import com.nvmex.networkhelper.util.modem.parseModemPcis
import dev.ririxi.bunnylock.modem.*
import dev.ririxi.bunnylock.data.Rat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Backend adapter only. Both drivers render the original band/cell controls. */
class BunnyControlPresentation {
    private val mutable = MutableStateFlow(QmiBandUiState())
    val state = mutable.asStateFlow()
    private var modem = ModemState()
    private var identity: Pair<Int, Backend?>? = null
    private var network: NetworkPanelUiState? = null
    private var targets = emptyList<QmiCellTarget>()

    fun observe(value: ModemState) {
        modem = value
        val key = value.sim to value.backend
        val changed = identity != key
        if (changed) { identity = key; targets = emptyList(); network = null }
        val groups = QmiBand.entries.associateWith { domain ->
            value.groups.firstOrNull { it.group == domain.group() }
                ?: value.groups.firstOrNull { domain.rat == "nr" && it.group == Group.NR }
        }
        val hardware = groups.mapValues { it.value?.supported.orEmpty().toSet() }
        val mask = value.allowedMask ?: (Masks.G2 or Masks.G3 or Masks.G4 or Masks.G5)
        val profile = QmiProfile(ratsFor(mask), groups.mapValues { it.value?.allowed.orEmpty() },
            when (value.nrMode) { NrMode.SA -> "sa"; NrMode.NSA -> "nsa"; NrMode.BOTH -> "both"; null -> "unknown" })
        val device = QmiDeviceState(value.sim, profile, hardware,
            mapOf("lte" to value.lteLock.orEmpty(), "nr" to value.nrLock.orEmpty(), "plmn" to value.plmnLock.orEmpty()),
            controlReady = value.root == RootState.Granted, nrModeSettable = value.supportsNrMode)
        val plmn = value.homePlmn ?: value.registeredPlmn.orEmpty()
        mutable.update { old -> old.copy(sim = value.sim, device = device,
            draft = if (changed || old.draft == null || old.device?.hardware?.values?.all { it.isEmpty() } == true) qmiEmptyBandSelection(profile) else old.draft,
            combinedNr = value.groups.any { it.group == Group.NR } || (!changed && old.combinedNr),
            mcc = network?.mcc?.takeIf { it.isNotBlank() } ?: plmn.take(3),
            mnc = network?.mnc?.takeIf { it.isNotBlank() } ?: plmn.drop(3),
            servingCells = qmiServingCells(network, System.currentTimeMillis()),
            activeBands = QmiBand.entries.associateWith(qmiObserveBands(network, value.sim - 1, System.currentTimeMillis())::highlighted),
            cellLockState = qmiCellLockState(device, qmiServingCells(network, System.currentTimeMillis()), targets)) }
    }

    fun updateNetwork(sim: Int, value: NetworkPanelUiState?) {
        if (sim != modem.sim) return
        network = value
        observe(modem)
    }
    fun configureMainlandMode(enabled: Boolean) { mutable.update { it.copy(mainlandMode = enabled) }; edit { it } }
    fun edit(transform: (QmiProfile) -> QmiProfile) {
        mutable.update { ui -> ui.copy(draft = ui.draft?.let(transform)?.let {
            qmiPruneSelection(it, ui.device?.hardware, ui.mainlandMode, ui.mcc, ui.mnc)
        }) }
    }
    fun setCombined(enabled: Boolean) {
        val forced = modem.groups.any { it.group == Group.NR }
        mutable.update { it.copy(combinedNr = enabled || forced) }
        if (enabled || forced) edit { draft ->
            val union = draft.bands[QmiBand.NR_SA].orEmpty() + draft.bands[QmiBand.NR_NSA].orEmpty()
            draft.copy(bands = draft.bands + mapOf(QmiBand.NR_SA to union, QmiBand.NR_NSA to union))
        }
    }
    fun selectBand(domain: QmiBand, bands: Set<Int>) = edit { draft ->
        val domains = if (state.value.combinedNr && domain.rat == "nr") listOf(QmiBand.NR_SA, QmiBand.NR_NSA) else listOf(domain)
        draft.copy(bands = draft.bands + domains.associateWith { bands })
    }
    fun rememberTargets(value: List<QmiCellTarget>) { targets = value }
    fun clearTargets() { targets = emptyList() }
    fun syncDraft(clearSelection: Boolean) {
        mutable.update { ui ->
            val profile = ui.device?.profile ?: return@update ui
            ui.copy(draft = if (clearSelection) qmiEmptyBandSelection(profile) else
                (ui.draft ?: qmiEmptyBandSelection(profile)).copy(rats = profile.rats, nrMode = profile.nrMode))
        }
    }
    fun prepareQuick(request: QmiQuickLockRequest) {
        if (request.kind == QmiQuickLockKind.BAND) {
            val ui = state.value
            val selected = qmiQuickBandSelection(request, ui.device!!.profile, ui.device.hardware!!, ui.mainlandMode, ui.mcc, ui.mnc)
            mutable.update { it.copy(draft = selected, combinedNr = request.rat == "NR", quickPrefill = request) }
        } else mutable.update { it.copy(quickPrefill = request) }
    }
}

private fun QmiBand.group() = when (this) {
    QmiBand.LTE -> Group.LTE; QmiBand.NR_SA -> Group.NR_SA; QmiBand.NR_NSA -> Group.NR_NSA
    QmiBand.WCDMA -> Group.WCDMA; QmiBand.GSM -> Group.GSM
}
private fun ratsFor(mask: Long) = buildSet {
    if (mask and Masks.G2 != 0L) add("gsm"); if (mask and Masks.G3 != 0L) add("wcdma")
    if (mask and Masks.G4 != 0L) add("lte"); if (mask and Masks.G5 != 0L) add("nr")
}
private fun QmiProfile.mask() = rats.fold(0L) { mask, rat -> mask or when (rat) {
    "nr" -> Masks.G5; "lte" -> Masks.G4; "wcdma" -> Masks.G3; "gsm" -> Masks.G2; else -> 0L
} }

class ModemControlActions(private val legacy: QmiBandLockViewModel, private val drivers: ModemDriversViewModel, val bunny: Boolean) {
    private val presentation get() = drivers.controls
    fun refresh(sim: Int = if (bunny) drivers.state.value.sim else legacy.state.value.sim) {
        if (bunny) { if (sim != drivers.state.value.sim) drivers.sim(sim) else drivers.refresh() } else legacy.refresh(sim)
    }
    fun configureMainlandMode(value: Boolean) { if (bunny) presentation.configureMainlandMode(value) else legacy.configureMainlandMode(value) }
    fun edit(change: (QmiProfile) -> QmiProfile) { if (bunny) presentation.edit(change) else legacy.edit(change) }
    fun setCombined(value: Boolean) { if (bunny) presentation.setCombined(value) else legacy.setCombined(value) }
    fun selectBand(domain: QmiBand, bands: Set<Int>) { if (bunny) presentation.selectBand(domain, bands) else legacy.selectBand(domain, bands) }
    fun updateNetwork(sim: Int, value: NetworkPanelUiState?) { if (bunny) presentation.updateNetwork(sim, value) else legacy.updateNetwork(sim, value) }
    fun dismissStatus() { legacy.dismissStatus(); drivers.dismiss() }
    fun prepareQuickLock(request: QmiQuickLockRequest) {
        if (bunny) drivers.prepareQuickControl(request) else legacy.prepareQuickLock(request)
    }
    fun apply() {
        if (!bunny) return legacy.apply()
        val ui = presentation.state.value
        val draft = ui.draft ?: return
        drivers.execute {
            require(draft.rats.isNotEmpty()) { "请至少选择一种网络制式" }
            val messages = mutableListOf<String>()
            state.value.groups.forEach { group ->
                val domains = if (group.group == Group.NR) listOf(QmiBand.NR_SA, QmiBand.NR_NSA)
                    else QmiBand.entries.filter { it.group() == group.group }
                val chosen = domains.filter { it.rat in draft.rats }.flatMap { draft.bands[it].orEmpty() }.toSet()
                if (chosen.isNotEmpty()) messages += lockBands(group.group, chosen)
            }
            if (state.value.supportsNrMode && "nr" in draft.rats) messages += setNrMode(when (draft.nrMode) {
                "sa" -> NrMode.SA; "nsa" -> NrMode.NSA; else -> NrMode.BOTH
            })
            messages += setNetMode(NetMode.AUTO, draft.mask(), verbatim(draft.rats.joinToString(" + ").uppercase()))
            messages.joinToString("\n")
        }
    }
    fun unlockBands() {
        if (!bunny) return legacy.unlockBands()
        drivers.execute(resetSelection = true) {
            state.value.groups.forEach { unlockBands(it.group) }
            if (state.value.supportsNrMode) setNrMode(NrMode.BOTH)
            setNetMode(NetMode.AUTO)
        }
    }
    fun lock(input: QmiCellInput, lockRat: Boolean = false) {
        if (!bunny) return legacy.lock(input, lockRat)
        drivers.execute {
            if (input.mode == QmiCellMode.PLMN) {
                val numeric = input.plmn.trim()
                require(numeric.matches(Regex("[0-9]{5,6}"))) { "PLMN 需为 MCC + MNC" }
                return@execute lockPlmn(numeric.take(3), numeric.drop(3))
            }
            require(input.mode != QmiCellMode.NR_GNB) { "此后端不支持 gNB 白名单" }
            val nr = input.mode != QmiCellMode.LTE
            val pcis = if (!nr || input.mode != QmiCellMode.NR_ARFCN) parseModemPcis(input.pcis, nr) else emptyList()
            require(!state.value.mtkAtCellLock || pcis.size <= 1) { "当前 AT 后端仅支持单个 PCI" }
            require(state.value.supportsPciLock || pcis.isEmpty()) { "此后端仅支持频点锁定" }
            val channel = requireNotNull(input.channel.toLongOrNull()) { "请输入有效频点" }
            require(channel in (if (nr) 1L..3279165L else 0L..262143L)) { "频点超出范围" }
            val targets = if (state.value.supportsPciLock || nr) qmiCellTargetsFromInput(input)
                else listOf(QmiCellTarget("LTE", channel.toInt()))
            if (!state.value.supportsPciLock && nr) require(input.band.toIntOrNull() in 1..512) { "请输入 NR 频段" }
            val request = CellLockRequest(if (nr) LockRat.NR else LockRat.LTE,
                if (nr) null else channel, if (nr) emptyList() else pcis,
                if (nr) channel else null, if (nr) pcis else emptyList(), input.band.toIntOrNull(), input.scs)
            val result = lockCells(request)
            // Frequency-only backends must never pretend to have locked a selected PCI.
            presentation.rememberTargets(targets)
            if (lockRat) {
                drivers.saveCellRatRestore(state.value.allowedMask, state.value.nrMode)
                if (nr && state.value.supportsNrMode) setNrMode(NrMode.SA)
                setNetMode(if (nr) NetMode.NR_ONLY else NetMode.LTE_ONLY)
            }
            result
        }
    }
    fun unlockCells() {
        if (!bunny) return legacy.unlockCells()
        drivers.execute {
            val result = unlockCells()
            if (state.value.anyPlmnLock) unlockPlmn()
            drivers.cellRatRestore()?.let { (mask, mode) ->
                if (state.value.supportsNrMode && mode != null) setNrMode(mode)
                setNetMode(NetMode.AUTO, mask, verbatim("原有网络制式"))
                drivers.clearCellRatRestore()
            }
            presentation.clearTargets()
            result
        }
    }
    fun command(name: String) {
        if (!bunny) legacy.command(name)
        else drivers.execute { require(name == "reattach"); radioCycle() }
    }
}
