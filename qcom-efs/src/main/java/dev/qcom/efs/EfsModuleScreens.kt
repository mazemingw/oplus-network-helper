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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.qcom.efs.features.ALL_FEATURES
import dev.qcom.efs.features.FeatureStatus

@Composable private fun ModuleLifecycle(vm: MainViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.enterModule() }
        owner.lifecycle.addObserver(observer)
        vm.enterModule()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable fun EfsExplorerScreen(vm: MainViewModel, onBack: () -> Unit, onFeatures: () -> Unit) {
    if (!rememberQualcommPlatform()) { UnsupportedPlatform(onBack); return }
    ModuleLifecycle(vm)
    App(vm, onBack, onFeatures)
    val state by vm.state.collectAsState()
    if (state.backupBypassDialog) BackupBypassDialog(state, vm)
}

@Composable internal fun BackupChainStatus(state: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("完整分区备份：${if (state.initialBackupComplete) "本地已校验" else "本地未完成"} · ${if (state.cloudBackupComplete) "云端已校验" else "云端未完成"}",
            color = if (state.initialBackupComplete && state.cloudBackupComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        state.initialBackupError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.cloudBackupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.backupBypassed) Text(stringResource(R.string.efs_backup_bypassed), color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::requestRawBackup, enabled = !state.busy && state.phase == Phase.READY) { Text("分区备份") }
            TextButton(onClick = vm::retryCloudBackup, enabled = !state.busy && state.initialBackupComplete) { Text("验证/重试云备份") }
        }
        if (state.canOfferBackupBypass) TextButton(onClick = vm::requestBackupBypass,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.efs_backup_force_skip)) }
    }
}

@Composable fun ModemFeaturesScreen(vm: MainViewModel, onBack: () -> Unit, defaultDataSlot: Int? = null,
    defaultDataIcon: @Composable (Boolean) -> Unit = {}) {
    if (!rememberQualcommPlatform()) { UnsupportedPlatform(onBack); return }
    ModuleLifecycle(vm)
    CompactModemFeaturesScreen(vm, onBack, defaultDataSlot, defaultDataIcon)
    val state by vm.state.collectAsState()
    if (state.backupBypassDialog) BackupBypassDialog(state, vm)
}

@Composable private fun UnsupportedPlatform(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("仅支持已确认的高通处理器平台")
        Text("其他处理器或无法识别的平台不会启动 EFS 连接、备份或恢复。")
        TextButton(onClick = onBack) { Text("返回") }
    }
}
