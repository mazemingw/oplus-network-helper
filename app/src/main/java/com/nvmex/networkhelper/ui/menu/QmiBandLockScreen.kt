package com.nvmex.networkhelper.ui.menu

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.nvmex.networkhelper.viewmodel.menu.QmiNoticeLevel
import com.nvmex.networkhelper.viewmodel.signal.NetworkPanelMultiSimViewModel
import com.nvmex.networkhelper.ui.settings.rememberAppSettings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QmiBandLockScreen(onBack: () -> Unit, vm: QmiBandLockViewModel = viewModel(),
                     networkVm: NetworkPanelMultiSimViewModel = viewModel()) {
    val ui by vm.state.collectAsState()
    val supported = remember { SocVendorDetector.isQualcomm() }
    val (settings, setSettings) = rememberAppSettings()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dockHeightPx by remember { mutableIntStateOf(0) }
    val dockHeight = with(LocalDensity.current) { dockHeightPx.toDp() }
    var cell by remember(ui.sim) { mutableStateOf(QmiCellInput()) }
    var pendingCell by remember(ui.sim) { mutableStateOf<QmiCellInput?>(null) }
    if (supported) {
        ObserveBandNetwork(ui.sim, vm, networkVm)
    }
    pendingCell?.let { input ->
        AlertDialog(onDismissRequest = { pendingCell = null },
            title = { Text(stringResource(R.string.qmi_cell_rat_title)) },
            text = { Text(stringResource(if (input.mode == QmiCellMode.LTE) R.string.qmi_cell_rat_lte else R.string.qmi_cell_rat_nr)) },
            confirmButton = { TextButton(onClick = { pendingCell = null; vm.lock(input, lockRat = true) }) {
                Text(stringResource(R.string.qmi_cell_rat_together)) } },
            dismissButton = { TextButton(onClick = { pendingCell = null; vm.lock(input) }) {
                Text(stringResource(R.string.qmi_cell_rat_only)) } })
    }
    LaunchedEffect(settings.mainlandBandMode) { vm.configureMainlandMode(settings.mainlandBandMode) }
    LaunchedEffect(Unit) { if (supported && ui.device == null) vm.refresh() }
    LaunchedEffect(ui.busy, ui.device) {
        if (!ui.busy && ui.device?.controlReady == true) networkVm.retryQmiMonitoring()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = dockHeight + 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.qmi_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
        if (!supported) {
            Text(stringResource(R.string.qmi_unsupported), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..2).forEach { sim -> FilterChip(selected = ui.sim == sim, enabled = !ui.busy,
                onClick = { if (ui.sim != sim) vm.refresh(sim) }, label = { Text("SIM $sim") }) }
            AssistChip(onClick = { vm.refresh() }, enabled = !ui.busy,
                label = { Text(stringResource(R.string.qmi_read)) })
            AssistChip(onClick = vm::resetSession, enabled = !ui.busy,
                label = { Text(stringResource(R.string.qmi_reset_session)) })
        }
        TabRow(selectedTabIndex = tab) {
            listOf(R.string.qmi_bands, R.string.qmi_cells).forEachIndexed { index, res ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(res)) })
            }
        }
        val device = ui.device
        val draft = ui.draft
        if (device != null && draft != null) {
            val enabled = !ui.busy && device.controlReady
            if (tab == 0) {
                QmiSection(stringResource(R.string.qmi_rats)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("nr" to "NR", "lte" to "LTE", "wcdma" to "WCDMA", "gsm" to "GSM").forEach { (key, label) ->
                            FilterChip(selected = key in draft.rats, enabled = enabled,
                                onClick = { vm.edit { it.copy(rats = if (key in it.rats) it.rats - key else it.rats + key) } },
                                colors = lockSelectionColors(),
                                leadingIcon = if (key in draft.rats) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                                label = { Text(label) })
                        }
                    }
                    if ("nr" in draft.rats) {
                    Text(stringResource(R.string.qmi_nr_mode), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("both" to R.string.qmi_nr_both, "sa" to R.string.qmi_nr_sa, "nsa" to R.string.qmi_nr_nsa).forEach { (mode, label) ->
                            FilterChip(selected = draft.nrMode == mode, enabled = enabled && device.nrModeSettable,
                                onClick = { vm.edit { it.copy(nrMode = mode) } }, colors = lockSelectionColors(),
                                leadingIcon = if (draft.nrMode == mode) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                                label = { Text(stringResource(label)) })
                        }
                    }
                    if (!device.nrModeSettable) Text(stringResource(R.string.qmi_nr_mode_read_only),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.qmi_combined_nr), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = ui.combinedNr, enabled = enabled, onCheckedChange = vm::setCombined)
                    }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.qmi_mainland_mode), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = ui.mainlandMode, enabled = !ui.busy, onCheckedChange = {
                            vm.configureMainlandMode(it)
                            setSettings(settings.copy(mainlandBandMode = it))
                        })
                    }
                    if (ui.mainlandMode) Text(
                        if (mainlandBandRule(ui.mcc, ui.mnc) == null) stringResource(R.string.qmi_mainland_unknown)
                        else stringResource(R.string.qmi_mainland_operator, ui.mcc, ui.mnc),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ui.activeBands.values.any { it.isNotEmpty() }) Text(stringResource(R.string.qmi_active_legend),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (device.hardware == null) Text(stringResource(R.string.qmi_no_hardware), color = MaterialTheme.colorScheme.error)
                else {
                    qmiBandDisplayOrder.filter { it.rat in draft.rats }
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
                QmiSection(stringResource(R.string.qmi_current_locks)) {
                    Text(if (ui.servingCells.isEmpty()) stringResource(R.string.qmi_current_cell_unknown)
                        else ui.servingCells.joinToString("\n") { "${it.label}" + (it.rsrp?.let { value -> " · ${value.toInt()} dBm" } ?: "") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(when (ui.cellLockState) {
                        QmiCellLockState.NONE -> R.string.qmi_cell_state_none
                        QmiCellLockState.UNKNOWN -> R.string.qmi_cell_state_unknown
                        QmiCellLockState.MATCHED -> R.string.qmi_cell_state_matched
                        QmiCellLockState.OUTSIDE -> R.string.qmi_cell_state_outside
                    }), modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ui.cellLockState == QmiCellLockState.MATCHED) MaterialTheme.colorScheme.primary
                            else if (ui.cellLockState == QmiCellLockState.OUTSIDE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("lte" to "LTE", "nr" to "NR", "plmn" to "PLMN").forEach { (key, label) ->
                        val lock = device.locks[key]
                        Text("$label · " + (lock?.ifEmpty { stringResource(R.string.qmi_unlocked) } ?: stringResource(R.string.qmi_unavailable)), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.qmi_cell_unlock_scope), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                QmiSection(stringResource(R.string.qmi_cells)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QmiCellMode.entries.forEach { mode ->
                            FilterChip(selected = cell.mode == mode, enabled = enabled, onClick = { cell = cell.copy(mode = mode) },
                                label = { Text(stringResource(cellModeLabel(mode))) })
                        }
                    }
                    ServingCellPrefill(ui.sim, enabled, networkVm, cell) { cell = it }
                    when (cell.mode) {
                        QmiCellMode.PLMN -> Input(cell.plmn, stringResource(R.string.qmi_plmn_label), enabled) { cell = cell.copy(plmn = it) }
                        QmiCellMode.NR_GNB -> {
                            Input(cell.gnbBits, stringResource(R.string.qmi_gnb_bits), enabled) { cell = cell.copy(gnbBits = it) }
                            Input(cell.gnbIds, stringResource(R.string.qmi_gnb_ids), enabled) { cell = cell.copy(gnbIds = it) }
                        }
                        else -> {
                            Input(cell.channel, if (cell.mode == QmiCellMode.LTE) "EARFCN" else "NR-ARFCN", enabled) { cell = cell.copy(channel = it) }
                            if (cell.mode != QmiCellMode.NR_ARFCN) Input(cell.pcis, stringResource(R.string.qmi_pci_label), enabled) { cell = cell.copy(pcis = it) }
                            if (cell.mode != QmiCellMode.LTE) {
                                Text("SCS (kHz)", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(15, 30, 60, 120, 240).forEach { scs -> FilterChip(selected = cell.scs == scs,
                                        onClick = { cell = cell.copy(scs = scs) }, enabled = enabled, label = { Text(scs.toString()) }) }
                                }
                                if (cell.mode != QmiCellMode.NR_ARFCN) Input(cell.band, stringResource(R.string.qmi_band_label), enabled) { cell = cell.copy(band = it) }
                            }
                        }
                    }
                }
            }
            QmiSection(stringResource(R.string.qmi_connection)) {
                Text(stringResource(R.string.qmi_attach_hint), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("attach" to R.string.qmi_attach, "detach" to R.string.qmi_detach, "reattach" to R.string.qmi_reattach).forEach { (command, label) ->
                        OutlinedButton(onClick = { vm.command(command) }, enabled = enabled) { Text(stringResource(label)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (supported) {
        val device = ui.device
        val draft = ui.draft
        val ready = !ui.busy && device?.controlReady == true && (tab == 1 || device.hardware != null)
        val hasSelection = draft != null && (draft.bands.values.any { it.isNotEmpty() } ||
            draft.rats != device?.profile?.rats || draft.nrMode != device?.profile?.nrMode)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)
            .onSizeChanged { dockHeightPx = it.height }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val status = if (ui.busy) stringResource(R.string.qmi_reading) else ui.error ?: ui.message
            if (status != null) QmiStatusAlert(status, if (ui.error != null) QmiNoticeLevel.ERROR else ui.noticeLevel, ui.busy, vm::dismissStatus)
            Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        if (tab == 0) vm.apply()
                        else if (cell.mode == QmiCellMode.PLMN) vm.lock(cell)
                        else pendingCell = cell
                    },
                        enabled = ready && (tab == 1 || hasSelection), modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qmi_lock))
                    }
                    OutlinedButton(onClick = { if (tab == 0) vm.unlockBands() else vm.unlockCells() },
                        enabled = ready, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.qmi_unlock))
                    }
                }
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
    Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = RoundedCornerShape(18.dp),
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
private fun BandPicker(domain: QmiBand, title: String, hardware: Set<Int>, selected: Set<Int>, current: Set<Int>, enabled: Boolean, onChange: (Set<Int>) -> Unit) {
    val activeDescription = stringResource(R.string.qmi_active_band)
    QmiSection(title, actions = {
        if (hardware.isNotEmpty()) {
            TextButton(onClick = { onChange(hardware) }, enabled = enabled,
                modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.qmi_select_all), style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { onChange(emptySet()) }, enabled = enabled,
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
                    FilterChip(selected = chosen, enabled = enabled,
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
private fun ObserveBandNetwork(sim: Int, vm: QmiBandLockViewModel, networkVm: NetworkPanelMultiSimViewModel) {
    val sims by networkVm.sims.collectAsState()
    val frame by networkVm.frame.collectAsState()
    val subId = sims.firstOrNull { it.simSlotIndex == sim - 1 }?.subscriptionId
    val network = subId?.let { frame.data[it] }
    com.nvmex.networkhelper.ui.network.QmiRadioObservation(networkVm, sim, subscriptionId = subId)
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
                              onSelect: (QmiCellInput) -> Unit) {
    val sims by vm.sims.collectAsState()
    val frame by vm.frame.collectAsState()
    val subId = sims.firstOrNull { it.simSlotIndex == sim - 1 }?.subscriptionId
    val state = subId?.let { frame.data[it] }
    val history by vm.cellTimelineBySubId.collectAsState()
    val radios by vm.qmiBySlot.collectAsState()
    val loadingBySlot by vm.qmiLoading.collectAsState()
    val errors by vm.qmiErrors.collectAsState()
    val loading = loadingBySlot[sim - 1] == true
    val neighbors = radios[sim - 1]?.takeIf { System.currentTimeMillis() - it.updatedAt in 0..12000 }?.neighbors.orEmpty()
    val candidates = qmiCellCandidates(neighbors, history[subId].orEmpty(), state, sim - 1)
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
        OutlinedButton(onClick = vm::retryQmiMonitoring, enabled = enabled && !expanded && !loading,
            modifier = Modifier.height(56.dp), shape = RoundedCornerShape(4.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.qmi_cell_refresh))
        }
    }
    if (!expanded) errors[sim - 1]?.let { Text(stringResource(R.string.qmi_read_failed, it), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall) }
}
