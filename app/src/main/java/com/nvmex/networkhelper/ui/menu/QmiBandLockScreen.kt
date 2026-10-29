package com.nvmex.networkhelper.ui.menu

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.nvmex.networkhelper.ui.network.sections.NetworkSignalProgressBar
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.network.SocVendorDetector
import com.nvmex.networkhelper.util.qmi.*
import com.nvmex.networkhelper.viewmodel.menu.QmiBandLockViewModel
import com.nvmex.networkhelper.viewmodel.menu.QmiBandUiState
import com.nvmex.networkhelper.viewmodel.menu.QmiNoticeLevel
import com.nvmex.networkhelper.viewmodel.signal.NetworkPanelMultiSimViewModel
import com.nvmex.networkhelper.ui.settings.rememberAppSettings
import com.nvmex.networkhelper.viewmodel.menu.ModemDriversViewModel
import com.nvmex.networkhelper.viewmodel.menu.ModemControlActions
import dev.ririxi.bunnylock.modem.RootState
import dev.ririxi.bunnylock.modem.NetMode
import dev.ririxi.bunnylock.modem.NrMode
import dev.ririxi.bunnylock.modem.LockRat

private val lockControlShape = RoundedCornerShape(4.dp)
private val cellInputSaver = listSaver<QmiCellInput, String>(
    save = { listOf(it.mode.name, it.channel, it.pcis, it.band, it.scs.toString(), it.gnbBits, it.gnbIds, it.plmn) },
    restore = { QmiCellInput(QmiCellMode.valueOf(it[0]), it[1], it[2], it[3], it[4].toInt(), it[5], it[6], it[7]) }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QmiBandLockScreen(onBack: () -> Unit, legacyVm: QmiBandLockViewModel = viewModel(),
                     networkVm: NetworkPanelMultiSimViewModel = viewModel(), quickRequest: QmiQuickLockRequest? = null,
                     drivers: ModemDriversViewModel = viewModel()) {
    val legacyUi by legacyVm.state.collectAsState()
    val bunnyUi by drivers.controls.state.collectAsState()
    val supported = remember { SocVendorDetector.isQualcomm() }
    val driver by drivers.selected.collectAsState()
    val bunnyState by drivers.state.collectAsState()
    val driverBusy by drivers.busy.collectAsState()
    val driverNotice by drivers.notice.collectAsState()
    val driverFailed by drivers.failed.collectAsState()
    val channelsBlocked by com.nvmex.networkhelper.util.modem.SystemQmiServices.channelsBlocked.collectAsState()
    val bunny = driver.isBunny
    val ui = if (bunny) bunnyUi else legacyUi
    val vm = remember(legacyVm, drivers, bunny) { ModemControlActions(legacyVm, drivers, bunny) }
    val allBusy = ui.busy || driverBusy || channelsBlocked || (bunny && bunnyState.refreshing)
    var legacyMonitoring by rememberSaveable { mutableStateOf(true) }
    val releaseLegacy: suspend () -> Unit = {
        legacyMonitoring = false
        networkVm.stopQmiMonitoring()
        legacyVm.releaseDriver()
    }
    val nativeReady: suspend () -> Unit = {
        legacyVm.refreshForRecovery()
        legacyMonitoring = true
    }
    SystemQmiRecoveryDialog(drivers, releaseLegacy, nativeReady)
    val (settings, setSettings) = rememberAppSettings()
    val pager = rememberPagerState(initialPage = if (quickRequest?.kind == QmiQuickLockKind.CELL) 1 else 0, pageCount = { 3 })
    val tab = pager.currentPage
    val scope = rememberCoroutineScope()
    var dockHeightPx by remember { mutableIntStateOf(0) }
    val dockHeight = with(LocalDensity.current) { dockHeightPx.toDp() }
    var cell by rememberSaveable(ui.sim, stateSaver = cellInputSaver) { mutableStateOf(QmiCellInput()) }
    var consumedQuickId by rememberSaveable(driver) { mutableStateOf<String?>(null) }
    var pendingCell by remember(ui.sim) { mutableStateOf<QmiCellInput?>(null) }
    LaunchedEffect(Unit) {
        com.nvmex.networkhelper.util.modem.SystemQmiServices.channelsBlocked.first { !it }
        if (bunny) drivers.select(driver, releaseLegacy, nativeReady)
    }
    LaunchedEffect(bunny, bunnyState.root, bunnyState.sim, bunnyState.groups, driverBusy, quickRequest?.id) {
        val request = quickRequest
        if (bunny && !driverBusy && bunnyState.root == RootState.Granted && bunnyState.groups.isNotEmpty() && request != null && request.id != consumedQuickId) {
            if (bunnyState.sim != request.sim) drivers.sim(request.sim)
            else vm.prepareQuickLock(request)
        }
    }
    ObserveBandNetwork(ui.sim, vm, networkVm, legacyMonitoring && !driverBusy)
    LaunchedEffect(driver, bunnyState.supportsPciLock) {
        if (bunny && !bunnyState.supportsPciLock) cell = cell.copy(
            mode = if (cell.mode in setOf(QmiCellMode.NR_PCI, QmiCellMode.NR_MULTI_PCI)) QmiCellMode.NR_ARFCN else cell.mode, pcis = "")
    }
    pendingCell?.let { input ->
        AlertDialog(onDismissRequest = { pendingCell = null },
            title = { Text(stringResource(R.string.qmi_cell_rat_title)) },
            text = { Text(stringResource(if (input.mode == QmiCellMode.LTE) R.string.qmi_cell_rat_lte else R.string.qmi_cell_rat_nr)) },
            confirmButton = { TextButton(shape = lockControlShape, onClick = { pendingCell = null; vm.lock(input, lockRat = true) }) {
                Text(stringResource(R.string.qmi_cell_rat_together)) } },
            dismissButton = { TextButton(shape = lockControlShape, onClick = { pendingCell = null; vm.lock(input) }) {
                Text(stringResource(R.string.qmi_cell_rat_only)) } })
    }
    LaunchedEffect(settings.mainlandBandMode, bunny) { vm.configureMainlandMode(settings.mainlandBandMode) }
    LaunchedEffect(quickRequest?.id, bunny, channelsBlocked) {
        if (supported && !bunny && !channelsBlocked && !driverBusy) {
            if (quickRequest != null) vm.prepareQuickLock(quickRequest)
            else if (ui.device == null) vm.refresh()
        }
    }
    LaunchedEffect(ui.quickPrefill?.id) {
        ui.quickPrefill?.takeIf { it.id != consumedQuickId }?.let { request ->
            pager.scrollToPage(if (request.kind == QmiQuickLockKind.CELL) 1 else 0)
            if (request.kind == QmiQuickLockKind.CELL) cell = request.cellInput()
            consumedQuickId = request.id
        }
    }
    LaunchedEffect(ui.busy, ui.device) {
        if (!bunny && !ui.busy && ui.device?.controlReady == true) networkVm.retryQmiMonitoring()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
    Column(Modifier.fillMaxSize()) {
    Column(Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.qmi_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(shape = lockControlShape, onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        if (!supported && !bunny) {
            Text(stringResource(R.string.qmi_unsupported), style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..2).forEach { sim -> FilterChip(shape = lockControlShape, selected = (if (bunny) bunnyState.sim else ui.sim) == sim,
                enabled = !allBusy && (!bunny || bunnyState.sims.any { it.slot == sim - 1 }),
                onClick = { if (bunny) drivers.sim(sim) else if (ui.sim != sim) vm.refresh(sim) }, label = { Text("SIM $sim") }) }
            AssistChip(shape = lockControlShape, onClick = {
                if (bunny) drivers.refresh() else { legacyMonitoring = true; legacyVm.refresh() }
            }, enabled = !allBusy,
                label = { Text(stringResource(R.string.qmi_read)) })
            AssistChip(shape = lockControlShape, onClick = { drivers.clearChannels(releaseLegacy, nativeReady) }, enabled = !allBusy,
                label = { Text("清理通道") })
        }
        TabRow(selectedTabIndex = tab) {
            listOf(stringResource(R.string.qmi_bands), stringResource(R.string.qmi_cells), "驱动&设置").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(label) })
            }
        }
    }
    HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) { page ->
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, bottom = if (page == 2) 100.dp else dockHeight + 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (page == 2) {
            ModemDriverSettings(drivers, !allBusy, releaseLegacy, nativeReady)
        } else {
        val device = ui.device
        val draft = ui.draft
        if (device != null && draft != null) {
            val enabled = !allBusy && device.controlReady
            if (page == 0) {
                QmiSection(stringResource(R.string.qmi_rats)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("nr" to "NR", "lte" to "LTE", "wcdma" to "WCDMA", "gsm" to "GSM")
                            .filter { (rat, _) -> !bunny || (rat != "nr" || bunnyState.supports5g) &&
                                device.hardware?.any { it.key.rat == rat && it.value.isNotEmpty() } == true }.forEach { (key, label) ->
                            FilterChip(shape = lockControlShape, selected = key in draft.rats, enabled = enabled,
                                onClick = { vm.edit { it.copy(rats = if (key in it.rats) it.rats - key else it.rats + key) } },
                                colors = lockSelectionColors(),
                                leadingIcon = if (key in draft.rats) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                                label = { Text(label) })
                        }
                    }
                    if ("nr" in draft.rats && (!bunny || bunnyState.supportsNrMode)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("both" to R.string.qmi_nr_both, "sa" to R.string.qmi_nr_sa, "nsa" to R.string.qmi_nr_nsa).forEach { (mode, label) ->
                                FilterChip(shape = lockControlShape, selected = draft.nrMode == mode,
                                    enabled = enabled && device.nrModeSettable,
                                    onClick = { vm.edit { it.copy(nrMode = mode) } }, colors = lockSelectionColors(),
                                    leadingIcon = if (draft.nrMode == mode) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                                    label = { Text(stringResource(label)) })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if ("nr" in draft.rats && (!bunny || bunnyState.groups.none { it.group == dev.ririxi.bunnylock.modem.Group.NR })) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.qmi_combined_nr_short), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Switch(checked = ui.combinedNr, enabled = enabled, onCheckedChange = vm::setCombined)
                        }
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.qmi_mainland_mode_short), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            Switch(checked = ui.mainlandMode, enabled = !ui.busy, onCheckedChange = {
                                vm.configureMainlandMode(it); setSettings(settings.copy(mainlandBandMode = it))
                            })
                        }
                    }
                }
                if (device.hardware == null) Text(stringResource(R.string.qmi_no_hardware), color = MaterialTheme.colorScheme.error)
                else {
                    qmiBandDisplayOrder.filter { it.rat in draft.rats && device.hardware[it].orEmpty().isNotEmpty() }
                        .filterNot { ui.combinedNr && it == QmiBand.NR_NSA }.forEach { domain ->
                        val current = if (ui.combinedNr && domain.rat == "nr")
                            ui.activeBands[QmiBand.NR_SA].orEmpty() + ui.activeBands[QmiBand.NR_NSA].orEmpty()
                        else ui.activeBands[domain].orEmpty()
                        BandPicker(domain, if (ui.combinedNr && domain == QmiBand.NR_SA) "NR SA + NSA" else domain.label,
                            qmiVisibleBands(domain, device.hardware[domain].orEmpty(), ui.mainlandMode, ui.mcc, ui.mnc),
                            draft.bands[domain].orEmpty(), current, enabled) { vm.selectBand(domain, it) }
                    }
                }
            } else {
                CellLockReadback(ui, device)
                QmiSection(stringResource(R.string.qmi_cells)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val modes = if (!bunny) QmiCellMode.entries.toList() else buildList {
                            add(QmiCellMode.LTE)
                            if (bunnyState.supports5g) {
                                add(QmiCellMode.NR_ARFCN)
                                if (bunnyState.supportsPciLock) add(QmiCellMode.NR_PCI)
                                if (bunnyState.backend == dev.ririxi.bunnylock.modem.Backend.QUALCOMM) add(QmiCellMode.NR_MULTI_PCI)
                            }
                            add(QmiCellMode.PLMN)
                        }
                        LaunchedEffect(driver, modes) { if (cell.mode !in modes) cell = cell.copy(mode = modes.first()) }
                        modes.forEach { mode ->
                            FilterChip(shape = lockControlShape, selected = cell.mode == mode, enabled = enabled,
                                onClick = { cell = qmiCellWithDefaultScs(cell.copy(mode = mode)) },
                                colors = lockSelectionColors(),
                                leadingIcon = if (cell.mode == mode) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                                label = { Text(if (bunny && mode == QmiCellMode.LTE && !bunnyState.supportsPciLock) "LTE 频点" else stringResource(cellModeLabel(mode))) })
                        }
                    }
                    ServingCellPrefill(ui.sim, enabled, networkVm, cell,
                        modem = if (bunny) bunnyState else null, refresh = if (bunny) drivers::refresh else null) {
                        cell = if (bunny && !bunnyState.supportsPciLock) it.copy(
                            mode = if (it.mode == QmiCellMode.LTE) QmiCellMode.LTE else QmiCellMode.NR_ARFCN, pcis = "") else it
                    }
                    if (bunny && !bunnyState.supportsPciLock) Text("此后端仅支持频点锁定，不支持 PCI 锁定。", style = MaterialTheme.typography.bodySmall)
                    when (cell.mode) {
                        QmiCellMode.PLMN -> Input(cell.plmn, stringResource(R.string.qmi_plmn_label), enabled) { cell = cell.copy(plmn = it) }
                        QmiCellMode.NR_GNB -> {
                            Input(cell.gnbBits, stringResource(R.string.qmi_gnb_bits), enabled) { cell = cell.copy(gnbBits = it) }
                            Input(cell.gnbIds, stringResource(R.string.qmi_gnb_ids), enabled) { cell = cell.copy(gnbIds = it) }
                        }
                        else -> {
                            Input(cell.channel, if (cell.mode == QmiCellMode.LTE) "EARFCN" else "NR-ARFCN", enabled) {
                                cell = qmiCellWithDefaultScs(cell.copy(channel = it))
                            }
                            if (cell.mode != QmiCellMode.NR_ARFCN && (!bunny || bunnyState.supportsPciLock)) Input(cell.pcis, stringResource(R.string.qmi_pci_label), enabled) {
                                val changed = qmiCellWithPciText(cell, it)
                                cell = if (bunny && bunnyState.backend != dev.ririxi.bunnylock.modem.Backend.QUALCOMM && changed.mode == QmiCellMode.NR_MULTI_PCI)
                                    changed.copy(mode = QmiCellMode.NR_PCI) else changed
                            }
                            if (cell.mode != QmiCellMode.LTE) {
                                Text("SCS (kHz)", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(15, 30, 60, 120, 240).forEach { scs -> FilterChip(shape = lockControlShape, selected = cell.scs == scs,
                                        onClick = { cell = cell.copy(scs = scs) }, enabled = enabled,
                                        colors = lockSelectionColors(),
                                        leadingIcon = if (cell.scs == scs) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                                        label = { Text(scs.toString()) }) }
                                }
                                if (cell.mode != QmiCellMode.NR_ARFCN || bunny) Input(cell.band, stringResource(R.string.qmi_band_label), enabled) {
                                    cell = qmiCellWithDefaultScs(cell.copy(band = it))
                                }
                            }
                        }
                    }
                }
            }
            QmiSection(stringResource(R.string.qmi_connection)) {
                if (!bunny) Text(stringResource(R.string.qmi_attach_hint), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("attach" to R.string.qmi_attach, "detach" to R.string.qmi_detach, "reattach" to R.string.qmi_reattach).filter { !bunny || it.first == "reattach" }.forEach { (command, label) ->
                        OutlinedButton(shape = lockControlShape, onClick = { vm.command(command) }, enabled = enabled) { Text(stringResource(label)) }
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(16.dp))
    }
    }
    }
    if (supported || bunny) {
        val device = ui.device
        val draft = ui.draft
        val ready = !allBusy && device?.controlReady == true && (tab == 1 || device.hardware != null)
        val hasSelection = draft != null && (draft.bands.values.any { it.isNotEmpty() } ||
            draft.rats != device?.profile?.rats || draft.nrMode != device?.profile?.nrMode)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val status = if (allBusy) stringResource(R.string.qmi_reading) else driverNotice ?: ui.error ?: ui.message
            if (status != null) QmiStatusAlert(status, if (driverNotice != null && driverFailed || ui.error != null) QmiNoticeLevel.ERROR else ui.noticeLevel,
                allBusy) { drivers.dismiss(); vm.dismissStatus() }
            if (tab < 2) Surface(modifier = Modifier.onSizeChanged { dockHeightPx = it.height }, shape = lockControlShape, shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(shape = lockControlShape, onClick = {
                        if (tab == 0) vm.apply()
                        else if (cell.mode == QmiCellMode.PLMN) vm.lock(cell)
                        else pendingCell = cell
                    },
                        enabled = ready && (tab == 1 || hasSelection), modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qmi_lock))
                    }
                    OutlinedButton(shape = lockControlShape, onClick = { if (tab == 0) vm.unlockBands() else vm.unlockCells() },
                        enabled = ready, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qmi_unlock))
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CellLockReadback(ui: QmiBandUiState, device: QmiDeviceState) {
    var expanded by rememberSaveable(ui.sim) { mutableStateOf(false) }
    val status = when (ui.cellLockState) {
        QmiCellLockState.NONE -> R.string.qmi_cell_state_none
        QmiCellLockState.UNKNOWN -> R.string.qmi_cell_state_unknown
        QmiCellLockState.MATCHED -> R.string.qmi_cell_state_matched
        QmiCellLockState.OUTSIDE -> R.string.qmi_cell_state_outside
    }
    val color = when (ui.cellLockState) {
        QmiCellLockState.MATCHED -> MaterialTheme.colorScheme.primary
        QmiCellLockState.OUTSIDE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (ui.cellLockState == QmiCellLockState.MATCHED) Icons.Default.CheckCircle else Icons.Default.Info,
                    null, Modifier.size(18.dp), tint = color)
                Text(stringResource(R.string.qmi_current_locks), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(status), style = MaterialTheme.typography.labelMedium, color = color)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("nr" to "NR", "lte" to "LTE", "plmn" to "PLMN").forEach { (key, label) ->
                    val lock = device.locks[key]
                    Surface(shape = lockControlShape, color = if (!lock.isNullOrEmpty()) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text("$label · " + stringResource(when {
                            lock == null -> R.string.qmi_unavailable
                            lock.isEmpty() -> R.string.qmi_unlocked
                            else -> R.string.qmi_cell_locked
                        }), Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(if (ui.servingCells.isEmpty()) stringResource(R.string.qmi_current_cell_unknown)
                else stringResource(R.string.qmi_current_serving, ui.servingCells.joinToString("\n") {
                    it.label + (it.rsrp?.let { value -> " · ${value.toInt()} dBm" } ?: "")
                }), style = MaterialTheme.typography.bodySmall)
            val active = device.locks.filterValues { !it.isNullOrEmpty() }
            active.forEach { (key, lock) ->
                Text("${key.uppercase()} · $lock", style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
            if (active.values.any { (it?.length ?: 0) > 80 || it?.contains('\n') == true }) {
                TextButton(shape = lockControlShape, onClick = { expanded = !expanded },
                    modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(stringResource(if (expanded) R.string.qmi_collapse else R.string.qmi_expand))
                }
            }
        }
    }
}

@Composable
private fun QmiStatusAlert(message: String, level: QmiNoticeLevel, busy: Boolean, onDismiss: () -> Unit) {
    val background = when (level) {
        QmiNoticeLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
        QmiNoticeLevel.WARNING -> Color(0xFFFFEDAD)
        QmiNoticeLevel.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        QmiNoticeLevel.INFO -> MaterialTheme.colorScheme.secondaryContainer
    }
    val foreground = when (level) {
        QmiNoticeLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        QmiNoticeLevel.WARNING -> Color(0xFF463500)
        QmiNoticeLevel.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        QmiNoticeLevel.INFO -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = lockControlShape,
        color = background, contentColor = foreground,
        shadowElevation = 4.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!busy) Icon(when (level) {
                    QmiNoticeLevel.ERROR -> Icons.Default.ErrorOutline
                    QmiNoticeLevel.WARNING -> Icons.Default.WarningAmber
                    QmiNoticeLevel.INFO -> Icons.Default.Info
                    QmiNoticeLevel.SUCCESS -> Icons.Default.CheckCircle
                }, contentDescription = null)
                Text(message, Modifier.weight(1f).heightIn(max = 140.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                if (!busy) IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.qmi_dismiss_status))
                }
            }
        }
    }
}

