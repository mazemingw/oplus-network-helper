package com.nvmex.networkhelper.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

@Composable
fun BandLockHelpDialog(
    show: Boolean,
    state: NetworkPanelUiState,
    onDismiss: () -> Unit
) {
    if (!show) return

    val carrier = detectCarrier(state)
    val spec = lockSpecFor(carrier)

    val currentNrBand = remember(state.cellType, state.band) {
        if (state.cellType.equals("NR", ignoreCase = true) && state.band.isNotBlank() && state.band != "-") {
            "N${state.band.trim()}"
        } else {
            null
        }
    }

    val currentLteBand = remember(state.cellType, state.band) {
        if (state.cellType.equals("LTE", ignoreCase = true) && state.band.isNotBlank() && state.band != "-") {
            "B${state.band.trim()}"
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.action_ok),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.band_lock_help_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (spec == null) {
                        UnknownCarrierBlock(state = state)
                        return@Column
                    }

                    CurrentBandSummaryCard(
                        carrierTitle = stringResource(spec.titleRes),
                        state = state,
                        currentNrBand = currentNrBand,
                        currentLteBand = currentLteBand
                    )

                    BandSectionCard(
                        title = "5G NR",
                        rows = spec.nr,
                        currentBand = currentNrBand
                    )

                    BandSectionCard(
                        title = "4G LTE",
                        rows = spec.lte,
                        currentBand = currentLteBand
                    )

                    Text(
                        text = stringResource(R.string.band_lock_help_highlight_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

@Composable
private fun UnknownCarrierBlock(state: NetworkPanelUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.band_lock_unknown_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = stringResource(R.string.band_lock_current_operator, state.operatorName),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "MCC / MNC：${state.mcc}-${state.mnc}",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.band_lock_unknown_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CurrentBandSummaryCard(
    carrierTitle: String,
    state: NetworkPanelUiState,
    currentNrBand: String?,
    currentLteBand: String?
) {
    val currentText = when {
        currentNrBand != null -> stringResource(R.string.band_lock_current_5g, currentNrBand)
        currentLteBand != null -> stringResource(R.string.band_lock_current_4g, currentLteBand)
        else -> stringResource(R.string.band_lock_current_unknown)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = carrierTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = currentText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

//            Text(
//                text = "当前小区类型：${state.cellType}",
//                style = MaterialTheme.typography.titleSmall
//            )
        }
    }
}

@Composable
private fun BandSectionCard(
    title: String,
    rows: List<LockRow>,
    currentBand: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            rows.forEach { row ->
                val isCurrent = currentBand != null && row.band.equals(currentBand, ignoreCase = true)
                BandRowCard(
                    row = row,
                    isCurrent = isCurrent
                )
            }
        }
    }
}

@Composable
private fun BandRowCard(
    row: LockRow,
    isCurrent: Boolean
) {
    val containerColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    val borderColor = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }

    val titleColor = if (isCurrent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val noteColor = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val bandWithBw = buildString {
        append(row.band)
        if (row.bw.isNotBlank() && row.bw != "-") {
            append(" · ")
            append(row.bw)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        tonalElevation = if (isCurrent) 2.dp else 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isCurrent) 1.2.dp else 0.8.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = bandWithBw,
                modifier = Modifier.width(108.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor
            )

            Text(
                text = stringResource(row.noteRes),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = noteColor
            )
        }
    }
}



/* =========================
 * Data Model (结构化文案)
 * ========================= */

private data class LockSpec(
    @param:StringRes val titleRes: Int,
    val nr: List<LockRow>,
    val lte: List<LockRow>
)

private data class LockRow(
    val band: String, // N41 / B3
    val bw: String,   // 100MHz / 100+60MHz / -
    @param:StringRes val noteRes: Int
)

private fun lockSpecFor(carrier: Carrier): LockSpec? {
    return when (carrier) {
        Carrier.CMCC -> LockSpec(
            titleRes = R.string.band_lock_carrier_cmcc,
            nr = listOf(
                LockRow("N41", "160MHz", R.string.band_lock_note_main),
                LockRow("N78", "100MHz", R.string.band_lock_note_rare),
                LockRow("N79", "160MHz", R.string.band_lock_note_main_dense_support),
                LockRow("N28", "30MHz", R.string.band_lock_note_assist_weak)
            ),
            lte = listOf(
                LockRow("B3", "20MHz", R.string.band_lock_note_common),
                LockRow("B8", "10MHz", R.string.band_lock_note_village_weak),
                LockRow("B34", "15MHz", R.string.band_lock_note_rare),
                LockRow("B38", "20MHz", R.string.band_lock_note_common),
                LockRow("B39", "20MHz", R.string.band_lock_note_common),
                LockRow("B40", "20MHz", R.string.band_lock_note_basement),
                LockRow("B41", "20MHz", R.string.band_lock_note_common)
            )
        )

        Carrier.CUCC -> LockSpec(
            titleRes = R.string.band_lock_carrier_cucc,
            nr = listOf(
                LockRow("N78", "100MHz", R.string.band_lock_note_main),
                LockRow("N1", "20/40MHz", R.string.band_lock_note_low_supplement),
                LockRow("N3", "20MHz", R.string.band_lock_note_rare),
                LockRow("N5", "10/15MHz", R.string.band_lock_note_weak_shared_ctcc),
                LockRow("N8", "10MHz", R.string.band_lock_note_weak)
            ),
            lte = listOf(
                LockRow("B1", "20MHz", R.string.band_lock_note_common),
                LockRow("B3", "20MHz", R.string.band_lock_note_common),
                LockRow("B8", "10MHz", R.string.band_lock_note_village_weak)
            )
        )

        Carrier.CTCC -> LockSpec(
            titleRes = R.string.band_lock_carrier_ctcc,
            nr = listOf(
                LockRow("N78", "100MHz", R.string.band_lock_note_main),
                LockRow("N1", "20/40MHz", R.string.band_lock_note_low_supplement),
                LockRow("N3", "20MHz", R.string.band_lock_note_rare),
                LockRow("N5", "10/15MHz", R.string.band_lock_note_village_weak),
                LockRow("N8", "10MHz", R.string.band_lock_note_weak_shared_cucc)

            ),
            lte = listOf(
                LockRow("B1", "20MHz", R.string.band_lock_note_common),
                LockRow("B3", "20MHz", R.string.band_lock_note_common),
                LockRow("B5", "10MHz", R.string.band_lock_note_village_weak)
            )
        )

        Carrier.CBN -> LockSpec(
            titleRes = R.string.band_lock_carrier_cbn,
            nr = listOf(
                LockRow("N41", "160MHz", R.string.band_lock_note_main),
                LockRow("N78", "100MHz", R.string.band_lock_note_rare),
                LockRow("N79", "160MHz", R.string.band_lock_note_main_dense_support),
                LockRow("N28", "30MHz", R.string.band_lock_note_assist_weak)
            ),
            lte = listOf(
                LockRow("-", "-", R.string.band_lock_note_cbn_lte_shared)
            )
        )

        Carrier.TIETONG -> LockSpec(
            titleRes = R.string.band_lock_carrier_tietong,
            nr = listOf(
                LockRow("-", "-", R.string.band_lock_note_tietong)
            ),
            lte = listOf(
                LockRow("-", "-", R.string.band_lock_note_tietong)
            )
        )

        Carrier.UNKNOWN -> null
    }
}

/* =========================
 * Carrier Detect
 * ========================= */

enum class Carrier { CMCC, CUCC, CTCC, CBN, TIETONG, UNKNOWN }

fun detectCarrier(state: NetworkPanelUiState): Carrier {
    val mcc = state.mcc.trim()
    val mnc = state.mnc.trim()

    if (mcc != "460") return Carrier.UNKNOWN

    return when (mnc) {
        "00", "02", "07" -> Carrier.CMCC
        "01", "06", "09" -> Carrier.CUCC
        "03", "05", "11" -> Carrier.CTCC
        "15" -> Carrier.CBN
        "20" -> Carrier.TIETONG
        else -> Carrier.UNKNOWN
    }
}
