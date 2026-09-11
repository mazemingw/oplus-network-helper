package com.nvmex.networkhelper.ui.chart

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun progressiveChartX(index: Int, pointCount: Int, windowSeconds: Int): Double {
    if (pointCount <= 1) return (windowSeconds - 1).toDouble()
    if (pointCount >= windowSeconds) return index.toDouble()
    return index.toDouble() * (windowSeconds - 1).toDouble() / (pointCount - 1).toDouble()
}

internal fun chartXFraction(index: Int, pointCount: Int, windowSeconds: Int): Float {
    val maxX = (windowSeconds - 1).coerceAtLeast(1).toDouble()
    return (progressiveChartX(index, pointCount, windowSeconds) / maxX).toFloat().coerceIn(0f, 1f)
}

internal fun chartYFraction(value: Float, min: Float, max: Float): Float {
    val range = (max - min).takeIf { it > 0.0001f } ?: 1f
    return (1f - ((value - min) / range)).coerceIn(0f, 1f)
}

@Composable
internal fun ChartVerticalMarker(
    xFraction: Float,
    modifier: Modifier = Modifier,
    animatePosition: Boolean = false,
    animationDurationMillis: Int = 420,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    horizontalInset: Dp = 0.dp,
    lineWidth: Dp = 1.5.dp,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
) {
    val animatedX by animateFloatAsState(
        targetValue = xFraction,
        animationSpec = tween(
            durationMillis = if (animatePosition) animationDurationMillis else 0,
            easing = LinearEasing
        ),
        label = "chart-marker-x"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val top = topInset.toPx()
        val bottom = bottomInset.toPx()
        val horizontal = horizontalInset.toPx()
        val plotBottom = (size.height - bottom).coerceAtLeast(top)
        val x = horizontal + (size.width - horizontal * 2f).coerceAtLeast(1f) * animatedX.coerceIn(0f, 1f)
        drawLine(
            color = color,
            start = Offset(x, top),
            end = Offset(x, plotBottom),
            strokeWidth = lineWidth.toPx()
        )
    }
}

@Composable
internal fun ChartPointDot(
    xFraction: Float,
    yFraction: Float,
    chartSize: IntSize,
    modifier: Modifier = Modifier,
    animatePosition: Boolean = false,
    animationDurationMillis: Int = 420,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    horizontalInset: Dp = 0.dp,
    size: Dp = 7.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    borderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    borderWidth: Dp = 1.5.dp,
) {
    val animatedX by animateFloatAsState(
        targetValue = xFraction,
        animationSpec = tween(
            durationMillis = if (animatePosition) animationDurationMillis else 0,
            easing = LinearEasing
        ),
        label = "chart-dot-x"
    )
    val animatedY by animateFloatAsState(
        targetValue = yFraction,
        animationSpec = tween(
            durationMillis = if (animatePosition) animationDurationMillis else 0,
            easing = LinearEasing
        ),
        label = "chart-dot-y"
    )
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }.roundToInt()
    val topPx = with(density) { topInset.toPx() }.roundToInt()
    val bottomPx = with(density) { bottomInset.toPx() }.roundToInt()
    val horizontalPx = with(density) { horizontalInset.toPx() }.roundToInt()

    Box(
        modifier = modifier
            .size(size)
            .offset {
                val plotHeight = (chartSize.height - topPx - bottomPx).coerceAtLeast(1)
                val plotWidth = (chartSize.width - horizontalPx * 2).coerceAtLeast(1)
                val centerX = horizontalPx + plotWidth * animatedX
                val x = (centerX - sizePx / 2f)
                    .roundToInt()
                    .coerceIn(0, (chartSize.width - sizePx).coerceAtLeast(0))
                val y = (topPx + plotHeight * animatedY - sizePx / 2f)
                    .roundToInt()
                    .coerceIn(topPx, (chartSize.height - bottomPx - sizePx).coerceAtLeast(topPx))
                IntOffset(x, y)
            }
            .background(color, CircleShape)
            .border(borderWidth, borderColor, CircleShape)
    )
}

@Composable
internal fun ChartPointLabel(
    text: String,
    xFraction: Float,
    yFraction: Float,
    preferAbove: Boolean,
    chartSize: IntSize,
    modifier: Modifier = Modifier,
    animatePosition: Boolean = false,
    animationDurationMillis: Int = 420,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    horizontalInset: Dp = 0.dp,
    verticalGap: Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
) {
    var labelSize by remember(text) { mutableStateOf(IntSize.Zero) }
    val animatedX by animateFloatAsState(
        targetValue = xFraction,
        animationSpec = tween(
            durationMillis = if (animatePosition) animationDurationMillis else 0,
            easing = LinearEasing
        ),
        label = "chart-label-x"
    )
    val animatedY by animateFloatAsState(
        targetValue = yFraction,
        animationSpec = tween(
            durationMillis = if (animatePosition) animationDurationMillis else 0,
            easing = LinearEasing
        ),
        label = "chart-label-y"
    )
    val density = LocalDensity.current
    val verticalGapPx = with(density) { verticalGap.toPx() }
    val topPx = with(density) { topInset.toPx() }.roundToInt()
    val bottomPx = with(density) { bottomInset.toPx() }.roundToInt()
    val horizontalPx = with(density) { horizontalInset.toPx() }.roundToInt()

    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .onSizeChanged { labelSize = it }
            .offset {
                val plotHeight = (chartSize.height - topPx - bottomPx).coerceAtLeast(1)
                val plotWidth = (chartSize.width - horizontalPx * 2).coerceAtLeast(1)
                val centerX = horizontalPx + plotWidth * animatedX
                val maxX = (chartSize.width - horizontalPx - labelSize.width).coerceAtLeast(horizontalPx)
                val x = (centerX - labelSize.width / 2f)
                    .roundToInt()
                    .coerceIn(horizontalPx, maxX)

                val rawY = topPx + plotHeight * animatedY + if (preferAbove) {
                    -labelSize.height - verticalGapPx
                } else {
                    verticalGapPx
                }
                val maxY = (chartSize.height - bottomPx - labelSize.height).coerceAtLeast(topPx)
                val y = rawY.roundToInt().coerceIn(topPx, maxY)
                IntOffset(x, y)
            }
    )
}
