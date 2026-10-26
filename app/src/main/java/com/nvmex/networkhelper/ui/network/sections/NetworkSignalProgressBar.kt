package com.nvmex.networkhelper.ui.network.sections

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Shared by serving-signal rows and RX: the same animated red/yellow/green pill and midpoint marker. */
@Composable
fun NetworkSignalProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = (fraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "networkSignalBar"
    )
    val color = if (fraction == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f) else when {
        animated < 0.5f -> lerp(Color(0xFFFF3B30), Color(0xFFFFCC00), (animated / 0.5f).coerceIn(0f, 1f))
        else -> lerp(Color(0xFFFFCC00), Color(0xFF34C759), ((animated - 0.5f) / 0.5f).coerceIn(0f, 1f))
    }
    Box(modifier.height(12.dp).clip(RoundedCornerShape(999.dp))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animated).clip(RoundedCornerShape(999.dp)).background(color))
        Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            .align(Alignment.Center))
    }
}
