package com.nvmex.networkhelper.ui.network.sections

import android.telephony.SubscriptionManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.network.SocVendorDetector
import com.nvmex.networkhelper.util.qmi.QmiModemClient
import com.nvmex.networkhelper.util.qmi.QmiRxGroup
import com.nvmex.networkhelper.util.qmi.qmiRxMetricFraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/** No root request or polling until expanded. Each displayed subscription resolves to its physical slot. */
@Composable
fun RxAntennaSection(subId: Int, live: Boolean, active: Boolean, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var retry by remember { mutableIntStateOf(0) }
    var groups by remember(subId) { mutableStateOf<List<QmiRxGroup>>(emptyList()) }
    var error by remember(subId) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val qualcomm = remember { SocVendorDetector.isQualcomm() }
    val sim = remember(subId, retry) {
        runCatching {
            context.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfo(subId)
                ?.simSlotIndex?.takeIf { it in 0..1 }?.plus(1)
        }.getOrNull()
    }
    LaunchedEffect(expanded, live, active, sim, retry, lifecycle) {
        groups = emptyList(); error = null
        if (!expanded || !live || !active || !qualcomm || sim == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val client = QmiModemClient(context)
            groups = emptyList(); error = null; loading = true
            try {
                while (isActive) {
                    groups = client.rx(sim)
                    loading = false
                    delay(1000)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { groups = emptyList(); error = e.message ?: "QMI" }
            finally { loading = false; withContext(NonCancellable) { client.close() } }
        }
    }
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.qmi_rx_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.qmi_collapse else R.string.qmi_expand))
        }
        if (expanded) Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !live -> Text(stringResource(R.string.qmi_rx_paused))
                !active -> Unit
                !qualcomm -> Text(stringResource(R.string.qmi_unsupported))
                sim == null -> Text(stringResource(R.string.qmi_rx_sim_unknown))
                else -> {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let {
                        Text(stringResource(R.string.qmi_read_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { retry++ }) { Text(stringResource(R.string.qmi_read)) }
                    }
                    val available = groups.filter { it.chains.isNotEmpty() }
                    if (!loading && error == null && available.isEmpty()) Text(stringResource(R.string.qmi_rx_empty), style = MaterialTheme.typography.bodySmall)
                    available.forEach { group ->
                        group.chains.forEach { chain ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${group.rat} · RX ${chain.index}" + if (!chain.tuned) " · ${stringResource(R.string.qmi_rx_idle)}" else "",
                                    style = MaterialTheme.typography.labelLarge)
                                val metrics = buildList {
                                    add(RxMetric("Power", chain.power, "dBm", -140.0, -30.0))
                                    if (group.rat in listOf("LTE", "NR")) add(RxMetric("RSRP", chain.rsrp, "dBm", -140.0, -60.0))
                                    if (group.rat == "WCDMA") {
                                        add(RxMetric("Ec/Io", chain.ecio, "dB", -20.0, 0.0))
                                        add(RxMetric("RSCP", chain.rscp, "dBm", -120.0, -25.0))
                                    }
                                    if (group.rat in listOf("LTE", "NR") || chain.phase != null) add(RxMetric("Phase", chain.phase, "°", 0.0, 360.0))
                                }
                                metrics.chunked(3).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { value -> RxMetricBar(value, Modifier.weight(1f)) }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun metric(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

private data class RxMetric(val label: String, val value: Double?, val unit: String, val min: Double, val max: Double)

@Composable
private fun RxMetricBar(measurement: RxMetric, modifier: Modifier = Modifier) {
    val fraction = qmiRxMetricFraction(measurement.value, measurement.min, measurement.max)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(measurement.label, style = MaterialTheme.typography.labelSmall)
        Text("${metric(measurement.value)} ${measurement.unit}", style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        NetworkSignalProgressBar(fraction, Modifier.fillMaxWidth())
    }
}
