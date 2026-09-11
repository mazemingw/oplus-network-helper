package com.nvmex.networkhelper.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.home.UpdateUiState
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.home.OplusVendor
import com.nvmex.networkhelper.util.home.execSu
import com.nvmex.networkhelper.util.network.NetworkPanelWithPermissionGate
import com.nvmex.networkhelper.ui.base.toast
import com.nvmex.networkhelper.ui.home.qos.QosDataTable
import com.nvmex.networkhelper.ui.network.NetworkPanelTabs
import com.nvmex.networkhelper.ui.onboarding.EnvironmentProbeStore
import com.nvmex.networkhelper.ui.settings.USAGE_TUTORIAL_URL
import com.nvmex.networkhelper.ui.settings.openUrl
import com.nvmex.networkhelper.ui.settings.rememberAppSettings
import com.nvmex.networkhelper.viewmodel.home.UpdateViewModel
import com.nvmex.networkhelper.viewmodel.signal.QosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    targetPkg: String,
    targetActivity: String,
    vendor: OplusVendor,
    onPanelState: (NetworkPanelUiState) -> Unit,
    contentBottomPadding: Dp = 0.dp,
    onOpenEngineerBandLock: () -> Unit = {},
    onOpenSignalComparison: () -> Unit = {},
    onPlaybackBarChange: (HomePlaybackBarState?) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scroll = rememberScrollState()

    var hasRoot by remember { mutableStateOf<Boolean?>(null) }

    val updateVm: UpdateViewModel = hiltViewModel()
    val updateUi by updateVm.ui.collectAsState()

    // ✅ Hilt 版 QoS VM：这里只订阅，不再注册广播
    val qosViewModel: QosViewModel = hiltViewModel()
    val currentQosBySubId by qosViewModel.currentQosBySubId.collectAsState()
    val globalEvent by qosViewModel.globalEvent.collectAsState()

    LaunchedEffect(Unit) {
        updateVm.checkOnce()
    }

    var showLogDialog by remember { mutableStateOf(false) }
    var lastLog by remember { mutableStateOf("") }
    var showBandHelp by remember { mutableStateOf(false) }
    var showEngineerGuidePrompt by remember { mutableStateOf(false) }
    var dontShowEngineerGuidePrompt by remember { mutableStateOf(false) }
    var showDevDialog by remember { mutableStateOf(false) }
    var titleTapCount by remember { mutableStateOf(0) }
    var lastTitleTapAt by remember { mutableStateOf(0L) }

    var simSelectedIndex by remember { mutableStateOf(0) }
    var currentPanelState by remember { mutableStateOf(NetworkPanelUiState()) }
    var allPanelStates by remember { mutableStateOf<Map<Int, NetworkPanelUiState>>(emptyMap()) }
    var playbackSample by remember { mutableStateOf<HomeLogSample?>(null) }
    val playbackStates = playbackSample?.panelBySubId
    val displayedPanelState = playbackStates
        ?.values
        ?.sortedBy { it.subId }
        ?.getOrNull(simSelectedIndex.coerceAtLeast(0))
        ?: currentPanelState
    val (settings, setSettings) = rememberAppSettings()

    LaunchedEffect(Unit) {
        hasRoot = withContext(Dispatchers.IO) { execSu("id").exitCode == 0 }
    }

    fun showLog(title: String, log: String) {
        lastLog = "[$title]\n\n${log.ifBlank { context.getString(R.string.home_no_output) }}"
        showLogDialog = true
    }

    fun launchEngineerMode() {
        scope.launch {
            val res = withContext(Dispatchers.IO) { execSu("am start -n $targetActivity") }
            if (res.exitCode == 0) {
                toast(context, "${context.getString(R.string.home_toast_root_started)} ✅")
            } else {
                val res2 = withContext(Dispatchers.IO) {
                    execSu("monkey -p $targetPkg -c android.intent.category.LAUNCHER 1")
                }
                if (res2.exitCode == 0) {
                    toast(context, "${context.getString(R.string.home_toast_monkey_started)} ✅")
                    showLog(context.getString(R.string.home_log_am_start_failed), res.output)
                } else {
                    toast(context, "${context.getString(R.string.home_toast_launch_failed)} ❌")
                    showLog(
                        context.getString(R.string.home_log_launch_failed),
                        "am start exit=${res.exitCode}\n${res.output}\n\n" +
                                "monkey exit=${res2.exitCode}\n${res2.output}"
                    )
                }
            }
        }
    }

    val onRootStart: () -> Unit = {
        if (settings.suppressEngineerGuidePrompt) {
            launchEngineerMode()
        } else {
            dontShowEngineerGuidePrompt = false
            showEngineerGuidePrompt = true
        }
        Unit
    }

    val onRootForceStop: () -> Unit = {
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                execSu("am force-stop $targetPkg")
            }
            if (res.exitCode == 0) {
                toast(context, "${context.getString(R.string.home_toast_force_stop_ok)} ✅")
            } else {
                toast(context, "${context.getString(R.string.home_toast_force_stop_failed)} ❌")
                showLog(context.getString(R.string.home_log_force_stop_failed), "exit=${res.exitCode}\n${res.output}")
            }
        }
        Unit
    }

    fun parseComponent(targetActivity: String): Pair<String, String>? {
        val parts = targetActivity.split("/")
        if (parts.size != 2) return null
        val pkg = parts[0].trim()
        val clsRaw = parts[1].trim()
        if (pkg.isBlank() || clsRaw.isBlank()) return null
        val cls = if (clsRaw.startsWith(".")) pkg + clsRaw else clsRaw
        return pkg to cls
    }

    fun isPackageInstalled(context: android.content.Context, pkg: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isActivityResolvable(context: android.content.Context, pkg: String, cls: String): Boolean {
        val intent = android.content.Intent().setClassName(pkg, cls)
        return intent.resolveActivity(context.packageManager) != null
    }

    val onCheckSuEnv: () -> Unit = {
        scope.launch {
            val res = withContext(Dispatchers.IO) { execSu("id") }

            val sb = StringBuilder()
            sb.appendLine("exit=${res.exitCode}")
            sb.appendLine(res.output)
            sb.appendLine()
            sb.appendLine(context.getString(R.string.home_target_check_section))
            sb.appendLine("targetPkg = $targetPkg")
            sb.appendLine("targetActivity = $targetActivity")

            val pkgOk = isPackageInstalled(context, targetPkg)
            sb.appendLine(context.getString(R.string.home_target_pkg_exists, if (pkgOk) "✅" else "❌"))

            val comp = parseComponent(targetActivity)
            if (comp == null) {
                sb.appendLine("❌ ${context.getString(R.string.home_activity_format_failed)}")
            } else {
                val (pkgInActivity, cls) = comp
                sb.appendLine(context.getString(R.string.home_activity_pkg, pkgInActivity))
                sb.appendLine(context.getString(R.string.home_activity_class, cls))

                val samePkg = (pkgInActivity == targetPkg)
                sb.appendLine(
                    context.getString(
                        R.string.home_pkg_same,
                        if (samePkg) "✅" else "❌ (${context.getString(R.string.home_pkg_mismatch_detail)})"
                    )
                )

                val actOk = isActivityResolvable(context, pkgInActivity, cls)
                sb.appendLine(
                    context.getString(
                        R.string.home_activity_resolvable,
                        if (actOk) "✅" else "❌ (${context.getString(R.string.home_activity_unresolvable_detail)})"
                    )
                )
            }

            sb.appendLine()
            sb.appendLine(context.getString(R.string.home_lsposed_scope_section))
            val frameworkConnected = EnvironmentProbeStore.readFrameworkConnected(context)
            val refreshOk = withContext(Dispatchers.IO) {
                EnvironmentProbeStore.refreshConfiguredScopes(context)
            }
            val scopeStatuses = EnvironmentProbeStore.readSupportScopeStatuses(context)
            sb.appendLine(context.getString(R.string.home_framework_connected, if (frameworkConnected) "✅" else "❌"))
            sb.appendLine(
                context.getString(
                    R.string.home_scope_read,
                    if (refreshOk) "✅ (${context.getString(R.string.home_scope_realtime)})"
                    else "⚠ ${context.getString(R.string.home_scope_cached)}"
                )
            )
            scopeStatuses.forEach { status ->
                val scopeLabel = supportScopeLabel(
                    context = context,
                    packageName = status.packageName,
                    fallback = status.label
                )
                sb.appendLine(
                    "$scopeLabel (${status.packageName}): ${
                        if (status.configured) "✅" else "❌"
                    }"
                )
            }
            val allScopesConfigured = scopeStatuses.all { it.configured }
            sb.appendLine(context.getString(R.string.home_scope_complete, if (allScopesConfigured) "✅" else "❌"))

            showLog(context.getString(R.string.home_su_check_title), sb.toString())

            val toastMsg = when {
                res.exitCode != 0 -> "${context.getString(R.string.home_toast_su_unavailable)} ❌"
                !pkgOk -> "${context.getString(R.string.home_toast_target_missing)} ❌"
                comp == null -> "${context.getString(R.string.home_toast_activity_bad_format)} ❌"
                else -> "su ✅ ${context.getString(R.string.home_toast_target_check_done)} ✅"
            }
            toast(context, toastMsg)
        }
        Unit
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!settings.hideHomeTopBar) {
                    Text(
                        text = stringResource(R.string.app_name_networkhelper),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.clickable {
                            val now = System.currentTimeMillis()
                            titleTapCount = if (now - lastTitleTapAt <= 1600L) titleTapCount + 1 else 1
                            lastTitleTapAt = now
                            if (titleTapCount >= 7) {
                                titleTapCount = 0
                                showDevDialog = true
                            }
                        }
                    )
                }

                NetworkPanelWithPermissionGate {
                    NetworkPanelTabs(
                        selectedIndex = simSelectedIndex,
                        onSelectedIndexChange = { simSelectedIndex = it },
                        onCurrentState = {
                            currentPanelState = it
                            onPanelState(it)
                        },
                        onShowBandHelp = { showBandHelp = true },
                        onLaunchEngineerMode = if (playbackSample == null) onRootStart else null,
                        onOpenEngineerBandLock = if (playbackSample == null) onOpenEngineerBandLock else null,
                        onOpenSignalComparison = onOpenSignalComparison,
                        onAllStates = { allPanelStates = it },
                        overrideStates = playbackStates,
                        overrideSubIds = playbackStates?.keys?.sorted(),
                        playbackFrameIndex = playbackSample?.playbackIndex,
                        playbackSessionKey = playbackSample?.playbackSession,
                        showTopBar = !settings.hideHomeTopBar
                    )
                }

                if (settings.enableSignalChart) {
                    SignalChartCard(
                        sample = displayedPanelState,
                        modifier = Modifier.fillMaxWidth(),
                        windowSeconds = 60,
                        autoRange = settings.autoRangeChart,
                        useEChartsChart = settings.enableEChartsChart,
                        playbackSampleKey = playbackSample?.ts,
                        playbackFrameIndex = playbackSample?.playbackIndex,
                        playbackSessionKey = playbackSample?.playbackSession,
                    )
                }

                RootControlPanel(
                    hasRoot = hasRoot,
                    vendor = vendor,
                    onRootStart = onRootStart,
                    onRootForceStop = onRootForceStop,
                    onShowBandHelp = { showBandHelp = true },
                    onCheckSuEnv = onCheckSuEnv
                )

                // ✅ 这里只传同一个 Hilt VM
                QosDataTable(
                    viewModel = qosViewModel,
                    overrideQosBySubId = playbackSample?.qosBySubId,
                    overrideGlobalEvent = playbackSample?.globalEvent
                )

                HomeLogRecorderPanel(
                    panelState = currentPanelState,
                    panelStates = allPanelStates,
                    qosBySubId = currentQosBySubId,
                    globalEvent = globalEvent,
                    onPlaybackSampleChange = { playbackSample = it },
                    onPlaybackBarChange = onPlaybackBarChange,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp + contentBottomPadding))
            }
        }

        if (showLogDialog) {
            AlertDialog(
                onDismissRequest = { showLogDialog = false },
                confirmButton = { TextButton(onClick = { showLogDialog = false }) { Text("OK") } },
                title = { Text(stringResource(R.string.home_execution_log)) },
                text = { Text(lastLog, style = MaterialTheme.typography.bodySmall) }
            )
        }

        if (showEngineerGuidePrompt) {
            AlertDialog(
                onDismissRequest = { showEngineerGuidePrompt = false },
                title = { Text(stringResource(R.string.home_view_tutorial_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.home_view_tutorial_desc),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            Checkbox(
                                checked = dontShowEngineerGuidePrompt,
                                onCheckedChange = { dontShowEngineerGuidePrompt = it }
                            )
                            Text(
                                text = stringResource(R.string.home_dont_show_again),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (dontShowEngineerGuidePrompt) {
                                setSettings(settings.copy(suppressEngineerGuidePrompt = true))
                            }
                            showEngineerGuidePrompt = false
                            openUrl(context, USAGE_TUTORIAL_URL)
                        }
                    ) {
                        Text(stringResource(R.string.home_view_tutorial))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            if (dontShowEngineerGuidePrompt) {
                                setSettings(settings.copy(suppressEngineerGuidePrompt = true))
                            }
                            showEngineerGuidePrompt = false
                            launchEngineerMode()
                        }
                    ) {
                        Text(stringResource(R.string.home_continue_launch))
                    }
                }
            )
        }

        BandLockHelpDialog(
            show = showBandHelp,
            state = currentPanelState,
            onDismiss = { showBandHelp = false }
        )

        DeveloperAuthDialog(
            show = showDevDialog,
            onDismiss = { showDevDialog = false }
        )

        when (val s = updateUi) {
            is UpdateUiState.Available -> {
                UpdateDialog(
                    info = s.info,
                    mandatory = s.mandatory,
                    downloadProgress = updateVm.downloadProgress.collectAsState().value,
                    downloadSpeed = updateVm.downloadSpeed.collectAsState().value,
                    onDismiss = { updateVm.dismissIfAllowed() },
                    onSkipThisVersion = { updateVm.skipCurrentVersion() },
                    onUpdate = {
                        updateVm.downloadAndInstallApk(context)
                    }
                )
            }
            else -> Unit
        }
    }
}

private fun supportScopeLabel(context: Context, packageName: String, fallback: String): String {
    val resId = when (packageName) {
        "com.android.phone" -> R.string.home_scope_phone_service
        "com.oplus.engineernetwork" -> R.string.home_scope_engineer_mode
        "com.oplus.subsys" -> R.string.home_scope_subsystem
        "android" -> R.string.home_scope_system_service
        else -> 0
    }
    return if (resId != 0) context.getString(resId) else fallback
}
