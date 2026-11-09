package dev.qcom.efs.features.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.qcom.efs.chineseMessage
import dev.qcom.efs.FeaturesState
import dev.qcom.efs.features.FeatureStatus
import dev.qcom.efs.features.ALL_FEATURES
import dev.qcom.efs.features.featureRisk
import dev.qcom.efs.features.featureSwitchChecked

private const val DEFAULT_SPC = "000000"

@Composable
fun FeaturesDialog(
    state: FeaturesState,
    readOnly: Boolean,
    busy: Boolean,
    ssrDone: Boolean,
    onSimSlot: (Int) -> Unit,
    onEnableWrites: () -> Unit,
    onDisable: (id: String, spc: String) -> Unit,
    onRestore: (id: String, spc: String) -> Unit,
    onSuppressDisableWarning: () -> Unit,
    onSsr: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is FeaturesState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("基带功能与恢复") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(4.dp),
                        strokeWidth = 3.dp,
                    )
                    Text("正在读取基带功能状态…")
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
        )

        is FeaturesState.Ready -> ReadyBody(
            state = state,
            busy = busy,
            readOnly = readOnly,
            ssrDone = ssrDone,
            onSimSlot = onSimSlot,
            onEnableWrites = onEnableWrites,
            onDisable = onDisable,
            onRestore = onRestore,
            onSuppressDisableWarning = onSuppressDisableWarning,
            onSsr = onSsr,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ReadyBody(
    state: FeaturesState.Ready,
    busy: Boolean,
    readOnly: Boolean,
    ssrDone: Boolean,
    onSimSlot: (Int) -> Unit,
    onEnableWrites: () -> Unit,
    onDisable: (String, String) -> Unit,
    onRestore: (String, String) -> Unit,
    onSuppressDisableWarning: () -> Unit,
    onSsr: () -> Unit,
    onDismiss: () -> Unit,
) {
    val acting = state.statuses.values.any { it is FeatureStatus.Writing }
    var pendingDisable by remember { mutableStateOf<Pair<String, String>?>(null) }
    var suppressAfterDisable by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    var blockedMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!acting) onDismiss() },
        title = { Text("基带功能与恢复") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "SIM 0", 1 to "SIM 1").forEach { (slot, label) ->
                        FilterChip(
                            selected = state.simSlot == slot,
                            enabled = !acting,
                            onClick = { onSimSlot(slot) },
                            label = { Text(label) },
                        )
                    }
                }
                if (readOnly) {
                    HorizontalDivider()
                    Text(
                        text = "当前处于只读模式，请先启用写入，再修改功能配置。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onEnableWrites) {
                        Text("我已了解，启用写入")
                    }
                }
                Text(
                    "开启表示未检测到禁用配置，关闭表示禁用。禁用前自动保存原值，开启开关可恢复原值；修改后需保存应用。",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(ALL_FEATURES, key = { it.id }) { feature ->
                        val status = state.statuses[feature.id]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = feature.label.removePrefix("禁用 "),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(featureRisk(feature.id), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    (if (status is FeatureStatus.AlreadyDisabled) "已禁用" else "未禁用或待确认") +
                                        if (feature.id in state.restorable) " · 原值已备份" else " · 无原值记录",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                if (status is FeatureStatus.WriteError || status is FeatureStatus.ReadError) {
                                    Text(
                                        text = when (status) {
                                            is FeatureStatus.WriteError -> chineseMessage(status.message)
                                            is FeatureStatus.ReadError -> chineseMessage(status.message)
                                            else -> ""
                                        },
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (feature.id in state.restorable) {
                                TextButton(
                                    onClick = { if (readOnly) blockedMessage = "请先启用写入，再恢复功能配置。" else pendingRestore = feature.id },
                                    enabled = !acting && !busy,
                                ) { Text("恢复") }
                            }
                            if (status is FeatureStatus.Writing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(4.dp),
                                    strokeWidth = 3.dp,
                                )
                            } else {
                                Switch(
                                    checked = featureSwitchChecked(status),
                                    onCheckedChange = { on ->
                                        when {
                                            readOnly -> blockedMessage = "尚未允许修改，请先启用写入。"
                                            status is FeatureStatus.ReadError || status == null -> blockedMessage = "状态尚未确认，请重新读取后再操作。"
                                            on && feature.id !in state.restorable -> blockedMessage = "没有有效原值备份，无法安全恢复此功能。"
                                            on -> pendingRestore = feature.id
                                            state.warnBeforeDisable -> pendingDisable = feature.id to DEFAULT_SPC
                                            else -> onDisable(feature.id, DEFAULT_SPC)
                                        }
                                    },
                                    enabled = !busy && !acting,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                state.note?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "修改后点击“保存应用”生效，该操作会重启基带并短暂中断移动网络。",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Not gated on read-only: a restart writes nothing, and it is
                // exactly what someone who just re-armed the lock still needs.
                Button(
                    onClick = { onSsr() },
                    enabled = !busy && !acting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存应用" + if (ssrDone) " · 已完成" else "") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !acting) { Text("关闭") }
        },
    )

    blockedMessage?.let { message -> AlertDialog(onDismissRequest = { blockedMessage = null },
        title = { Text("暂不能修改") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { blockedMessage = null }) { Text("知道了") } }) }
    pendingRestore?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复禁用前原值？") },
            text = { Text("将优先读取本地原值记录，找不到或损坏时读取 Download/EFS-BACKUP/。原本不存在的配置项将删除，已有配置项写回完整原值。共用配置项的其他功能状态也可能变化。") },
            confirmButton = {
                TextButton(onClick = { pendingRestore = null; onRestore(id, DEFAULT_SPC) }) { Text("确认恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    }

    pendingDisable?.let { (id, spc) ->
        AlertDialog(
            onDismissRequest = {
                suppressAfterDisable = false
                pendingDisable = null
            },
            title = { Text("确认禁用此功能？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "此操作将直接写入基带 EFS，并可能改变基带" +
                                "行为。请先通过右上角菜单中的备份功能保存 EFS。禁用前会自动将原值保存到应用内和 Download/EFS-BACKUP/，任一保存失败则不执行禁用。",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = suppressAfterDisable,
                            onCheckedChange = { suppressAfterDisable = it },
                        )
                        Text("不再提醒")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val suppress = suppressAfterDisable
                    suppressAfterDisable = false
                    pendingDisable = null
                    if (suppress) onSuppressDisableWarning()
                    onDisable(id, spc)
                }) { Text("确认禁用") }
            },
            dismissButton = {
                TextButton(onClick = {
                    suppressAfterDisable = false
                    pendingDisable = null
                }) { Text("取消") }
            },
        )
    }
}
