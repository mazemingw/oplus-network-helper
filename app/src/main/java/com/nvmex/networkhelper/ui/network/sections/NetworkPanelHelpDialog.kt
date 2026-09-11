package com.nvmex.networkhelper.ui.network.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R

@Composable
fun NetworkPanelHelpDialog(
    show: Boolean,
    onDismiss: () -> Unit
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.network_field_help),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                item {
                    HorizontalDivider()
                }

                item {
                    HelpItem(stringResource(R.string.network_data_nr_mode), stringResource(R.string.network_help_data_nr_mode_desc))
                }
                item {
                    HelpItem(stringResource(R.string.network_cell_type), stringResource(R.string.network_help_cell_type_desc))
                }
                item {
                    HelpItem("FREQ", stringResource(R.string.network_help_freq_desc))
                }
                item {
                    HelpItem("LINK RATE", stringResource(R.string.network_help_link_rate_desc))
                }

                item {
                    HorizontalDivider()
                }


                item {
                    HelpItem("TAC", stringResource(R.string.network_help_tac_desc))
                }
                item {
                    HelpItem("PCI", stringResource(R.string.network_help_pci_desc))
                }
                item {
                    HelpItem("NR-NCI", stringResource(R.string.network_help_nci_desc))
                }
                item {
                    HelpItem("ARFCN", stringResource(R.string.network_help_arfcn_desc))
                }
                item {
                    HelpItem(stringResource(R.string.network_help_band_duplex_title), stringResource(R.string.network_help_band_duplex_desc))
                }

                item {
                    HorizontalDivider()
                }

                item {
                    HelpItem(stringResource(R.string.network_help_bandwidth_title), stringResource(R.string.network_help_bandwidth_desc))
                }
                item {
                    HelpItem("NR CA", stringResource(R.string.network_help_nr_ca_desc))
                }
                item {
                    HelpItem("LTE CA", stringResource(R.string.network_help_lte_ca_desc))
                }

                item {
                    HorizontalDivider()
                }

                item {
                    HelpItem("RSSI", stringResource(R.string.network_help_rssi_desc))
                }

                item {
                    HelpItem("RSRP", stringResource(R.string.network_help_rsrp_desc))
                }

                item {
                    HelpItem("RSRQ", stringResource(R.string.network_help_rsrq_desc))
                }

                item {
                    HelpItem("SINR", stringResource(R.string.network_help_sinr_desc))
                }

                item {
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.action_got_it),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
private fun HelpItem(
    title: String,
    desc: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
