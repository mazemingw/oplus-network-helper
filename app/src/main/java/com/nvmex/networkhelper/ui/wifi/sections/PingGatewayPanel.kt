package com.nvmex.networkhelper.ui.wifi.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.ui.settings.rememberAppSettings
import com.nvmex.networkhelper.viewmodel.wifi.PingViewModel

@Composable
fun PingGatewayPanel(
    modifier: Modifier = Modifier,
    wifiConnected: Boolean,
    hiPerfEnabled: Boolean,
    lowLatencyEnabled: Boolean,
    sampleIntervalMs: Long = 1000L,
    vm: PingViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()
    val (settings, _) = rememberAppSettings()

    DisposableEffect(wifiConnected, sampleIntervalMs) {
        if (wifiConnected) {
            vm.start(intervalMs = sampleIntervalMs, windowSec = 60)
        } else {
            vm.stop(reset = true)
        }
        onDispose { vm.stop() }
    }

    val windowSeconds = 60
    val marks = remember { mutableStateListOf<Int>() }

    var lastHi by remember { mutableStateOf(hiPerfEnabled) }
    var lastLow by remember { mutableStateOf(lowLatencyEnabled) }
    var lastTs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(ui.samples.lastOrNull()?.t) {
        val ts = ui.samples.lastOrNull()?.t ?: 0L

        if (ts == 0L) {
            marks.clear()
            lastTs = 0L
            return@LaunchedEffect
        }

        val advanced = (lastTs != 0L && ts != lastTs)
        val windowFull = ui.samples.size >= windowSeconds

        if (windowFull && advanced) {
            marks.replaceAll { it - 1 }
            marks.removeIf { it < 0 }
        }

        lastTs = ts
    }

    LaunchedEffect(hiPerfEnabled, lowLatencyEnabled, ui.samples.size, wifiConnected) {
        val changed = (hiPerfEnabled != lastHi) || (lowLatencyEnabled != lastLow)
        if (!changed) return@LaunchedEffect

        if (wifiConnected && ui.samples.isNotEmpty()) {
            val idx = (minOf(ui.samples.size, windowSeconds) - 1).coerceAtLeast(0)
            marks.add(idx)
            if (marks.size > 40) {
                marks.removeAt(0)
            }
        }

        lastHi = hiPerfEnabled
        lastLow = lowLatencyEnabled
    }

    val latestMs: Double? = ui.samples.lastOrNull()?.ms
    val latestOkMs: Double? = ui.samples.lastOrNull { it.ms != null }?.ms
    val hasChart = wifiConnected && ui.samples.isNotEmpty()

    val hudText = when {
        !wifiConnected -> stringResource(R.string.wifi_ping_paused)
        latestMs != null -> String.format("%.1f ms", latestMs)
        latestOkMs != null -> stringResource(R.string.wifi_ping_timeout_last, String.format("%.1f", latestOkMs))
        else -> "--"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.wifi_ping_title, windowSeconds),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = hudText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Text(
                text = stringResource(R.string.wifi_gateway, ui.gatewayIp ?: "-"),
                style = MaterialTheme.typography.bodySmall
            )

            if (hasChart) {
                PingLineChart(
                    samples = ui.samples,
                    windowSeconds = windowSeconds,
                    modifier = Modifier.fillMaxWidth(),
                    yMaxMs = null,
                    showGrid = true,
                    showMinMax = true,
                    marks = marks,
                    useEChartsChart = settings.enableEChartsChart
                )

                val minText = ui.minMs?.let { String.format("%.1f ms", it) } ?: "-"
                val maxText = ui.maxMs?.let { String.format("%.1f ms", it) } ?: "-"
                val avgText = ui.avgMs?.let { String.format("%.1f ms", it) } ?: "-"
                val lossText = String.format("%.1f%%", ui.lossRate * 100)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.wifi_ping_min, minText), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.wifi_ping_avg, avgText), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.wifi_ping_max, maxText), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.wifi_ping_loss, lossText), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    text = if (wifiConnected) {
                        stringResource(R.string.wifi_ping_sampling)
                    } else {
                        stringResource(R.string.wifi_ping_disconnected)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!ui.lastError.isNullOrBlank()) {
                Text(
                    text = "⚠ ${ui.lastError}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
