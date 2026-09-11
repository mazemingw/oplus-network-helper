package com.nvmex.networkhelper.ui.network

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.model.network.QualityLevel
import com.nvmex.networkhelper.ui.network.sections.NetworkQualityBadge
import com.nvmex.networkhelper.ui.network.sections.localizedNetworkQualityText
import com.nvmex.networkhelper.util.network.NetworkPanelWithPermissionGate
import com.nvmex.networkhelper.util.network.sections.NetworkQualityAssessment
import com.nvmex.networkhelper.util.network.sections.assessNetworkQuality
import com.nvmex.networkhelper.util.windows.WindowUtils
import com.nvmex.networkhelper.viewmodel.signal.NetworkPanelMultiSimViewModel

private data class SignalComparisonEntry(
    val title: String,
    val state: NetworkPanelUiState?
)

private enum class SignalMetric(
    val label: String,
    val min: Float,
    val max: Float
) {
    RSSI("RSSI", -120f, -50f),
    RSRP("RSRP", -140f, -70f),
    RSRQ("RSRQ", -30f, -3f),
    SINR("SINR", -20f, 30f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalComparisonScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val statusBarHeight = WindowUtils.getStatusBarHeight(context)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = statusBarHeight),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                title = { Text(stringResource(R.string.home_signal_compare_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            NetworkPanelWithPermissionGate {
                SignalComparisonContent()
            }
        }
    }
}

@Composable
private fun SignalComparisonContent(
    vm: NetworkPanelMultiSimViewModel = viewModel()
) {
    val sims by vm.sims.collectAsState()
    val frame by vm.frame.collectAsState()
    val states = frame.data
    val entries = (0..1).map { slotIndex ->
        val simInfo = sims.firstOrNull { it.simSlotIndex == slotIndex }
        SignalComparisonEntry(
            title = "SIM${slotIndex + 1}",
            state = simInfo?.subscriptionId?.let(states::get)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.home_signal_compare_live_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SignalScoreOverview(entries)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_signal_basic_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                ComparisonHeader(entries)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ComparisonRow(
                    label = stringResource(R.string.network_operator),
                    values = entries.map { displayValue(it.state?.operatorName) }
                )
                ComparisonRow(
                    label = stringResource(R.string.home_signal_data_network),
                    values = entries.map { displayValue(it.state?.dataNetworkType) }
                )
                ComparisonRow(
                    label = stringResource(R.string.home_signal_nr_mode),
                    values = entries.map { displayValue(it.state?.nrMode) }
                )
                ComparisonRow(
                    label = stringResource(R.string.home_signal_band),
                    values = entries.map { displayValue(it.state?.band) }
                )
                ComparisonRow(
                    label = stringResource(R.string.home_signal_arfcn),
                    values = entries.map { displayValue(it.state?.arfcn) }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_signal_quality),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                ComparisonHeader(entries)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SignalMetric.entries.forEach { metric ->
                    SignalComparisonRow(
                        metric = metric,
                        values = entries.map { signalValue(it.state, metric) }
                    )
                }
                Text(
                    text = stringResource(R.string.home_signal_nr_ss_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SignalScoreOverview(entries: List<SignalComparisonEntry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            entries.forEach { entry ->
                val assessment = assessSignalQuality(entry.state)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    NetworkQualityBadge(
                        qualityText = localizedNetworkQualityText(assessment),
                        qualityLevel = assessment.level,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private fun assessSignalQuality(state: NetworkPanelUiState?): NetworkQualityAssessment {
    state ?: return NetworkQualityAssessment(
        level = QualityLevel.UNKNOWN,
        text = "unknown",
        score = 0
    )
    return when {
        state.dataNetworkType == "NR" -> assessNetworkQuality(
            rsrpString = state.ssRsrp,
            sinrString = state.ssSinr,
            rsrqString = state.ssRsrq,
            isNR = true
        )
        state.cellType == "LTE" -> assessNetworkQuality(
            rsrpString = state.rsrp,
            sinrString = state.sinr,
            rsrqString = state.rsrq,
            rssiString = state.rssi,
            isNR = false
        )
        else -> NetworkQualityAssessment(
            level = QualityLevel.UNKNOWN,
            text = "unknown",
            score = 0
        )
    }
}

@Composable
private fun ComparisonHeader(entries: List<SignalComparisonEntry>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(70.dp))
        entries.forEach { entry ->
            Text(
                text = entry.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ComparisonRow(label: String, values: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(70.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        (0..1).forEach { index ->
            Text(
                text = values.getOrElse(index) { "-" },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SignalComparisonRow(metric: SignalMetric, values: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = metric.label,
            modifier = Modifier.width(70.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        (0..1).forEach { index ->
            val textValue = values.getOrElse(index) { "-" }
            SignalValueCell(
                metric = metric,
                value = textValue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SignalValueCell(
    metric: SignalMetric,
    value: String,
    modifier: Modifier = Modifier
) {
    val numericValue = parseFirstNumber(value)
    val targetProgress = numericValue?.let {
        ((it - metric.min) / (metric.max - metric.min)).coerceIn(0f, 1f)
    } ?: 0f
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        label = "${metric.label} progress"
    )

    Column(
        modifier = modifier.padding(horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Keep the numeric text on the theme's default content color.
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = signalBarColor(targetProgress, numericValue != null),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

@Composable
private fun signalBarColor(progress: Float, available: Boolean): Color {
    if (!available) return MaterialTheme.colorScheme.outlineVariant
    return when {
        progress >= 0.72f -> Color(0xFF2E7D32)
        progress >= 0.48f -> Color(0xFFF9A825)
        progress >= 0.28f -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }
}

private fun signalValue(state: NetworkPanelUiState?, metric: SignalMetric): String {
    state ?: return "-"
    if (metric == SignalMetric.RSSI) {
        return displayValue(state.rssi).takeUnless { it == "-" }
            ?: displayValue(state.anchorRssi)
    }

    val isNr = state.cellType.equals("NR", ignoreCase = true) ||
            state.dataNetworkType.equals("NR", ignoreCase = true) ||
            state.nrMode != "-"
    val primary = when (metric) {
        SignalMetric.RSRP -> if (isNr) state.ssRsrp else state.rsrp
        SignalMetric.RSRQ -> if (isNr) state.ssRsrq else state.rsrq
        SignalMetric.SINR -> if (isNr) state.ssSinr else state.sinr
        SignalMetric.RSSI -> state.rssi
    }
    val fallback = when (metric) {
        SignalMetric.RSRP -> state.rsrp
        SignalMetric.RSRQ -> state.rsrq
        SignalMetric.SINR -> state.sinr
        SignalMetric.RSSI -> state.rssi
    }
    return displayValue(primary).takeUnless { it == "-" } ?: displayValue(fallback)
}

private fun parseFirstNumber(value: String): Float? {
    return Regex("""-?\d+(?:\.\d+)?""").find(value)?.value?.toFloatOrNull()
}

private fun displayValue(value: String?): String {
    return value?.trim()?.takeUnless { it.isBlank() || it == "-" } ?: "-"
}