@Composable
internal fun QmiSection(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                actions()
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BandPicker(domain: QmiBand, title: String, hardware: Set<Int>, selected: Set<Int>, current: Set<Int>, enabled: Boolean, onChange: (Set<Int>) -> Unit) {
    val activeDescription = stringResource(R.string.qmi_active_band)
    QmiSection(title, actions = {
        if (hardware.isNotEmpty()) {
            TextButton(shape = lockControlShape, onClick = { onChange(hardware) }, enabled = enabled,
                modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.qmi_select_all), style = MaterialTheme.typography.labelMedium)
            }
            TextButton(shape = lockControlShape, onClick = { onChange(emptySet()) }, enabled = enabled,
                modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.qmi_select_none), style = MaterialTheme.typography.labelMedium)
            }
        }
    }) {
        Text(stringResource(R.string.qmi_hardware, hardware.size, selected.size), style = MaterialTheme.typography.bodySmall)
        if (hardware.isEmpty()) Text(stringResource(R.string.qmi_no_bands), style = MaterialTheme.typography.bodySmall)
        else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hardware.sorted().forEach { band ->
                    val active = band in current
                    val chosen = band in selected
                    FilterChip(shape = lockControlShape, selected = chosen, enabled = enabled,
                        onClick = { onChange(if (band in selected) selected - band else selected + band) },
                        modifier = if (active) Modifier.semantics { stateDescription = activeDescription } else Modifier,
                        colors = lockSelectionColors(),
                        border = BorderStroke(if (active || chosen) 2.dp else 0.8.dp,
                            if (chosen) MaterialTheme.colorScheme.primary else if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant),
                        leadingIcon = if (chosen) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                        trailingIcon = if (active) ({ Icon(Icons.Default.CellTower, activeDescription, Modifier.size(16.dp)) }) else null,
                        label = { Text(qmiBandButtonLabel(domain, band)) })
                }
            }
        }
    }
}

