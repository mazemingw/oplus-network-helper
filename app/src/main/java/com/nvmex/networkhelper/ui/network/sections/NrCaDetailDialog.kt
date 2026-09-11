package com.nvmex.networkhelper.ui.network.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NrCaCarrier
import com.nvmex.networkhelper.model.network.NrCaInfo
import com.nvmex.networkhelper.util.network.utils.nrArfcnToMhz
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import java.util.Locale

@Composable
fun NrCaDetailDialog(
    show: Boolean,
    info: NrCaInfo?,
    showBandwidth: Boolean = true,
    onDismiss: () -> Unit
) {
    if (!show) return

    val carriers = info?.carriers.orEmpty()
    val caSiteTypeText = if (carriers.size >= 2) {
        stringResource(
            if (carriers.map { it.pci }.distinct().size > 1) {
                R.string.ca_cross_site
            } else {
                R.string.ca_same_site
            }
        )
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.ca_nr_detail_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                if (info == null || carriers.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.ca_nr_empty),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.ca_nr_empty_reason_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.ca_nr_empty_reason_plugin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.ca_nr_empty_reason_system),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 500.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ca_nr_slot_type_count, info.slot, info.type, carriers.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (caSiteTypeText != null) {
                            Text(
                                text = caSiteTypeText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(carriers) { idx, c ->
                                NrCaCarrierCardCompact(
                                    idx = idx,
                                    c = c,
                                    showBandwidth = showBandwidth
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.action_close),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
private fun NrCaCarrierCardCompact(
    idx: Int,
    c: NrCaCarrier,
    showBandwidth: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "carrier[$idx]",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                val bandText = bandTextOf(c)
                val freqRangeText = freqRangeTextOf(c)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (freqRangeText != null) {
                        Text(
                            text = freqRangeText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    NrBandChip(text = bandText)
                }
            }

            CompactKeyValueRow("DL EARFCN", c.dlArfcn.toString())
            CompactKeyValueRow("PCI", c.pci.toString())
            CompactKeyValueRow("CC / SCC", "ccId=${c.ccId}  sccId=${c.sccId}")
            CompactKeyValueRow("DL STATE", NrcaTranslator.activityState(c.dlStateRaw))
            CompactKeyValueRow("DL BW", if (showBandwidth) NrcaTranslator.bandwidth(c.dlBwRaw) else "-")
            CompactKeyValueRow("UL STATE", NrcaTranslator.activityState(c.ulStateRaw))
            CompactKeyValueRow("UL BW", if (showBandwidth) NrcaTranslator.bandwidth(c.ulBwRaw) else "-")
        }
    }
}

@Composable
private fun NrBandChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF448AFF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun bandTextOf(c: NrCaCarrier): String = NrcaTranslator.bandSmart(c.bandRaw).trim()

private fun freqRangeTextOf(c: NrCaCarrier): String? {
    val centerMhz = c.dlArfcn.takeIf { it > 0 }?.let(::nrArfcnToMhz)?.takeIf { it > 0.0 }
    val bwMhz = NrcaTranslator.bandwidthMhzOrNull(c.dlBwRaw)
        ?: NrcaTranslator.bandwidthMhzOrNull(c.ulBwRaw)

    return if (centerMhz != null && bwMhz != null && bwMhz > 0) {
        val half = bwMhz / 2.0
        val startMhz = centerMhz - half
        val endMhz = centerMhz + half
        "${formatMhzCompact(startMhz)}-${formatMhzCompact(endMhz)}MHz"
    } else {
        null
    }
}

private fun formatMhzCompact(v: Double): String {
    val raw = String.format(Locale.US, "%.3f", v)
    return raw.trimEnd('0').trimEnd('.')
}

@Composable
private fun CompactKeyValueRow(
    key: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
