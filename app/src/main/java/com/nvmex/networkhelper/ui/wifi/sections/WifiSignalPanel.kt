package com.nvmex.networkhelper.ui.wifi.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.wifi.WifiUiModel
import com.nvmex.networkhelper.util.wifi.WifiQualityEvaluator

@Composable
fun WifiSignalPanel(
    ui: WifiUiModel,
    modifier: Modifier = Modifier
) {
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) {
        WifiParamsHelpDialog(onDismiss = { showHelp = false })
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showHelp = true }) {
                    Text(stringResource(R.string.wifi_params_help_title), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    val qualityLabel = localizedQualityLabel(ui.qualityLabel)
                    Text(
                        text = "$qualityLabel · ${ui.qualityScore}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    ui.qualityHint?.let { hint ->
                        Text(
                            text = localizedQualityHint(hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 信号条（RSSI / TX / RX / Retry/s / Bad/s）
            WifiSignalBarsPanel(ui = ui)
        }
    }
}

@Composable
private fun localizedQualityLabel(label: String): String {
    return when (label) {
        WifiQualityEvaluator.LABEL_EXCELLENT -> stringResource(R.string.wifi_quality_excellent)
        WifiQualityEvaluator.LABEL_GOOD -> stringResource(R.string.wifi_quality_good)
        WifiQualityEvaluator.LABEL_FAIR -> stringResource(R.string.wifi_quality_fair)
        WifiQualityEvaluator.LABEL_POOR -> stringResource(R.string.wifi_quality_poor)
        WifiQualityEvaluator.LABEL_VERY_POOR -> stringResource(R.string.wifi_quality_very_poor)
        else -> stringResource(R.string.wifi_quality_unknown)
    }
}

@Composable
private fun localizedQualityHint(hint: String): String {
    return when (hint) {
        WifiQualityEvaluator.HINT_DEGRADED -> stringResource(R.string.wifi_quality_hint_degraded)
        WifiQualityEvaluator.HINT_HIGH_RETRY -> stringResource(R.string.wifi_quality_hint_high_retry)
        WifiQualityEvaluator.HINT_HIGH_BAD -> stringResource(R.string.wifi_quality_hint_high_bad)
        WifiQualityEvaluator.HINT_WEAK_SIGNAL -> stringResource(R.string.wifi_quality_hint_weak_signal)
        WifiQualityEvaluator.HINT_LOW_SPEED -> stringResource(R.string.wifi_quality_hint_low_speed)
        else -> hint
    }
}
