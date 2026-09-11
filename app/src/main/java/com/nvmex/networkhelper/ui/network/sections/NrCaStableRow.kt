package com.nvmex.networkhelper.ui.network.sections

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun NrCaStableRow(
    label: String,
    chips: List<CaChip>,
    onClick: (() -> Unit)? = null,
    emptyText: String = "无聚合",
    showLoading: Boolean = false
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)

    Row(
        modifier = rowModifier.heightIn(min = 34.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            NrCaChipsFixedContainer(
                chips = chips,
                emptyText = emptyText,
                showLoading = showLoading
            )
        }
    }
}

@Composable
fun NrCaChipsFixedContainer(
    chips: List<CaChip>,
    emptyText: String = "无聚合",
    showLoading: Boolean = false
) {
    val scrollState = rememberScrollState()

    val targetState = if (chips.isEmpty()) {
        NrCaUiState.Empty
    } else {
        NrCaUiState.WithChips(chips)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        AnimatedContent(
            targetState = targetState,
            transitionSpec = {
                (
                        slideInVertically(
                            initialOffsetY = { it / 2 }
                        ) + fadeIn()
                        ).togetherWith(
                        slideOutVertically(
                            targetOffsetY = { -it / 2 }
                        ) + fadeOut()
                    ).using(
                        SizeTransform(clip = false)
                    )
            },
            label = "NrCaAnimatedContent"
        ) { state ->
            when (state) {
                NrCaUiState.Empty -> {
                    NrCaBadge(
                        text = emptyText,
                        backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                        modifier = Modifier.wrapContentWidth(),
                        showLoading = showLoading
                    )
                }

                is NrCaUiState.WithChips -> {
                    Row(
                        modifier = Modifier.horizontalScroll(scrollState),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        state.chips.forEach { chip ->
                            NrCaBadge(
                                text = chip.text,
                                backgroundColor = if (chip.active) {
                                    Color(0xFF448AFF)
                                } else {
                                    Color(0xFFFF5722)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface NrCaUiState {
    data object Empty : NrCaUiState
    data class WithChips(val chips: List<CaChip>) : NrCaUiState
}
