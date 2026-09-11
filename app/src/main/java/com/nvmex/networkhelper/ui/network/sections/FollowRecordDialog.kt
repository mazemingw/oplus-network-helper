package com.nvmex.networkhelper.ui.network.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.ui.network.FollowRecordUiState
import com.nvmex.networkhelper.ui.network.FollowSiteType
import com.nvmex.networkhelper.ui.network.FollowVendor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FollowRecordDialog(
    show: Boolean,
    panelState: NetworkPanelUiState,
    uiState: FollowRecordUiState,
    onDismiss: () -> Unit,
    onCellNameChange: (String) -> Unit,
    onReloadLocation: () -> Unit,
    onPickFromMap: () -> Unit,
    onSubmit: () -> Unit,
    onSubmitSuccess: () -> Unit,
    onVendorSelected: (FollowVendor) -> Unit,
    onSiteTypeSelected: (FollowSiteType) -> Unit,
    onApplyQuickSuffix: () -> Unit
) {
    if (!show) return

    val idLabel = if (panelState.cellType.uppercase() == "NR") {
        stringResource(R.string.follow_record_nci)
    } else {
        stringResource(R.string.follow_record_eci)
    }
    val suffixPreview = buildSuffixPreview(
        cellType = panelState.cellType,
        vendor = uiState.selectedVendor,
        siteType = uiState.selectedSiteType
    )

    LaunchedEffect(show, uiState.submitSuccess) {
        if (show && uiState.submitSuccess) {
            onSubmitSuccess()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.network_follow_record),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("${stringResource(R.string.follow_record_cell_type)}: ${panelState.cellType}")
                    Text("${stringResource(R.string.follow_record_tac)}: ${panelState.tac}")
                    Text("${stringResource(R.string.follow_record_pci)}: ${panelState.pci}")
                    Text("$idLabel: ${panelState.ci}")
                    Text("${stringResource(R.string.follow_record_arfcn)}: ${panelState.arfcn}")

                    if (uiState.isLoadingLocation) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.follow_record_loading_location))
                        }
                    } else {
                        Text("${stringResource(R.string.follow_record_wgs_longitude)}: ${uiState.longitude ?: "-"}")
                        Text("${stringResource(R.string.follow_record_wgs_latitude)}: ${uiState.latitude ?: "-"}")
                        Text("${stringResource(R.string.follow_record_poi_name)}: ${uiState.poiName?.ifBlank { "-" } ?: "-"}")
                        Text("${stringResource(R.string.follow_record_address)}: ${uiState.address?.ifBlank { "-" } ?: "-"}")
                    }

                    OutlinedTextField(
                        value = uiState.cellName,
                        onValueChange = onCellNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = {
                            Text(
                                stringResource(R.string.follow_record_cell_name),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )

                    Text(
                        text = stringResource(R.string.follow_record_vendor),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FollowVendor.entries.forEach { vendor ->
                            FilterChip(
                                selected = uiState.selectedVendor == vendor,
                                onClick = { onVendorSelected(vendor) },
                                label = { Text(stringResource(vendor.labelRes)) }
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.follow_record_site_type),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FollowSiteType.entries.forEach { siteType ->
                            FilterChip(
                                selected = uiState.selectedSiteType == siteType,
                                onClick = { onSiteTypeSelected(siteType) },
                                label = { Text(stringResource(siteType.labelRes)) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${stringResource(R.string.follow_record_quick_suffix)}: ${suffixPreview ?: "-"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        TextButton(
                            onClick = onApplyQuickSuffix,
                            enabled = !uiState.isSubmitting
                        ) {
                            Text(
                                stringResource(R.string.follow_record_apply_suffix),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    uiState.errorMessage?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    uiState.submitMessage?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = !uiState.isSubmitting
            ) {
                Text(
                    if (uiState.isSubmitting) {
                        stringResource(R.string.follow_record_submitting)
                    } else {
                        stringResource(R.string.action_submit)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onReloadLocation,
                    enabled = !uiState.isLoadingLocation && !uiState.isSubmitting
                ) {
                    Text(
                        stringResource(R.string.follow_record_reload_location),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(
                    onClick = onPickFromMap,
                    enabled = !uiState.isSubmitting
                ) {
                    Text(
                        stringResource(R.string.follow_record_pick_on_map),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !uiState.isSubmitting
                ) {
                    Text(
                        stringResource(R.string.action_close),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    )
}

private fun buildSuffixPreview(
    cellType: String,
    vendor: FollowVendor?,
    siteType: FollowSiteType?
): String? {
    if (vendor == null || siteType == null) return null

    val techCode = when (cellType.uppercase()) {
        "NR" -> "5"
        "LTE" -> "L"
        else -> return null
    }

    return vendor.code + techCode + siteType.code
}
