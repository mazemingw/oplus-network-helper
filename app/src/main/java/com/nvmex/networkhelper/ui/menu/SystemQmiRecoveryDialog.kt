package com.nvmex.networkhelper.ui.menu

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.util.modem.ModemDriver
import com.nvmex.networkhelper.viewmodel.menu.ModemDriversViewModel

@Composable
internal fun SystemQmiRecoveryDialog(vm: ModemDriversViewModel, releaseLegacy: suspend () -> Unit,
                                     nativeReady: suspend () -> Unit) {
    val visible by vm.systemDialog.collectAsState()
    if (!visible) return
    val state by vm.systemRecovery.collectAsState()
    val busy by vm.busy.collectAsState()
    val reason by vm.systemReason.collectAsState()
    val capacity by vm.capacityFailure.collectAsState()
    val progress by vm.recoveryProgress.collectAsState()
    val driver by vm.selected.collectAsState()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var acceptImpact by remember { mutableStateOf(false) }
    val available = state.services.filter { it.canPause }.map { it.component.service }.toSet()
    LaunchedEffect(available, state.decideForMe, capacity) {
        selected = if (state.decideForMe && capacity) state.services.filter { it.canPause && it.component.suggest }
            .map { it.component.service }.toSet() else if (state.decideForMe) emptySet() else selected.intersect(available)
    }
    val highImpact = state.services.any { it.component.service in selected && !it.component.suggest }
    val shape = RoundedCornerShape(4.dp)
    AlertDialog(onDismissRequest = vm::dismissSystemComponents,
        title = { Text("系统组件与 QMI 通道") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(reason, style = MaterialTheme.typography.bodySmall)
                Text("以下为已知 NAS 使用组件；运行状态不等于实时 NAS 占用。身份未匹配的组件不可操作。",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (driver == ModemDriver.ORIGINAL_QMI)
                    "当前驱动验证控制 + 监测两路 NAS 客户端同时可用；暂停一项后重试，不足再尝试下一项。"
                    else "按当前后端实际读取结果验证；一项服务可能持有多个客户端，数量不能直接换算。",
                    style = MaterialTheme.typography.bodySmall)
                if (state.services.isEmpty()) Text("尚未读取组件，或设备没有匹配的 init 服务。")
                state.services.forEach { row ->
                    OutlinedCard(Modifier.fillMaxWidth(), shape = shape) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = row.component.service in selected, enabled = !busy && row.canPause,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + row.component.service else selected - row.component.service
                                        acceptImpact = false
                                    })
                                Column(Modifier.weight(1f)) {
                                    Text(row.component.title, style = MaterialTheme.typography.labelLarge)
                                    Text(row.component.service, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text("${row.state}${if (row.pid.isNotBlank()) " · PID ${row.pid}" else ""} · " +
                                when {
                                    row.pausedByUs -> "助手已暂停 / 待恢复"
                                    row.state == "stopped" -> "原已停止，自动恢复不会启动它"
                                    !row.verified -> "定义或进程未匹配，禁止操作"
                                    else -> "身份已核对"
                                }, style = MaterialTheme.typography.bodySmall)
                            Text(row.component.role, style = MaterialTheme.typography.bodySmall)
                            Text(row.component.impact, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if (Build.MODEL == "PLK110" && Build.DISPLAY.contains("17.0.0.105")) {
                                Text("本机报告正常运行时持有 ${row.component.reportedNas} 个 NAS 客户端（非实时计数）",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            if (row.canResume) OutlinedButton(shape = shape, enabled = !busy,
                                onClick = { vm.resumeSystemComponent(row.component.service, releaseLegacy, nativeReady) }) {
                                Text("恢复此组件")
                            }
                        }
                    }
                }
                if (highImpact) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acceptImpact, onCheckedChange = { acceptImpact = it }, enabled = !busy)
                    Text("我已了解上述影响，确认暂停所选核心电话 / 无线服务。", style = MaterialTheme.typography.bodySmall)
                }
                if (progress.isNotBlank()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = vm::inspectSystemComponents, enabled = !busy, shape = shape) { Text("刷新组件") }
                if (state.services.any { it.pausedByUs }) OutlinedButton(shape = shape, enabled = !busy,
                    onClick = { vm.resumeSystemComponent(null, releaseLegacy, nativeReady) }) { Text("恢复助手暂停的组件") }
                Text(if (state.autoRestore) "自动恢复已开启，应用退出后恢复助手暂停的组件。"
                    else "自动恢复已关闭；退出应用后组件保持暂停，可在此手动恢复或开启自动恢复。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = !busy && selected.isNotEmpty() && (!highImpact || acceptImpact), shape = shape,
                onClick = { vm.pauseAndRetry(state.services.map { it.component.service }.filter { it in selected }, releaseLegacy, nativeReady) }) {
                Text("暂停所选并重试")
            }
        },
        dismissButton = { TextButton(onClick = vm::dismissSystemComponents, enabled = !busy) { Text("关闭") } })
}
