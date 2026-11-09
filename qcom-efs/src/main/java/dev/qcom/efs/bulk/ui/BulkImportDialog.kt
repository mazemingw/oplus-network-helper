package dev.qcom.efs.bulk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.qcom.efs.BulkResult
import dev.qcom.efs.BulkState
import dev.qcom.efs.bulk.BulkOp

/**
 * The bulk-import flow: preview (with the read-only and SPC guards), running
 * progress with a per-command result list, and the done summary with the
 * modem-restart button.  A dumb view of [BulkState]; the view model owns the
 * transitions.
 */
@Composable
fun BulkImportDialog(
    state: BulkState,
    readOnly: Boolean,
    busy: Boolean,
    ssrDone: Boolean,
    onSpcUnlock: (String) -> Unit,
    onEnableWrites: () -> Unit,
    onStart: (String) -> Unit,
    onSsr: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (state !is BulkState.Running) onDismiss() },
        title = { Text("批量导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    is BulkState.Preview -> PreviewBody(state, readOnly, onStart, onSpcUnlock, onEnableWrites)
                    is BulkState.Running -> RunningBody(state)
                    is BulkState.Done -> DoneBody(state, busy, ssrDone, onSsr)
                }
            }
        },
        confirmButton = {
            if (state !is BulkState.Running) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun PreviewBody(
    state: BulkState.Preview,
    readOnly: Boolean,
    onStart: (String) -> Unit,
    onSpcUnlock: (String) -> Unit,
    onEnableWrites: () -> Unit,
) {
    var spc by remember { mutableStateOf("000000") }

    Text(state.fileName, style = MaterialTheme.typography.titleSmall)

    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    } ?: run {
        Text("已解析 ${state.commands.size} 条操作")
        val writes = state.commands.count { it.op == BulkOp.WRITE }
        Text(
            "写入 $writes 项，删除 ${state.commands.size - writes} 项",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (readOnly) {
        HorizontalDivider()
        Text(
            "后台服务当前为只读模式，所有写入操作均被禁止。",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onEnableWrites) { Text("我已了解，启用写入") }
    }

    if (!readOnly && state.commands.isNotEmpty()) {
        HorizontalDivider()
        Text(
            "写入前需先使用服务编程码 " +
                    "SPC 解锁基带（常见值为 000000）。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = spc,
            onValueChange = { spc = it.filter(Char::isDigit).take(6) },
            label = { Text("SPC 服务编程码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        TextButton(onClick = { onSpcUnlock(spc) }, enabled = spc.length == 6) {
            Text("验证 SPC")
        }
        state.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { onStart(spc) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("开始导入") }
    }
}

@Composable
private fun RunningBody(state: BulkState.Running) {
    Text("正在导入… ${state.done} / ${state.commands.size}")
    LinearProgressIndicator(
        progress = { if (state.commands.isEmpty()) 0f else state.done.toFloat() / state.commands.size },
        modifier = Modifier.fillMaxWidth(),
    )
    ResultList(state.results)
}

@Composable
private fun DoneBody(state: BulkState.Done, busy: Boolean, ssrDone: Boolean, onSsr: () -> Unit) {
    val ok = state.results.count { it.ok }
    val fail = state.results.size - ok

    Text(
        "导入完成：成功 $ok 项，失败 $fail 项",
        color = if (fail == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
    state.summary?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    state.note?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.results.any { it.ok }) {
        Text(
            "修改后需重启基带才能生效。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (fail == 0) {
        // Enabled purely off the shared busy flag (same as the features
        // dialog): the SSR is a work{} launch, so the flag clears exactly
        // once the attempt finishes, success or failure.
        Button(
            onClick = onSsr,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重启基带（SSR）" + if (ssrDone) " · 已完成" else "")
        }
    }
    ResultList(state.results)
}

@Composable
private fun ResultList(results: List<BulkResult>) {
    if (results.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(results.size) {
        if (results.isNotEmpty()) listState.scrollToItem(results.size - 1)
    }
    LazyColumn(Modifier.heightIn(max = 240.dp)) {
        items(results) { r ->
            val opColor = if (r.op == BulkOp.WRITE) logBlue() else logOrange()
            val simColor = when (r.simTag) {
                "SIM0" -> logPurple()
                "SIM1" -> logTeal()
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (r.op == BulkOp.WRITE) "写" else "删",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = opColor,
                    maxLines = 1,
                )
                Text(
                    r.simTag,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = simColor,
                    maxLines = 1,
                )
                Text(
                    r.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (r.ok) "成功" else "失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            r.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        }
    }
}

// Palette ported from mtbtool's import log (write/delete + SIM0/SIM1).  Those
// hues are Material 300 shades, readable on a dark surface and washed out on a
// white one, so each pairs with an 800 shade for the light theme.
@Composable private fun logBlue() = themed(Color(0xFF4FC3F7), Color(0xFF0277BD))
@Composable private fun logOrange() = themed(Color(0xFFFF8A65), Color(0xFFBF360C))
@Composable private fun logPurple() = themed(Color(0xFFCE93D8), Color(0xFF6A1B9A))
@Composable private fun logTeal() = themed(Color(0xFF80CBC4), Color(0xFF00695C))

@Composable
private fun themed(onDark: Color, onLight: Color): Color =
    if (isSystemInDarkTheme()) onDark else onLight
