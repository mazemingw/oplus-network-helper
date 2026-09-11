package com.nvmex.networkhelper.ui.menu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.windows.WindowUtils
import com.nvmex.networkhelper.viewmodel.signal.NetworkPanelMultiSimViewModel
import com.nvmex.networkhelper.xposed.config.Config
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator



@Composable
fun EngineerBandLockScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val statusBarHeight = WindowUtils.getStatusBarHeight(context)
    val networkViewModel: NetworkPanelMultiSimViewModel = viewModel()
    val networkFrame by networkViewModel.frame.collectAsState()

    var selected5gBands by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selected4gBands by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedLegacyMode by remember { mutableStateOf(LEGACY_BAND_MODES.first { it.keyInt == LEGACY_AUTOMATIC_KEY_INT }) }
    var selectedTab by remember { mutableStateOf(BandRat.NR5G) }
    var selectedSim by remember { mutableStateOf(SimTarget.SIM1) }

    var subIdInput by remember { mutableStateOf("") }
    var slotIdInput by remember { mutableStateOf("") }
    var keyIntInput by remember { mutableStateOf("") }

    val waitingToSend = stringResource(R.string.engineer_band_lock_waiting_to_send)
    var latestResult by remember { mutableStateOf(waitingToSend) }
    var latestProcess by remember { mutableStateOf("-") }
    val successText = stringResource(R.string.state_success)
    val failedText = stringResource(R.string.state_failed)
    val chooseBandText = stringResource(R.string.engineer_band_lock_choose_band_toast)
    val commandSentText = stringResource(R.string.engineer_band_lock_command_sent)
    val unlockSentText = stringResource(R.string.engineer_band_lock_unlock_sent)
    val clearingBeforeLockText = stringResource(R.string.engineer_band_lock_clear_before_lock)
    val placeholderText = stringResource(R.string.engineer_band_lock_tab_placeholder)
    val dataSimWarningText = stringResource(R.string.engineer_band_lock_data_sim_warning)
    var hasSuccessfulResultForCurrentCommand by remember { mutableStateOf(false) }
    fun effectiveSubId(): Int = subIdInput.toIntOrNull() ?: selectedSim.subId
    val selectedPanelState = networkFrame.data[effectiveSubId()]
    val carrierRule = remember(selectedPanelState?.mcc, selectedPanelState?.mnc) {
        selectedPanelState?.let(::bandLockCarrierRuleFor)
    }
    var expandedUnsupportedTabs by remember { mutableStateOf<Set<BandRat>>(emptySet()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Config.ACTION_ENGINEER_BAND_LOCK_RESULT) return
                val ok = intent.getBooleanExtra(Config.EXTRA_BAND_LOCK_OK, false)
                Log.i("NetworkHelper999", "[APP][cmd] received result ok=$ok extras=${intent.extras?.keySet()}")
                if (!ok && hasSuccessfulResultForCurrentCommand) return
                if (ok) hasSuccessfulResultForCurrentCommand = true
                val msg = intent.getStringExtra(Config.EXTRA_BAND_LOCK_MESSAGE).orEmpty()
                val proc = intent.getStringExtra(Config.EXTRA_BAND_LOCK_PROCESS).orEmpty()
                val hex = intent.getStringExtra(Config.EXTRA_BAND_LOCK_BANDS_HEX).orEmpty()

                latestResult = (if (ok) successText else failedText) + if (msg.isBlank()) "" else " · $msg"
                latestProcess = proc.ifBlank { "-" }

                if (hex.isNotBlank()) {
                    latestResult = "$latestResult · hex=$hex"
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Config.ACTION_ENGINEER_BAND_LOCK_RESULT)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // Fact readback is intentionally disabled for now: non-data SIMs can report success while
    // the modem ignores the band-lock request, and the current readback path is not stable enough.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = statusBarHeight)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.engineer_band_lock_title),
                style = MaterialTheme.typography.headlineSmall
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
        }

        Spacer(Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SimTargetSelector(
                    selected = selectedSim,
                    onSelected = { selectedSim = it }
                )
                Text(
                    text = dataSimWarningText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.engineer_band_lock_target_bands),
                    style = MaterialTheme.typography.titleSmall
                )
                TabRow(selectedTabIndex = BandRat.entries.indexOf(selectedTab)) {
                    BandRat.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tabLabel(tab)) }
                        )
                    }
                }

                val tabBands = when (selectedTab) {
                    BandRat.LEGACY_2G3G -> emptyList()
                    BandRat.LTE4G -> LTE_4G_BANDS
                    BandRat.NR5G -> NR_5G_BANDS
                }
                val selectedBandsForCurrentTab = selectedBandsForTab(selectedTab, selected4gBands, selected5gBands)
                val currentBandsForCurrentTab = currentBandsForTab(selectedTab, selectedPanelState)
                val preferredBandsForCurrentTab = carrierRule?.bandsFor(selectedTab)
                val isAutoCollapsed = preferredBandsForCurrentTab != null && selectedTab != BandRat.LEGACY_2G3G
                val isExpanded = selectedTab in expandedUnsupportedTabs
                val collapsedVisibleBandSet = preferredBandsForCurrentTab
                    ?.let { it + selectedBandsForCurrentTab + currentBandsForCurrentTab }
                    .orEmpty()
                val visibleBandSet = when {
                    preferredBandsForCurrentTab == null || isExpanded -> tabBands.toSet()
                    else -> collapsedVisibleBandSet
                }
                val visibleTabBands = tabBands.filter { it in visibleBandSet }
                val hiddenBandCount = if (preferredBandsForCurrentTab == null) 0 else (tabBands.toSet() - collapsedVisibleBandSet).size

                if (selectedTab == BandRat.LEGACY_2G3G) {
                    LegacyModeSelector(
                        modes = LEGACY_BAND_MODES,
                        selected = selectedLegacyMode,
                        onSelected = { selectedLegacyMode = it }
                    )
                } else if (tabBands.isEmpty()) {
                    Text(
                        text = placeholderText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    BandCheckGrid(
                        bands = visibleTabBands,
                        prefix = selectedTab.bandPrefix(),
                        selectedBands = selectedBandsForTab(selectedTab, selected4gBands, selected5gBands),
                        currentBands = currentBandsForCurrentTab,
                        onToggle = { band, checked ->
                            when (selectedTab) {
                                BandRat.LTE4G -> {
                                    selected4gBands = if (checked) selected4gBands + band else selected4gBands - band
                                }
                                BandRat.NR5G -> {
                                    selected5gBands = if (checked) selected5gBands + band else selected5gBands - band
                                }
                                BandRat.LEGACY_2G3G -> Unit
                            }
                        }
                    )
                    if (isAutoCollapsed && hiddenBandCount > 0) {
                        TextButton(
                            onClick = {
                                expandedUnsupportedTabs = if (isExpanded) {
                                    expandedUnsupportedTabs - selectedTab
                                } else {
                                    expandedUnsupportedTabs + selectedTab
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(
                                if (isExpanded) {
                                    stringResource(R.string.engineer_band_lock_collapse_unsupported_bands)
                                } else {
                                    stringResource(R.string.engineer_band_lock_show_all_bands, hiddenBandCount)
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (selectedTab == BandRat.LEGACY_2G3G) {
                                sendBandModeCommand(
                                    context = context,
                                    modeKeyInt = LEGACY_AUTOMATIC_KEY_INT,
                                    subId = effectiveSubId(),
                                    slotId = slotIdInput.toIntOrNull()
                                )
                                sendBandModeCommand(
                                    context = context,
                                    modeKeyInt = selectedLegacyMode.keyInt,
                                    subId = effectiveSubId(),
                                    slotId = slotIdInput.toIntOrNull()
                                )
                                hasSuccessfulResultForCurrentCommand = false
                                latestResult = clearingBeforeLockText + " · " + commandSentText
                                return@Button
                            }
                            val bands = selectedBandsForTab(selectedTab, selected4gBands, selected5gBands).sorted()
                            if (bands.isEmpty()) {
                                Toast.makeText(context, chooseBandText, Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val keyIntForTab = keyIntInput.toIntOrNull() ?: selectedTab.defaultKeyInt()
                            sendBandLockCommand(
                                context = context,
                                unlock = true,
                                bands = intArrayOf(),
                                subId = effectiveSubId(),
                                slotId = slotIdInput.toIntOrNull(),
                                keyInt = keyIntForTab
                            )
                            sendBandLockCommand(
                                context = context,
                                unlock = false,
                                bands = bands.toIntArray(),
                                subId = effectiveSubId(),
                                slotId = slotIdInput.toIntOrNull(),
                                keyInt = keyIntForTab
                            )
                            hasSuccessfulResultForCurrentCommand = false
                            latestResult = clearingBeforeLockText + " · " + commandSentText
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.engineer_band_lock_lock_selected))
                    }

                    Button(
                        onClick = {
                            sendBandLockCommand(
                                context = context,
                                unlock = true,
                                bands = intArrayOf(),
                                subId = effectiveSubId(),
                                slotId = slotIdInput.toIntOrNull(),
                                keyInt = keyIntInput.toIntOrNull() ?: selectedTab.defaultKeyInt()
                            )
                            hasSuccessfulResultForCurrentCommand = false
                            latestResult = unlockSentText
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.engineer_band_lock_unlock_clear))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.engineer_band_lock_result_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.engineer_band_lock_status, latestResult), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.engineer_band_lock_process, latestProcess), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = stringResource(R.string.engineer_band_lock_xposed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.engineer_band_lock_optional_params_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.engineer_band_lock_optional_params_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = subIdInput,
                    onValueChange = { subIdInput = it },
                    label = { Text(stringResource(R.string.engineer_band_lock_sub_id_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = slotIdInput,
                    onValueChange = { slotIdInput = it },
                    label = { Text(stringResource(R.string.engineer_band_lock_slot_id_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = keyIntInput,
                    onValueChange = { keyIntInput = it },
                    label = { Text(stringResource(R.string.engineer_band_lock_key_int_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun EngineerBandLockDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onOpenFullPanel: () -> Unit
) {
    if (!show) return

    val context = LocalContext.current
    val networkViewModel: NetworkPanelMultiSimViewModel = viewModel()
    val networkFrame by networkViewModel.frame.collectAsState()

    var selected5gBands by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selected4gBands by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedLegacyMode by remember { mutableStateOf(LEGACY_BAND_MODES.first { it.keyInt == LEGACY_AUTOMATIC_KEY_INT }) }
    var selectedTab by remember { mutableStateOf(BandRat.NR5G) }
    var selectedSim by remember { mutableStateOf(SimTarget.SIM1) }
    var expandedUnsupportedTabs by remember { mutableStateOf<Set<BandRat>>(emptySet()) }
    var latestResult by remember { mutableStateOf<Boolean?>(null) }
    var hasSuccessfulResultForCurrentCommand by remember { mutableStateOf(false) }

    val successText = stringResource(R.string.state_success)
    val failedText = stringResource(R.string.state_failed)
    val waitingToSend = stringResource(R.string.engineer_band_lock_waiting_to_send)
    val chooseBandText = stringResource(R.string.engineer_band_lock_choose_band_toast)

    val selectedPanelState = networkFrame.data[selectedSim.subId]
    val carrierRule = remember(selectedPanelState?.mcc, selectedPanelState?.mnc) {
        selectedPanelState?.let(::bandLockCarrierRuleFor)
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Config.ACTION_ENGINEER_BAND_LOCK_RESULT) return
                val ok = intent.getBooleanExtra(Config.EXTRA_BAND_LOCK_OK, false)
                if (!ok && hasSuccessfulResultForCurrentCommand) return
                if (ok) hasSuccessfulResultForCurrentCommand = true
                latestResult = ok
            }
        }

        val filter = IntentFilter().apply {
            addAction(Config.ACTION_ENGINEER_BAND_LOCK_RESULT)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.engineer_band_lock_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SimTargetSelector(
                    selected = selectedSim,
                    onSelected = { selectedSim = it }
                )

                Text(
                    text = stringResource(R.string.engineer_band_lock_target_bands),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                TabRow(selectedTabIndex = BandRat.entries.indexOf(selectedTab)) {
                    BandRat.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tabLabel(tab)) }
                        )
                    }
                }

                val tabBands = when (selectedTab) {
                    BandRat.LEGACY_2G3G -> emptyList()
                    BandRat.LTE4G -> LTE_4G_BANDS
                    BandRat.NR5G -> NR_5G_BANDS
                }
                val selectedBandsForCurrentTab = selectedBandsForTab(selectedTab, selected4gBands, selected5gBands)
                val currentBandsForCurrentTab = currentBandsForTab(selectedTab, selectedPanelState)
                val preferredBandsForCurrentTab = carrierRule?.bandsFor(selectedTab)
                val isExpanded = selectedTab in expandedUnsupportedTabs
                val collapsedVisibleBandSet = preferredBandsForCurrentTab
                    ?.let { it + selectedBandsForCurrentTab + currentBandsForCurrentTab }
                    .orEmpty()
                val visibleBandSet = when {
                    preferredBandsForCurrentTab == null || isExpanded -> tabBands.toSet()
                    else -> collapsedVisibleBandSet
                }
                val visibleTabBands = tabBands.filter { it in visibleBandSet }
                val hiddenBandCount = if (preferredBandsForCurrentTab == null) {
                    0
                } else {
                    (tabBands.toSet() - collapsedVisibleBandSet).size
                }

                when {
                    selectedTab == BandRat.LEGACY_2G3G -> {
                        LegacyModeSelector(
                            modes = LEGACY_BAND_MODES,
                            selected = selectedLegacyMode,
                            onSelected = { selectedLegacyMode = it }
                        )
                    }
                    tabBands.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.engineer_band_lock_tab_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        BandCheckGrid(
                            bands = visibleTabBands,
                            prefix = selectedTab.bandPrefix(),
                            selectedBands = selectedBandsForCurrentTab,
                            currentBands = currentBandsForCurrentTab,
                            onToggle = { band, checked ->
                                when (selectedTab) {
                                    BandRat.LTE4G -> {
                                        selected4gBands = if (checked) selected4gBands + band else selected4gBands - band
                                    }
                                    BandRat.NR5G -> {
                                        selected5gBands = if (checked) selected5gBands + band else selected5gBands - band
                                    }
                                    BandRat.LEGACY_2G3G -> Unit
                                }
                            }
                        )
                        if (preferredBandsForCurrentTab != null && hiddenBandCount > 0) {
                            TextButton(
                                onClick = {
                                    expandedUnsupportedTabs = if (isExpanded) {
                                        expandedUnsupportedTabs - selectedTab
                                    } else {
                                        expandedUnsupportedTabs + selectedTab
                                    }
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    if (isExpanded) {
                                        stringResource(R.string.engineer_band_lock_collapse_unsupported_bands)
                                    } else {
                                        stringResource(R.string.engineer_band_lock_show_all_bands, hiddenBandCount)
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (selectedTab == BandRat.LEGACY_2G3G) {
                                sendBandModeCommand(
                                    context = context,
                                    modeKeyInt = LEGACY_AUTOMATIC_KEY_INT,
                                    subId = selectedSim.subId,
                                    slotId = null
                                )
                                sendBandModeCommand(
                                    context = context,
                                    modeKeyInt = selectedLegacyMode.keyInt,
                                    subId = selectedSim.subId,
                                    slotId = null
                                )
                                hasSuccessfulResultForCurrentCommand = false
                                latestResult = null
                                return@Button
                            }
                            val bands = selectedBandsForTab(selectedTab, selected4gBands, selected5gBands).sorted()
                            if (bands.isEmpty()) {
                                Toast.makeText(context, chooseBandText, Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val keyIntForTab = selectedTab.defaultKeyInt()
                            sendBandLockCommand(
                                context = context,
                                unlock = true,
                                bands = intArrayOf(),
                                subId = selectedSim.subId,
                                slotId = null,
                                keyInt = keyIntForTab
                            )
                            sendBandLockCommand(
                                context = context,
                                unlock = false,
                                bands = bands.toIntArray(),
                                subId = selectedSim.subId,
                                slotId = null,
                                keyInt = keyIntForTab
                            )
                            hasSuccessfulResultForCurrentCommand = false
                            latestResult = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.engineer_band_lock_lock_selected))
                    }

                    Button(
                        onClick = {
                            sendBandLockCommand(
                                context = context,
                                unlock = true,
                                bands = intArrayOf(),
                                subId = selectedSim.subId,
                                slotId = null,
                                keyInt = selectedTab.defaultKeyInt()
                            )
                            hasSuccessfulResultForCurrentCommand = false
                            latestResult = null
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.engineer_band_lock_unlock_clear))
                    }
                }

                Text(
                    text = stringResource(
                        R.string.engineer_band_lock_status,
                        when (latestResult) {
                            true -> successText
                            false -> failedText
                            null -> waitingToSend
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenFullPanel) {
                Text(stringResource(R.string.engineer_band_lock_open_full_panel))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun BandCheckGrid(
    bands: List<Int>,
    prefix: String,
    selectedBands: Set<Int>,
    currentBands: Set<Int>,
    onToggle: (Int, Boolean) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 520.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            bands.chunked(columns).forEach { rowBands ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowBands.forEach { band ->
                        BandCheckCell(
                            band = band,
                            prefix = prefix,
                            checked = band in selectedBands,
                            isCurrent = band in currentBands,
                            onCheckedChange = { onToggle(band, it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(columns - rowBands.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BandCheckCell(
    band: Int,
    prefix: String,
    checked: Boolean,
    isCurrent: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.32f)
    }
    val borderColor = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        tonalElevation = if (isCurrent) 1.dp else 0.dp,
        border = BorderStroke(if (isCurrent) 1.1.dp else 0.6.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "$prefix$band ${bandFrequencyLabel(prefix, band)}".trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun SimTargetSelector(
    selected: SimTarget,
    onSelected: (SimTarget) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SimTarget.entries.forEachIndexed { index, target ->
            SegmentedButton(
                selected = selected == target,
                onClick = { onSelected(target) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = SimTarget.entries.size
                )
            ) {
                Text(target.label)
            }
        }
    }
}

@Composable
private fun LegacyModeSelector(
    modes: List<LegacyBandMode>,
    selected: LegacyBandMode,
    onSelected: (LegacyBandMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        modes.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == mode,
                        onClick = { onSelected(mode) }
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == mode,
                    onClick = { onSelected(mode) }
                )
                Text(mode.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private enum class BandRat {
    LEGACY_2G3G,
    LTE4G,
    NR5G
}

private enum class SimTarget(
    val label: String,
    val subId: Int
) {
    SIM1("SIM1", 1),
    SIM2("SIM2", 2)
}

private data class BandLockCarrierRule(
    val nrBands: Set<Int>,
    val lteBands: Set<Int>
) {
    fun bandsFor(tab: BandRat): Set<Int>? = when (tab) {
        BandRat.NR5G -> nrBands
        BandRat.LTE4G -> lteBands
        BandRat.LEGACY_2G3G -> null
    }
}

private fun bandLockCarrierRuleFor(state: NetworkPanelUiState): BandLockCarrierRule? {
    if (state.mcc.trim() != "460") return null
    return when (state.mnc.trim()) {
        "00", "02", "07", "15" -> BandLockCarrierRule(
            nrBands = setOf(28, 41, 78, 79),
            lteBands = setOf(3, 8, 34, 38, 39, 40, 41)
        )
        "01", "06", "09" -> BandLockCarrierRule(
            nrBands = setOf(1, 3, 5, 8, 78),
            lteBands = setOf(1, 3, 8)
        )
        "03", "05", "11" -> BandLockCarrierRule(
            nrBands = setOf(1, 3, 5, 8, 78),
            lteBands = setOf(1, 3, 5)
        )
        else -> null
    }
}

private fun currentBandsForTab(tab: BandRat, state: NetworkPanelUiState?): Set<Int> {
    if (state == null) return emptySet()
    return when (tab) {
        BandRat.NR5G -> currentNrBands(state)
        BandRat.LTE4G -> currentLteBands(state)
        BandRat.LEGACY_2G3G -> emptySet()
    }
}

private fun currentNrBands(state: NetworkPanelUiState): Set<Int> {
    val nrcaBands = state.nrCaInfo?.carriers.orEmpty()
        .mapNotNull { carrier ->
            NrcaTranslator.bandShort(carrier.bandRaw).toIntOrNull()
                ?: carrier.bandRaw.takeIf { it > 0 }
        }
        .toSet()
    if (nrcaBands.isNotEmpty()) return nrcaBands

    return if (state.cellType.equals("NR", ignoreCase = true) || state.dataNetworkType == "NR") {
        state.band.trim().toIntOrNull()?.let(::setOf).orEmpty()
    } else {
        emptySet()
    }
}

private fun currentLteBands(state: NetworkPanelUiState): Set<Int> {
    val directBand = if (state.cellType.equals("LTE", ignoreCase = true)) {
        state.band.trim().toIntOrNull()
    } else {
        null
    }
    val anchorBand = state.nsaLteAnchor?.band?.trim()?.toIntOrNull()
        ?: state.anchorBand.trim().toIntOrNull()
    return listOfNotNull(directBand, anchorBand).toSet()
}

private fun tabLabel(tab: BandRat): String = when (tab) {
    BandRat.LEGACY_2G3G -> "2G/3G"
    BandRat.LTE4G -> "4G"
    BandRat.NR5G -> "5G"
}

private fun BandRat.bandPrefix(): String = when (this) {
    BandRat.LTE4G -> "B"
    else -> "N"
}

private fun BandRat.defaultKeyInt(): Int = when (this) {
    BandRat.LTE4G -> 4
    BandRat.NR5G -> 5
    BandRat.LEGACY_2G3G -> 5
}

private fun selectedBandsForTab(
    tab: BandRat,
    selected4gBands: Set<Int>,
    selected5gBands: Set<Int>
): Set<Int> = when (tab) {
    BandRat.LTE4G -> selected4gBands
    BandRat.NR5G -> selected5gBands
    BandRat.LEGACY_2G3G -> emptySet()
}

private fun bandFrequencyLabel(prefix: String, band: Int): String = when (prefix.uppercase()) {
    "N" -> NR_BAND_FREQ_LABELS[band]
    "B" -> LTE_BAND_FREQ_LABELS[band]
    else -> null
}.orEmpty()

private val NR_5G_BANDS = listOf(
    1, 2, 3, 5, 7, 8, 20, 25, 28, 30, 38, 40, 41, 48, 66, 71, 77, 78, 79, 258, 260, 261
)

private val LTE_4G_BANDS = listOf(
    1, 2, 3, 4, 5, 7, 8, 11, 12, 13, 17, 18, 19, 20, 21, 25, 26, 28, 29, 30, 32, 34,
    38, 39, 40, 41, 46, 48, 65, 66, 71
)

private val NR_BAND_FREQ_LABELS = mapOf(
    1 to "2.1G",
    2 to "1.9G",
    3 to "1.8G",
    5 to "850M",
    7 to "2.6G",
    8 to "900M",
    20 to "800M",
    25 to "1.9G",
    28 to "700M",
    30 to "2.3G",
    38 to "2.6G",
    40 to "2.3G",
    41 to "2.6G",
    48 to "3.5G",
    66 to "AWS",
    71 to "600M",
    77 to "3.7G",
    78 to "3.5G",
    79 to "4.9G",
    258 to "26G",
    260 to "39G",
    261 to "28G"
)

private val LTE_BAND_FREQ_LABELS = mapOf(
    1 to "2.1G",
    2 to "1.9G",
    3 to "1.8G",
    4 to "AWS",
    5 to "850M",
    7 to "2.6G",
    8 to "900M",
    11 to "1.5G",
    12 to "700M",
    13 to "700M",
    17 to "700M",
    18 to "850M",
    19 to "850M",
    20 to "800M",
    21 to "1.5G",
    25 to "1.9G",
    26 to "850M",
    28 to "700M",
    29 to "700M",
    30 to "2.3G",
    32 to "1.5G",
    34 to "2.0G",
    38 to "2.6G",
    39 to "1.9G",
    40 to "2.3G",
    41 to "2.6G",
    46 to "5GHz",
    48 to "3.5G",
    65 to "2.1G",
    66 to "AWS",
    71 to "600M"
)

private data class LegacyBandMode(
    val label: String,
    val keyInt: Int
)

private const val LEGACY_AUTOMATIC_KEY_INT = 3

private val LEGACY_BAND_MODES = listOf(
    LegacyBandMode("Automatic", 3),
    LegacyBandMode("Japan Band", 31),
    LegacyBandMode("GSM 850", 73),
    LegacyBandMode("EGSM 900", 33),
    LegacyBandMode("GSM 1800", 35),
    LegacyBandMode("WCDMA 850", 75),
    LegacyBandMode("WCDMA 900", 74),
    LegacyBandMode("WCDMA 1700", 76),
    LegacyBandMode("WCDMA VI 800", 37),
    LegacyBandMode("WCDMA 2100", 38)
)

private fun sendBandLockCommand(
    context: Context,
    unlock: Boolean,
    bands: IntArray,
    subId: Int?,
    slotId: Int?,
    keyInt: Int?
) {
    fun buildIntent(targetPkg: String): Intent {
        return Intent(Config.ACTION_ENGINEER_BAND_LOCK_COMMAND).apply {
            setPackage(targetPkg)
            putExtra(Config.EXTRA_BAND_LOCK_UNLOCK, unlock)
            putExtra(Config.EXTRA_BAND_LOCK_BANDS, bands)
            if (subId != null) putExtra(Config.EXTRA_BAND_LOCK_SUB_ID, subId)
            if (slotId != null) putExtra(Config.EXTRA_BAND_LOCK_SLOT_ID, slotId)
            if (keyInt != null) putExtra(Config.EXTRA_BAND_LOCK_KEY_INT, keyInt)
        }
    }

    val targets = listOf(Config.PHONE_TARGET_PKG, Config.ENGINEER_TARGET_PKG)
    targets.forEach { pkg ->
        Log.i(
            "NetworkHelper999",
            "[APP][cmd] send ENGINEER_BAND_LOCK_COMMAND target=$pkg unlock=$unlock bands=${bands.joinToString()} subId=$subId slotId=$slotId keyInt=$keyInt"
        )
        context.sendBroadcast(buildIntent(pkg))
    }
}

private fun sendBandLockQuery(
    context: Context,
    subId: Int?,
    slotId: Int?,
    keyInt: Int?
) {
    fun buildIntent(targetPkg: String): Intent {
        return Intent(Config.ACTION_ENGINEER_BAND_LOCK_QUERY).apply {
            setPackage(targetPkg)
            if (subId != null) putExtra(Config.EXTRA_BAND_LOCK_SUB_ID, subId)
            if (slotId != null) putExtra(Config.EXTRA_BAND_LOCK_SLOT_ID, slotId)
            if (keyInt != null) putExtra(Config.EXTRA_BAND_LOCK_KEY_INT, keyInt)
        }
    }

    val targets = listOf(Config.PHONE_TARGET_PKG, Config.ENGINEER_TARGET_PKG)
    targets.forEach { pkg ->
        Log.i(
            "NetworkHelper999",
            "[APP][query] send ENGINEER_BAND_LOCK_QUERY target=$pkg subId=$subId slotId=$slotId keyInt=$keyInt"
        )
        context.sendBroadcast(buildIntent(pkg))
    }
}

private fun sendBandModeCommand(
    context: Context,
    modeKeyInt: Int,
    subId: Int?,
    slotId: Int?
) {
    fun buildIntent(targetPkg: String): Intent {
        return Intent(Config.ACTION_ENGINEER_BAND_MODE_COMMAND).apply {
            setPackage(targetPkg)
            putExtra(Config.EXTRA_BAND_MODE_KEY_INT, modeKeyInt)
            if (subId != null) putExtra(Config.EXTRA_BAND_LOCK_SUB_ID, subId)
            if (slotId != null) putExtra(Config.EXTRA_BAND_LOCK_SLOT_ID, slotId)
        }
    }

    val targets = listOf(Config.PHONE_TARGET_PKG, Config.ENGINEER_TARGET_PKG)
    targets.forEach { pkg ->
        Log.i(
            "NetworkHelper999",
            "[APP][mode] send ENGINEER_BAND_MODE_COMMAND target=$pkg modeKeyInt=$modeKeyInt subId=$subId slotId=$slotId"
        )
        context.sendBroadcast(buildIntent(pkg))
    }
}
