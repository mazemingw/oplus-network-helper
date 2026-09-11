package com.nvmex.networkhelper.ui.wifi.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.viewmodel.wifi.WifiTuningViewModel

@Composable
fun WifiTuningPanel(
    modifier: Modifier = Modifier,
    vm: WifiTuningViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()

    // ✅ 控制 Dialog 显示
    var showHelp by remember { mutableStateOf(false) }

    // 你之前的 OFF 蓝色
    val offBlue = Color(0xFF448AFF)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            // ✅ 标题行：左标题 + 右帮助按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.wifi_turbo_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                IconButton(
                    onClick = { showHelp = true },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.help),
                        contentDescription = stringResource(R.string.action_help),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            }

            Text(stringResource(R.string.wifi_turbo_desc))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val hiPerfOn = ui.hiPerfEnabled
                FilledTonalButton(
                    onClick = {
                        if (!hiPerfOn && ui.lowLatencyEnabled) vm.toggleLowLatency()
                        vm.toggleHiPerf()
                    },
                    enabled = ui.hasRoot && !ui.busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (hiPerfOn) MaterialTheme.colorScheme.error else offBlue,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Text(
                        stringResource(R.string.wifi_turbo_high_perf, if (hiPerfOn) "ON" else "OFF"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                val lowLatOn = ui.lowLatencyEnabled
                FilledTonalButton(
                    onClick = {
                        if (!lowLatOn && ui.hiPerfEnabled) vm.toggleHiPerf()
                        vm.toggleLowLatency()
                    },
                    enabled = ui.hasRoot && !ui.busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (lowLatOn) MaterialTheme.colorScheme.error else offBlue,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Text(
                        stringResource(R.string.wifi_turbo_low_latency, if (lowLatOn) "ON" else "OFF"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            ui.lastMsg?.let { message ->
                Text(
                    text = stringResource(message.resId, *message.args.toTypedArray()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        }
    }

    // ✅ 帮助弹窗（放在 Card 外，避免布局嵌套干扰）
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.wifi_turbo_help_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wifi_turbo_help_1))
                    Text(stringResource(R.string.wifi_turbo_help_2))
                    Text(stringResource(R.string.wifi_turbo_help_3))
                    Text(stringResource(R.string.wifi_turbo_help_4))
                    Text(stringResource(R.string.wifi_turbo_help_5))
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }
}
