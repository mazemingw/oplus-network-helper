package dev.qcom.efs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val UiState.cloudBackupStatus: String get() = when {
    cloudBackupEnabled == false -> "云备份已关闭"
    cloudBackupComplete -> "云端已校验"
    cloudBackupEnabled == null -> "云备份待选择"
    else -> "云端未完成"
}

@Composable
internal fun CloudBackupChoiceDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("是否将分区备份保存到云端？") },
        text = { Text("完整备份会先保存在手机上。开启云备份后，应用会将 EFS/NV 分区镜像上传至私有云，并用机型和 IMEI1 关联备份，方便本地副本损坏时找回。以后可在备份管理中修改设置或删除云端副本。") },
        confirmButton = { TextButton(onClick = { vm.chooseCloudBackup(true) }) { Text("保存到云端") } },
        dismissButton = { TextButton(onClick = { vm.chooseCloudBackup(false) }) { Text("仅保存在本机") } },
    )
}

@Composable
internal fun CloudBackupManagementDialog(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<CloudBackupEntry>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CloudBackupEntry?>(null) }
    LaunchedEffect(refresh) {
        loadError = null
        try { entries = vm.listCloudBackups() }
        catch (t: Exception) {
            if (t is CancellationException) throw t
            loadError = t.message ?: "无法读取云备份列表"
        }
    }
    val backup = selected
    if (backup != null) {
        AlertDialog(
            onDismissRequest = { if (!deleting) selected = null },
            title = { Text("删除这份云备份？") },
            text = { Text("删除后将无法再从云端恢复此副本。保存在本机的备份可以继续用于恢复。") },
            confirmButton = { TextButton(enabled = !deleting && !state.busy, onClick = {
                deleting = true
                scope.launch {
                    try {
                        vm.deleteCloudBackup(backup.id)
                        entries = entries.orEmpty().filter { it.id != backup.id }
                        deleteError = null
                    } catch (t: Exception) {
                        if (t is CancellationException) throw t
                        deleteError = t.message ?: "删除云备份失败"
                    } finally {
                        deleting = false
                        selected = null
                    }
                }
            }) { Text(if (deleting) "正在删除…" else "删除云端副本") } },
            dismissButton = { TextButton(enabled = !deleting, onClick = { selected = null }) { Text("取消") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("备份管理") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.initialBackupComplete) "本地分区备份已校验" else "本地分区备份未完成")
                state.initialBackupError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.cloudBackupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.cloudBackupEnabled != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("备份时自动上云", Modifier.weight(1f))
                    Switch(checked = state.cloudBackupEnabled, enabled = !state.busy,
                        onCheckedChange = { vm.setCloudBackupEnabled(it) })
                }
                Text("云端副本", style = MaterialTheme.typography.labelLarge)
                deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    loadError != null -> {
                        Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { refresh++ }) { Text("重新加载") }
                    }
                    entries == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    entries.isNullOrEmpty() -> Text("暂无云备份")
                    else -> entries.orEmpty().forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.createdAt.take(19).replace('T', ' '))
                                Text(Formatter.formatFileSize(context, item.size), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(enabled = !state.busy, onClick = { selected = item }) { Text("删除") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (state.initialBackupComplete && state.cloudBackupEnabled == true && !state.cloudBackupComplete) {
                TextButton(enabled = !state.busy, onClick = { onDismiss(); vm.retryCloudBackup() }) { Text("上传本地备份") }
            }
        },
    )
}

@Composable
internal fun BackupBypassDialog(state: UiState, vm: MainViewModel) {
    val red = Color(0xFF8B0000)
    AlertDialog(
        onDismissRequest = vm::cancelBackupBypass,
        containerColor = red, titleContentColor = Color.White, textContentColor = Color.White,
        title = { Text(stringResource(R.string.efs_backup_skip_title)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.efs_backup_skip_warning))
                Text(stringResource(R.string.efs_backup_skip_partitions), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(state.backupPartitionNames.joinToString("\n"), fontFamily = FontFamily.Monospace) }
                state.initialBackupError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = vm::confirmBackupBypass, enabled = state.backupBypassRemaining == 0 && state.canOfferBackupBypass,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = red,
                    disabledContainerColor = Color.White.copy(alpha = 0.2f), disabledContentColor = Color.White.copy(alpha = 0.7f))) {
                Text(if (state.backupBypassRemaining > 0) stringResource(R.string.efs_backup_skip_wait, state.backupBypassRemaining)
                    else stringResource(R.string.efs_backup_skip_continue))
            }
        },
        dismissButton = { TextButton(onClick = vm::cancelBackupBypass, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
            Text(stringResource(R.string.efs_backup_skip_cancel))
        } },
    )
}

@Composable
internal fun StoragePermissionScreen(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("先授权并备份 EFS/NV 分区", style = MaterialTheme.typography.headlineSmall)
        Text("请在系统设置中授予“所有文件访问权限”，用于将全量 EFS 备份、功能原值和诊断日志保存到：")
        SelectionContainer { Text("Download/EFS-BACKUP/", fontFamily = FontFamily.Monospace) }
        Text("授权并连接后，会保存并校验 EFS/NV 完整分区镜像。首次备份时可以选择是否上传私有云。通过本地校验后可启用写入。若本地备份失败，可阅读风险提示后选择强制跳过。")
        Text("功能恢复优先读取应用内的原值副本；本地副本缺失或损坏时，读取公共备份目录中的副本。")
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("授予文件访问权限") }
    }
}

@Composable
internal fun BackupProgressDialog(state: UiState) {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (state.backupIsRaw) "EFS/NV 原始分区备份" else "EFS 文件导出") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.backupMessage ?: "正在读取基带文件…")
                Text("保存位置：Download/EFS-BACKUP/\n请保持应用在前台。" +
                    if (state.backupIsRaw) "正在读取完整块设备，检查采集前后摘要及镜像长度。任一分区失败都不会标记为成功。"
                    else "逐文件导出用于查看或恢复单个配置项，失败条目会记录到日志。",
                    style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(state.log.takeLast(8).joinToString("\n"),
                        modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        },
        confirmButton = {},
    )
}
