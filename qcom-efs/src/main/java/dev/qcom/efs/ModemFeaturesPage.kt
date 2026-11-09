package dev.qcom.efs

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.qcom.efs.features.*

private val actionShape = RoundedCornerShape(4.dp)
private val connectedGreen = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CompactModemFeaturesScreen(vm: MainViewModel, onBack: () -> Unit, defaultDataSlot: Int? = null, defaultDataIcon: @Composable (Boolean) -> Unit = {}) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), vm::completeExport)
    val manual = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::prepareFullRecovery) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.enterModule() }
    val ready = state.features as? FeaturesState.Ready
    val acting = ready?.statuses?.values?.any { it is FeatureStatus.Writing } == true
    val busy = state.busy || acting || state.features is FeaturesState.Checking
    val connected = state.phase == Phase.READY
    val protected = state.backupWriteAuthorized
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var restoreAll by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showReference by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    var commonOpen by rememberSaveable(ready?.simSlot) { mutableStateOf(true) }
    var nsaOpen by rememberSaveable(ready?.simSlot) { mutableStateOf(false) }
    var unsupportedOpen by rememberSaveable(ready?.simSlot) { mutableStateOf(false) }
    val groups = ALL_FEATURES.groupBy { featureSection(it, ready?.statuses?.get(it.id)) }

    LaunchedEffect(state.phase, state.backupRunning, state.features) {
        if (connected && !state.backupRunning && state.features == null) vm.openFeatures(defaultDataSlot?.takeIf { it in 0..1 } ?: 0)
    }
    LaunchedEffect(state.toast) { state.toast?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.dismissToast() } }
    LaunchedEffect(ready?.note) { ready?.note?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(state.pendingExport) { state.pendingExport?.let { export.launch(it.suggestedName) } }
    BackHandler(enabled = acting || state.backupRunning) { }

    fun notify(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    fun requestFeature(id: String, restore: Boolean) {
        val reason = featureEditBlockReason(connected, protected, state.readOnly)
        if (reason != null) { notify(reason); return }
        val status = ready?.statuses?.get(id)
        if (restore && id !in ready?.restorable.orEmpty()) { notify("没有此功能的有效原值备份，无法安全恢复，请查看属性恢复参考。"); return }
        if (status == null || status is FeatureStatus.ReadError) { notify("状态尚未确认，请先重新连接并读取；可点击原因图标查看详情。"); return }
        val feature = ALL_FEATURES.first { it.id == id }
        if (featureSection(feature, status) == FeatureSection.UNSUPPORTED) { notify("当前基带不支持或拒绝访问此属性，请点击原因图标查看详情。"); return }
        pending = id to restore
    }
    if (showReference) {
        ModemFeatureReferenceScreen(ready?.simSlot ?: defaultDataSlot?.takeIf { it in 0..1 } ?: 0, ready?.originals.orEmpty()) { showReference = false }
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text("基带功能与恢复") }, navigationIcon = {
        IconButton(onClick = { if (!acting && !state.backupRunning) { vm.closeFeatures(); onBack() } }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        if (!state.storageGranted) Box(Modifier.padding(padding)) {
            StoragePermissionScreen {
                if (Build.VERSION.SDK_INT >= 30) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                        .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                } else permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::reconnect, modifier = Modifier.weight(1f), shape = actionShape,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        colors = if (connected) ButtonDefaults.buttonColors(containerColor = connectedGreen, contentColor = Color.White) else ButtonDefaults.buttonColors(),
                        enabled = !busy && state.phase != Phase.CONNECTING) {
                        Text(if (connected) "已连接" else if (state.phase == Phase.CONNECTING) "连接中…" else "重连基带")
                    }
                    OutlinedButton(onClick = vm::disconnect, modifier = Modifier.weight(1f), shape = actionShape,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        enabled = connected && !busy) { Text("断开基带") }
                    OutlinedButton(onClick = {
                        val reason = featureEditBlockReason(connected, protected, state.readOnly)
                        if (reason != null) notify(reason) else vm.saveAndApplyFeatures()
                    }, modifier = Modifier.weight(1f), shape = actionShape,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        enabled = !busy) { Text("保存应用") }
                }
                Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { showBackup = true },
                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = actionShape) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("备份", style = MaterialTheme.typography.labelLarge)
                        BackupBadge("本地", state.initialBackupComplete)
                        BackupBadge("云端", state.cloudBackupComplete)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, "查看备份详情", Modifier.size(18.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::requestRawBackup, Modifier.weight(1f), shape = actionShape,
                        contentPadding = PaddingValues(horizontal = 4.dp), enabled = connected && !busy) { Text("完整备份") }
                    OutlinedButton(onClick = { restoreAll = true }, Modifier.weight(1f), shape = actionShape,
                        contentPadding = PaddingValues(horizontal = 4.dp), enabled = !busy) { Text("全量恢复") }
                    OutlinedButton(onClick = { manual.launch(arrayOf("application/zip", "application/octet-stream")) },
                        Modifier.weight(1f), shape = actionShape, contentPadding = PaddingValues(horizontal = 4.dp), enabled = !busy) { Text("导入备份") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("允许修改", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = !state.readOnly, onCheckedChange = { allow ->
                        val reason = featureEditBlockReason(connected, if (allow) protected else true, false)
                        if (reason != null) notify(reason) else vm.toggleReadOnly()
                    }, enabled = !busy)
                    Spacer(Modifier.weight(1f))
                    listOf(0, 1).forEach { slot -> FilterChip(selected = ready?.simSlot == slot,
                        onClick = { vm.setFeatureSimSlot(slot) }, label = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("卡${slot + 1}")
                            if (defaultDataSlot == slot) defaultDataIcon(ready?.simSlot == slot)
                        } }, enabled = connected && !busy) }
                }
                Text("允许修改>开关项目>保存应用", style = MaterialTheme.typography.bodySmall)
                if (!protected) Text("备份完成后才能修改", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                if (state.backupBypassed) Text(stringResource(R.string.efs_backup_bypassed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                if (state.canOfferBackupBypass) TextButton(onClick = vm::requestBackupBypass,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.efs_backup_force_skip)) }
                if (state.phase == Phase.FAILED) Text("连接失败，请重连基带", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                if (state.features is FeaturesState.Checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.recoveryPackagePath?.let {
                    TextButton(onClick = vm::exportRecoveryPackage, enabled = !busy) { Text("保存恢复包") }
                }
            }
            for (section in FeatureSection.entries) {
                val features = groups[section].orEmpty()
                if (features.isEmpty()) continue
                val expanded = when (section) { FeatureSection.COMMON -> commonOpen; FeatureSection.NSA -> nsaOpen; FeatureSection.UNSUPPORTED -> unsupportedOpen }
                item(key = "section-${section.name}") {
                    Row(Modifier.fillMaxWidth().clickable {
                        when (section) { FeatureSection.COMMON -> commonOpen = !commonOpen; FeatureSection.NSA -> nsaOpen = !nsaOpen; FeatureSection.UNSUPPORTED -> unsupportedOpen = !unsupportedOpen }
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("${features.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "折叠" else "展开")
                    }
                }
                if (expanded) items(features, key = { it.id }) { feature ->
                    val status = ready?.statuses?.get(feature.id)
                    val restorable = feature.id in ready?.restorable.orEmpty()
                    Surface(shape = actionShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(feature.label.removePrefix("禁用 "), style = MaterialTheme.typography.bodyMedium)
                                Text(featureRisk(feature.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                when (status) {
                                    is FeatureStatus.AlreadyDisabled -> Text("已禁用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    is FeatureStatus.ReadError, is FeatureStatus.WriteError -> Text(if (section == FeatureSection.UNSUPPORTED) "基带拒绝访问" else "操作失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    is FeatureStatus.Writing -> Text("处理中…", style = MaterialTheme.typography.labelSmall)
                                    is FeatureStatus.CanDisable -> Text("未禁用", style = MaterialTheme.typography.labelSmall)
                                    else -> Text("状态待确认", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (status is FeatureStatus.ReadError || status is FeatureStatus.WriteError) IconButton(onClick = {
                                details = chineseMessage(if (status is FeatureStatus.ReadError) status.message else (status as FeatureStatus.WriteError).message)
                            }) { Icon(Icons.Default.Info, "查看原因", Modifier.size(18.dp)) }
                            if (restorable && section != FeatureSection.UNSUPPORTED) TextButton(onClick = { requestFeature(feature.id, true) }, enabled = !busy) { Text("还原") }
                            Switch(checked = featureSwitchChecked(status), onCheckedChange = { on -> requestFeature(feature.id, on) },
                                enabled = !busy)
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { showReference = true }, enabled = !busy) {
                    Text("属性值对照与 EFS 手动恢复参考", color = Color(0xFF1976D2))
                }
            }
        }
    }
    if (state.backupRunning) BackupProgressDialog(state)
    if (showBackup) AlertDialog(onDismissRequest = { showBackup = false }, title = { Text("备份状态") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本地：${if (state.initialBackupComplete) "已校验" else "未完成"}\n云端：${if (state.cloudBackupComplete) "已校验" else "未完成"}")
            if (!protected) (state.initialBackupError ?: state.cloudBackupMessage)?.let { Text(chineseMessage(it), style = MaterialTheme.typography.bodySmall) }
            Text("归档标识：机型 + IMEI1", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { showBackup = false }) { Text("关闭") } },
        dismissButton = { if (state.initialBackupComplete && !state.cloudBackupComplete) TextButton(onClick = { showBackup = false; vm.retryCloudBackup() }, enabled = !busy) { Text("重试云备份") } })
    details?.let { message -> AlertDialog(onDismissRequest = { details = null }, title = { Text("操作详情") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { details = null }) { Text("关闭") } }) }
    pending?.let { (id, restore) -> AlertDialog(onDismissRequest = { pending = null },
        title = { Text(if (restore) "还原此功能？" else "禁用此功能？") },
        text = { Text(if (restore) "写回修改前原值，共享配置可能影响其他功能。随后点击“保存应用”使修改生效。" else "保存原值后禁用该功能，随后点击“保存应用”使修改生效。") },
        confirmButton = { TextButton(onClick = { pending = null; if (restore) vm.restoreFeature(id, "000000") else vm.disableFeature(id, "000000") }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }) }
    if (restoreAll) AlertDialog(onDismissRequest = { restoreAll = false }, title = { Text("全量恢复") },
        text = { Text("生成离线恢复包，本地损坏时自动下载云端副本。请在 Recovery 中执行，当前不会刷写分区。") },
        confirmButton = { TextButton(onClick = { restoreAll = false; vm.prepareFullRecovery() }) { Text("生成恢复包") } },
        dismissButton = { TextButton(onClick = { restoreAll = false }) { Text("取消") } })
}

@Composable private fun BackupBadge(label: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(if (done) Icons.Default.CheckCircle else Icons.Default.Pending, null, Modifier.size(16.dp),
            tint = if (done) connectedGreen else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