@Composable
private fun lockSelectionColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary
)

@Composable
private fun ObserveBandNetwork(sim: Int, vm: ModemControlActions, networkVm: NetworkPanelMultiSimViewModel, enabled: Boolean = true) {
    val sims by networkVm.sims.collectAsState()
    val frame by networkVm.frame.collectAsState()
    val subId = sims.firstOrNull { it.simSlotIndex == sim - 1 }?.subscriptionId
    val network = subId?.let { frame.data[it] }
    com.nvmex.networkhelper.ui.network.QmiRadioObservation(networkVm, sim, enabled = enabled && !vm.bunny, subscriptionId = subId)
    LaunchedEffect(sim, network, frame.ts) { vm.updateNetwork(sim, network) }
}

@Composable
private fun Input(value: String, label: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { onChange(it.take(1024)) }, label = { Text(label) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
}

private fun cellModeLabel(mode: QmiCellMode) = when (mode) {
    QmiCellMode.LTE -> R.string.qmi_cell_lte
    QmiCellMode.NR_ARFCN -> R.string.qmi_cell_nr_arfcn
    QmiCellMode.NR_PCI -> R.string.qmi_cell_nr_pci
    QmiCellMode.NR_MULTI_PCI -> R.string.qmi_cell_nr_multi
    QmiCellMode.NR_GNB -> R.string.qmi_cell_gnb
    QmiCellMode.PLMN -> R.string.qmi_cell_plmn
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServingCellPrefill(sim: Int, enabled: Boolean, vm: NetworkPanelMultiSimViewModel, currentInput: QmiCellInput,
                              modem: dev.ririxi.bunnylock.modem.ModemState? = null, refresh: (() -> Unit)? = null,
                              onSelect: (QmiCellInput) -> Unit) {
    val sims by vm.sims.collectAsState()
    val frame by vm.frame.collectAsState()
    val subId = sims.firstOrNull { it.simSlotIndex == sim - 1 }?.subscriptionId
    val state = subId?.let { frame.data[it] }
    val history by vm.cellTimelineBySubId.collectAsState()
    val radios by vm.qmiBySlot.collectAsState()
    val loadingBySlot by vm.qmiLoading.collectAsState()
    val errors by vm.qmiErrors.collectAsState()
    val loading = modem?.refreshing ?: (loadingBySlot[sim - 1] == true)
    val modemCandidates = modem?.cells.orEmpty().mapNotNull { cell ->
        if (cell.pci < 0 || cell.arfcn <= 0 || cell.rat !in setOf(dev.ririxi.bunnylock.data.Rat.LTE, dev.ririxi.bunnylock.data.Rat.NR)) null
        else QmiCellCandidate(qmiCellWithDefaultScs(QmiCellInput(if (cell.rat == dev.ririxi.bunnylock.data.Rat.LTE) QmiCellMode.LTE else QmiCellMode.NR_PCI,
            cell.arfcn.toString(), cell.pci.toString(), cell.band?.toString().orEmpty())),
            if (cell.registered) QmiCellSource.SERVING else QmiCellSource.NEIGHBOR, cell.rsrp?.toDouble(), cell.registered)
    }
    val neighbors = if (modem != null) modemCandidates.filter { !it.isCurrent }
        else qmiLiveNeighbors(radios[sim - 1], state, sim - 1, System.currentTimeMillis())
    val candidates = (qmiCellCandidates(neighbors, history[subId].orEmpty(), state, sim - 1) + modemCandidates.filter { it.isCurrent })
        .distinctBy { it.key }.map { candidate -> candidate.copy(isCurrent = candidate.isCurrent || modemCandidates.any { it.key == candidate.key && it.isCurrent }) }
    var expanded by remember(sim) { mutableStateOf(false) }
    var frozen by remember(sim) { mutableStateOf(emptyList<QmiCellCandidate>()) }
    var selected by remember(sim) { mutableStateOf<QmiCellCandidate?>(null) }
    val sourceNames = mapOf(QmiCellSource.NEIGHBOR to stringResource(R.string.qmi_cell_neighbor),
        QmiCellSource.HISTORY to stringResource(R.string.qmi_cell_history), QmiCellSource.CARRIER to stringResource(R.string.qmi_cell_ca),
        QmiCellSource.SERVING to stringResource(R.string.qmi_cell_serving))
    fun label(candidate: QmiCellCandidate): String = "${sourceNames[candidate.source]} · ${if (candidate.input.mode == QmiCellMode.LTE) "LTE" else "NR"} · ${candidate.input.channel} · PCI ${candidate.input.pcis}"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { open ->
            if (open && enabled && candidates.isNotEmpty()) { frozen = candidates.toList(); expanded = true }
            else expanded = false
        }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(value = selected?.takeIf { it.key == "${currentInput.mode}:${currentInput.channel}:${currentInput.pcis}" }?.let(::label).orEmpty(),
                onValueChange = {}, readOnly = true, singleLine = true, enabled = enabled,
                placeholder = { Text(stringResource(R.string.qmi_cell_choose, if (expanded) frozen.size else candidates.size)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().height(56.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled))
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 380.dp)) {
            frozen.forEach { candidate ->
                val input = candidate.input
                val source = stringResource(when (candidate.source) {
                    QmiCellSource.NEIGHBOR -> R.string.qmi_cell_neighbor
                    QmiCellSource.HISTORY -> R.string.qmi_cell_history
                    QmiCellSource.CARRIER -> R.string.qmi_cell_ca
                    QmiCellSource.SERVING -> R.string.qmi_cell_serving
                })
                DropdownMenuItem(onClick = { selected = candidate; expanded = false; onSelect(input) }, text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("$source · ${if (input.mode == QmiCellMode.LTE) "LTE" else "NR"}" +
                                if (input.band.isNotBlank()) " · ${qmiBandButtonLabel(if (input.mode == QmiCellMode.LTE) QmiBand.LTE else QmiBand.NR_SA, input.band.toInt())}" else "",
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (candidate.isCurrent) Text(stringResource(R.string.qmi_cell_current),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${input.channel} · PCI ${input.pcis}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Column(Modifier.width(74.dp), horizontalAlignment = Alignment.End) {
                                Text(candidate.rsrp?.let { "${it.toInt()} dBm" } ?: "—", style = MaterialTheme.typography.labelSmall)
                                NetworkSignalProgressBar(qmiRxMetricFraction(candidate.rsrp, -140.0, -60.0), Modifier.fillMaxWidth().height(6.dp))
                            }
                        }
                    }
                })
            }
            }
        }
        OutlinedButton(shape = lockControlShape, onClick = refresh ?: vm::retryQmiMonitoring, enabled = enabled && !expanded && !loading,
            modifier = Modifier.height(56.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.qmi_cell_refresh))
        }
    }
    if (!expanded && modem == null) errors[sim - 1]?.let { Text(stringResource(R.string.qmi_read_failed, it), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall) }
}
