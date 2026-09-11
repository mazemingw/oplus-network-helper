package com.nvmex.networkhelper.hotspot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.SoftApConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.sections.HotspotActionsSection
import com.nvmex.networkhelper.hotspot.sections.HotspotCapabilitySection
import com.nvmex.networkhelper.hotspot.sections.HotspotConfigSheetContent
import com.nvmex.networkhelper.hotspot.sections.HotspotHeaderSection
import com.nvmex.networkhelper.hotspot.sections.HotspotStatusAndSnapshotPanel
import com.nvmex.networkhelper.hotspot.sections.bandwidthOptionsForBand
import com.nvmex.networkhelper.hotspot.sections.fallbackChannelOptionsForBand
import com.nvmex.networkhelper.util.shell.SuShellRunner
import kotlinx.coroutines.delay

private const val PREFS_NAME = "hotspot_prefs"
private const val KEY_HIDE_LEGAL_TIP = "hide_legal_tip"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotScreen(
    vm: HotspotViewModel = viewModel()
) {
    var ready by remember { mutableStateOf(false) }
    val suRunner = remember {
        SuShellRunner()
    }

    LaunchedEffect(Unit) {
        delay(300)
        ready = true
    }

    if (!ready) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    // 只有 ready 后再开始观察
    DisposableEffect(Unit) {
        vm.startObserving()
        onDispose { vm.stopObserving() }
    }

    HotspotPermissionGate {
        val context = LocalContext.current

        val ui by vm.ui.collectAsState()

        val prefs = remember(context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        var showLegalDialog by remember {
            mutableStateOf(!prefs.getBoolean(KEY_HIDE_LEGAL_TIP, false))
        }

        var showConfigSheet by remember { mutableStateOf(false) }
        var showChannelsDetail by remember { mutableStateOf(false) }

        // 表单：默认跟随快照
        var ssid by remember(ui.ssid) { mutableStateOf(ui.ssid ?: "NetworkHelper") }
        var passphrase by remember(ui.passphrase) { mutableStateOf(ui.passphrase ?: "") }
        var hidden by remember { mutableStateOf(false) }

        var selectedBand by remember(ui.configBand) {
            mutableStateOf(ui.configBand ?: SoftApConfiguration.BAND_2GHZ)
        }
        var selectedChannel by remember(ui.configChannel) {
            mutableStateOf(ui.configChannel ?: 0) // 0 = Auto
        }

        // 带宽（配置态 maxChannelBandwidth）
        var selectedBandwidth by remember(ui.configMaxBandwidth) {
            mutableStateOf(ui.configMaxBandwidth ?: SoftApConfigurationCompat.CHANNEL_WIDTH_AUTO)
        }

        // capability -> 当前 band 的候选信道
        val channelOptions = remember(
            selectedBand,
            ui.capChannels2g, ui.capChannels5g, ui.capChannels6g, ui.capChannels60g
        ) {
            when (selectedBand) {
                SoftApConfiguration.BAND_2GHZ -> ui.capChannels2g
                SoftApConfiguration.BAND_5GHZ -> ui.capChannels5g
                SoftApConfiguration.BAND_6GHZ -> ui.capChannels6g
                SoftApConfiguration.BAND_60GHZ -> ui.capChannels60g
                else -> emptyList()
            }.ifEmpty {
                fallbackChannelOptionsForBand(selectedBand)
            }
        }

        LaunchedEffect(selectedBand, channelOptions) {
            if (selectedChannel != 0 && channelOptions.none { it.channel == selectedChannel }) {
                selectedChannel = 0
            }
        }

        // 根据 band 给出“合理带宽候选”
        val bandwidthOptions = remember(selectedBand) {
            bandwidthOptionsForBand(selectedBand)
        }

        LaunchedEffect(selectedBand, bandwidthOptions) {
            if (!bandwidthOptions.contains(selectedBandwidth)) {
                selectedBandwidth = SoftApConfigurationCompat.CHANNEL_WIDTH_AUTO
            }
        }

        // ====== 1) Config Sheet ======
        if (showConfigSheet) {
            ModalBottomSheet(
                onDismissRequest = { showConfigSheet = false },
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                HotspotConfigSheetContent(
                    ssid = ssid,
                    onSsidChange = { ssid = it },
                    passphrase = passphrase,
                    onPassphraseChange = { passphrase = it },
                    hidden = hidden,
                    onHiddenChange = { hidden = it },

                    selectedBand = selectedBand,
                    onBandChange = { selectedBand = it },
                    selectedChannel = selectedChannel,
                    onChannelChange = { selectedChannel = it },
                    selectedBandwidth = selectedBandwidth,
                    onBandwidthChange = { selectedBandwidth = it },

                    capBand24 = ui.capBand24,
                    capBand5 = ui.capBand5,
                    capBand6 = ui.capBand6,
                    capBand60 = ui.capBand60,
                    channelOptions = channelOptions,
                    bandwidthOptions = bandwidthOptions,

                    configApplying = ui.configApplying,
                    onCancel = { showConfigSheet = false },
                    onApply = {
                        vm.applyConfig(
                            ssidText = ssid.trim(),
                            passphrase = passphrase,
                            hidden = hidden,
                            band = selectedBand,
                            channel = selectedChannel,
                            maxChannelBandwidth = selectedBandwidth
                        )
                        showConfigSheet = false
                    }
                )
            }
        }

        // Dialog 建议放在 Column 外面（避免被滚动布局影响），但你要保持原逻辑也没问题
        if (showLegalDialog) {
            HotspotLegalNoticeDialog(
                onClose = { showLegalDialog = false },
                onDontShowAgain = {
                    prefs.edit().putBoolean(KEY_HIDE_LEGAL_TIP, true).apply()
                    showLegalDialog = false
                }
            )
        }

        // ====== Main Content ======
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                    top = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HotspotHeaderSection(
                phase = ui.phase,
                tetherIfaces = ui.tetherIfaces,
                configApplyResult = ui.configApplyResult,
                lastError = ui.lastError,
                lastEvent = ui.lastEvent
            )

            HorizontalDivider()

            HotspotStatusAndSnapshotPanel(
                apState = ui.apState,
                apFailureReason = ui.apFailureReason,
                apBandText = ui.apBandText,
                apFrequencyMhz = ui.apFrequencyMhz,
                apChannel = ui.apChannel,
                apBandwidth = ui.apBandwidth,
                apWifiStandard = ui.apWifiStandard,
                apClients = ui.apClients,

                ssid = ui.ssid,
                passphrase = ui.passphrase,
                configBand = ui.configBand,
                configChannel = ui.configChannel,
                configMaxBandwidth = ui.configMaxBandwidth
            )

            HorizontalDivider()

            HotspotCapabilitySection(
                capCountryCode = ui.capCountryCode,
                capMaxClients = ui.capMaxClients,
                capBand24 = ui.capBand24,
                capBand5 = ui.capBand5,
                capBand6 = ui.capBand6,
                capBand60 = ui.capBand60,
                capChannels2g = ui.capChannels2g,
                capChannels5g = ui.capChannels5g,
                capChannels6g = ui.capChannels6g,
                capChannels60g = ui.capChannels60g,
                showChannelsDetail = showChannelsDetail,
                onToggleChannelsDetail = { showChannelsDetail = !showChannelsDetail },
                suRunner = suRunner
            )

            HorizontalDivider()

            HotspotActionsSection(
                phase = ui.phase,
                configApplying = ui.configApplying,
                onStart = { vm.startHotspot(showProvisioningUi = false) },
                onStop = { vm.stopHotspot() },
                onOpenConfig = { showConfigSheet = true },
                onRestart = { vm.restartHotspot() },
                onRefreshSnapshot = { vm.refreshConfigSnapshot() }
            )

            HorizontalDivider()

            PoweredByVpnHotspotLink()
        }
    }
}


@Composable
private fun HotspotLegalNoticeDialog(
    onClose: () -> Unit,
    onDontShowAgain: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.hotspot_legal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                Text(
                    stringResource(R.string.hotspot_legal_body),
                    style = MaterialTheme.typography.bodyMedium
                )

                // ⚠️ 红色重点提示
                Text(
                    stringResource(R.string.hotspot_legal_wifi_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDontShowAgain) { Text(stringResource(R.string.action_dont_show_again)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        }
    )
}


@Composable
private fun PoweredByVpnHotspotLink() {
    val context = LocalContext.current
    val url = "https://github.com/Mygod/VPNHotspot/tree/master"
    val linkColor = Color(0xFF448AFF)

    Text(
        text = "POWERED BY VPNHOTSPOT · GITHUB",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF448AFF),
        fontStyle = FontStyle.Italic,
        letterSpacing = 0.6.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
            .padding(vertical = 8.dp),
        textAlign = TextAlign.Center
    )

}





























