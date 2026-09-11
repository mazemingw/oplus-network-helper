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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.LteCaCarrier
import com.nvmex.networkhelper.model.network.LteCaInfo

@Composable
fun LteCaDetailDialog(
    show: Boolean,
    info: LteCaInfo?,
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
                text = stringResource(R.string.ca_lte_detail_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                if (info == null || carriers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ca_lte_empty),
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 500.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ca_lte_slot_count, info.slot, carriers.size),
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
                                LteCaCarrierCardCompact(
                                    idx = idx,
                                    c = c
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
private fun LteCaCarrierCardCompact(
    idx: Int,
    c: LteCaCarrier
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "carrier[$idx]",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = c.bandText ?: c.band?.let { "B$it" } ?: if (c.dlEarfcn > 0) "DL ${c.dlEarfcn}" else "DL -",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF448AFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            CompactKeyValueRow(stringResource(R.string.ca_scell_index), c.idx.toString())
            CompactKeyValueRow("PCI", c.pci.toString())
            CompactKeyValueRow("Band", c.bandText ?: c.band?.let { "B$it" } ?: "-")
            CompactKeyValueRow("DL BW", c.dlBwText ?: "-")
            CompactKeyValueRow("SCell State", c.scellStateText ?: c.scellState?.toString() ?: "-")
            CompactKeyValueRow("UL Enabled", c.ulEnabled?.toString() ?: "-")
            CompactKeyValueRow("DL EARFCN", c.dlEarfcn.toString())
            CompactKeyValueRow("UL EARFCN", c.ulEarfcn.toString())
            CompactKeyValueRow("RSRP", c.rsrp?.toString() ?: "-")
            CompactKeyValueRow("RSRQ", c.rsrq?.toString() ?: "-")
            CompactKeyValueRow("SINR", c.sinr?.toString() ?: "-")
        }
    }
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
