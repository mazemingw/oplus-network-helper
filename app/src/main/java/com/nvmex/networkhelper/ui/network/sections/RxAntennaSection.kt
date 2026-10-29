package com.nvmex.networkhelper.ui.network.sections

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.network.SocVendorDetector
import com.nvmex.networkhelper.util.qmi.QmiRxGroup
import com.nvmex.networkhelper.util.qmi.qmiRxMetricFraction
import com.nvmex.networkhelper.util.qmi.qmiRxSlots
import com.nvmex.networkhelper.util.qmi.qmiRxDisplayGroups
import java.util.Locale

/** Presentation only; RX, neighbors and CA share the foreground ViewModel's read-only session. */
@Composable
fun RxAntennaSection(sim: Int?, live: Boolean, active: Boolean, expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
                     groups: List<QmiRxGroup>, error: String?, loading: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val qualcomm = remember { SocVendorDetector.isQualcomm() }
    var retainedRats by remember(sim, expanded, live) { mutableStateOf(emptyList<String>()) }
    val displayGroups = qmiRxDisplayGroups(groups, retainedRats)
    LaunchedEffect(sim, expanded, live, active, displayGroups.map { it.rat }) {
        if (expanded && live && active) retainedRats = displayGroups.map { it.rat }
    }
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.qmi_rx_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.qmi_collapse else R.string.qmi_expand))
        }
        if (expanded) Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                !live -> Text(stringResource(R.string.qmi_rx_paused))
                !active -> Unit
                !qualcomm -> Text(stringResource(R.string.qmi_unsupported))
                sim == null -> Text(stringResource(R.string.qmi_rx_sim_unknown))
                else -> {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                    else Spacer(Modifier.height(4.dp))
                    error?.let {
                        Text(stringResource(R.string.qmi_read_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.qmi_read)) }
                    }
                    val available = displayGroups
                    if (!loading && error == null && available.isEmpty()) Text(stringResource(R.string.qmi_rx_empty), style = MaterialTheme.typography.bodySmall)
                    available.forEach { group ->
                        Text(group.rat, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        qmiRxSlots(group.chains).forEach { slot ->
                            val chain = slot.measurement
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.width(34.dp)) {
                                    Text("RX ${slot.index}", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    if (chain == null || !chain.tuned) Text(stringResource(if (chain == null) R.string.qmi_rx_unreported else R.string.qmi_rx_idle),
                                        style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val metrics = buildList {
                                    add(RxMetric("Power", chain?.power, "dBm", -140.0, -30.0))
                                    if (group.rat in listOf("LTE", "NR")) add(RxMetric("RSRP", chain?.rsrp, "dBm", -140.0, -60.0))
                                    if (group.rat == "WCDMA") {
                                        add(RxMetric("Ec/Io", chain?.ecio, "dB", -20.0, 0.0))
                                        add(RxMetric("RSCP", chain?.rscp, "dBm", -120.0, -25.0))
                                    }
                                }
                                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    metrics.forEach { value -> RxMetricBar(value, Modifier.weight(1f)) }
                                }
                                if (group.rat in listOf("LTE", "NR") || chain?.phase != null)
                                    RxPhaseIndicator(chain?.phase, Modifier.width(44.dp))
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(measurement.label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
            Text(measurement.unit, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(metric(measurement.value), style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        NetworkSignalProgressBar(fraction, Modifier.fillMaxWidth().height(8.dp))
    }
}

@Composable
private fun RxPhaseIndicator(phase: Double?, modifier: Modifier = Modifier) {
    val value = phase?.takeIf { it.isFinite() }
    val fraction = qmiRxMetricFraction(value, 0.0, 360.0)
    val animated by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "rxPhaseFill"
    )
    val color = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Column(modifier.clearAndSetSemantics { contentDescription = "PHASE ${metric(value)}°" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("PHASE", fontSize = 8.sp, lineHeight = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.size(24.dp)) {
            drawCircle(track)
            if (fraction != null) {
                drawArc(color, startAngle = -90f, sweepAngle = animated.coerceIn(0f, 1f) * 360f, useCenter = true)
            }
        }
        Text(if (value == null) "—" else "${metric(value)}°", fontSize = 10.sp, lineHeight = 11.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
