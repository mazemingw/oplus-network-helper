package dev.qcom.efs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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
        Text("授权并连接后，会自动保存 EFS/NV 完整分区镜像，并打包上传到私有云。默认须通过本地与云端校验才允许启用写入；备份失败时，可阅读风险提示后自行选择强制跳过。逐文件 ZIP 可在菜单中另行导出。")
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
