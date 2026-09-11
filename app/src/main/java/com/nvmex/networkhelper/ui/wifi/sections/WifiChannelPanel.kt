package com.nvmex.networkhelper.ui.wifi.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.wifi.WifiChannelSample
import com.nvmex.networkhelper.model.wifi.WifiUiModel
import kotlin.math.max

private enum class WifiBandFilter(
    val label: String,
    val channelPool: List<Int>,
    private val frequencyRange: IntRange
) {
    BAND_24(
        label = "2.4G",
        channelPool = (1..14).toList(),
        frequencyRange = 2400..2500
    ),
    BAND_5(
        label = "5G",
        channelPool = listOf(
            36, 40, 44, 48,
            52, 56, 60, 64,
            100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
            149, 153, 157, 161, 165
        ),
        frequencyRange = 5000..5895
    ),
    BAND_6(
        label = "6G",
        channelPool = listOf(
            1, 5, 9, 13, 17, 21, 25, 29,
            33, 37, 41, 45, 49, 53, 57, 61,
            65, 69, 73, 77, 81, 85, 89, 93,
            97, 101, 105, 109, 113, 117, 121, 125,
            129, 133, 137, 141, 145, 149, 153, 157,
            161, 165, 169, 173, 177, 181, 185, 189,
            193, 197, 201, 205, 209, 213, 217, 221,
            225, 229, 233
        ),
        frequencyRange = 5925..7125
    );

    fun supports(frequencyMhz: Int): Boolean = frequencyMhz in frequencyRange
}

@Composable
fun WifiChannelPanel(
    ui: WifiUiModel,
    modifier: Modifier = Modifier,
    lightMode: Boolean = false
) {
    val previewRows = 5
    val topLimit = 10
    val strongSignalThresholdDbm = -85

    var selectedBandName by rememberSaveable { mutableStateOf(WifiBandFilter.BAND_24.name) }
    var showAll by rememberSaveable { mutableStateOf(true) }
    var showTableHelp by rememberSaveable { mutableStateOf(false) }
    val selectedBand = remember(selectedBandName) { WifiBandFilter.valueOf(selectedBandName) }
    val allSamples = remember(ui.nearbyChannels, selectedBand) {
        ui.nearbyChannels
            .asSequence()
            .filter { selectedBand.supports(it.frequencyMhz) }
            .sortedByDescending { it.levelDbm }
            .toList()
    }
    val visibleSamples = remember(allSamples, showAll) {
        if (showAll) allSamples
        else {
            allSamples
                .filter { it.levelDbm >= strongSignalThresholdDbm }
                .ifEmpty { allSamples }
                .take(topLimit)
        }
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.wifi_channel_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "AP ${visibleSamples.size}/${allSamples.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WifiBandFilter.entries.forEach { band ->
                    FilterChip(
                        selected = band == selectedBand,
                        onClick = { selectedBandName = band.name },
                        label = { Text(band.label) }
                    )
                }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    modifier = Modifier.wrapContentWidth(),
                    selected = !showAll,
                    onClick = { showAll = false },
                    label = { Text(stringResource(R.string.wifi_filter_strong)) }
                )
                FilterChip(
                    modifier = Modifier.wrapContentWidth(),
                    selected = showAll,
                    onClick = { showAll = true },
                    label = { Text(stringResource(R.string.wifi_filter_all)) }
                )
            }

            if (allSamples.isEmpty()) {
                Text(
                    text = stringResource(R.string.wifi_scan_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = if (showAll) {
                        stringResource(R.string.wifi_channel_all_hint)
                    } else {
                        stringResource(R.string.wifi_channel_strong_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                WifiChannelChart(
                    samples = visibleSamples,
                    band = selectedBand,
                    lightMode = lightMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )

                val visibleItems = remember(visibleSamples) {
                    visibleSamples.distinctBy { it.bssid ?: "${it.ssid}|${it.channel}" }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.wifi_ap_list_title, visibleItems.size, visibleItems.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { showTableHelp = true }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = stringResource(R.string.wifi_param_help),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }

                WifiApTable(items = visibleItems)
                if (showTableHelp) {
                    WifiApTableHelpDialog(onDismiss = { showTableHelp = false })
                }
            }
        }
    }
}

@Composable
private fun WifiChannelChart(
    samples: List<WifiChannelSample>,
    band: WifiBandFilter,
    lightMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)
    val curveColor = MaterialTheme.colorScheme.primary
    val labelShadowColorArgb = MaterialTheme.colorScheme.surface.toArgb()

    Canvas(modifier = modifier) {
        data class LabelAnchor(val sample: WifiChannelSample, val x: Float, val y: Float, val color: Color)
        val axisChannels = buildAdaptiveAxisChannels(samples, band)
        val channelIndexMap = axisChannels.withIndex().associate { it.value to it.index }

        val width = size.width
        val height = size.height
        val paddingStart = 8.dp.toPx()
        val paddingEnd = 8.dp.toPx()
        val paddingTop = 10.dp.toPx()
        val paddingBottom = 20.dp.toPx()

        val plotLeft = paddingStart
        val plotRight = width - paddingEnd
        val plotTop = paddingTop
        val plotBottom = height - paddingBottom
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        fun xByChannel(channel: Int): Float {
            val idx = channelIndexMap[channel] ?: 0
            val steps = max(axisChannels.size - 1, 1)
            return plotLeft + plotWidth * (idx.toFloat() / steps)
        }

        fun yByDbm(levelDbm: Int): Float {
            val clamped = levelDbm.coerceIn(-95, -30)
            val fraction = (clamped + 95f) / 65f
            return plotBottom - plotHeight * fraction
        }

        drawLine(
            color = axisColor,
            start = Offset(plotLeft, plotBottom),
            end = Offset(plotRight, plotBottom),
            strokeWidth = 1.5f
        )

        // 每个信道位置增加淡竖线，增强读图定位感
        axisChannels.forEach { ch ->
            val x = xByChannel(ch)
            drawLine(
                color = axisColor.copy(alpha = 0.10f),
                start = Offset(x, plotTop),
                end = Offset(x, plotBottom),
                strokeWidth = 1f
            )
        }

        val tickChannels = chooseTickChannels(axisChannels, maxTicks = 8)

        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = textColor.toArgb()
            textSize = 22f
            textAlign = android.graphics.Paint.Align.CENTER
        }

        tickChannels.forEach { ch ->
            val x = xByChannel(ch)
            drawLine(
                color = axisColor,
                start = Offset(x, plotBottom),
                end = Offset(x, plotBottom + 6.dp.toPx()),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                ch.toString(),
                x,
                height - 2.dp.toPx(),
                textPaint
            )
        }

        val labelAnchors = mutableListOf<LabelAnchor>()

        samples.forEachIndexed { index, ap ->
            val centerX = xByChannel(ap.channel)
            val centerY = yByDbm(ap.levelDbm)

            val steps = max(axisChannels.size - 1, 1)
            val stepPx = plotWidth / steps
            val widthMhz = (ap.channelWidthMhz ?: 20).coerceAtLeast(20)
            val spanInSteps = (widthMhz / 20f).coerceAtLeast(1f)
            val xRadius = (stepPx * spanInSteps * 0.52f).coerceAtLeast(8.dp.toPx())

            val path = Path().apply {
                moveTo(centerX - xRadius, plotBottom)
                quadraticTo(centerX, centerY, centerX + xRadius, plotBottom)
                close()
            }

            val strength = ((ap.levelDbm + 95f) / 65f).coerceIn(0f, 1f)
            val hueShift = (index % 6) * 0.12f
            val baseColor = lerp(
                start = curveColor,
                stop = Color(0xFF16A34A),
                fraction = hueShift
            )
            val fillColor = baseColor.copy(alpha = (0.12f + strength * 0.28f).coerceIn(0.12f, 0.42f))
            val strokeColor = baseColor.copy(alpha = (0.55f + strength * 0.35f).coerceIn(0.5f, 0.95f))

            drawPath(
                path = path,
                color = fillColor
            )

            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = 2.8f, cap = StrokeCap.Round)
            )

            labelAnchors += LabelAnchor(
                sample = ap,
                x = centerX,
                y = centerY,
                color = strokeColor
            )
        }

        if (!lightMode) {
            val labelPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 20f
                textAlign = android.graphics.Paint.Align.CENTER
                setShadowLayer(2.5f, 0f, 0f, labelShadowColorArgb)
            }

            labelAnchors
                .sortedByDescending { it.sample.levelDbm }
                .forEachIndexed { idx, anchor ->
                    val text = compactSsid(anchor.sample.ssid)
                    val textX = anchor.x.coerceIn(plotLeft + 4.dp.toPx(), plotRight - 4.dp.toPx())
                    val baseY = (anchor.y - 8.dp.toPx()).coerceAtLeast(plotTop + 12.dp.toPx())
                    val stagger = (idx % 3) * 10.dp.toPx()
                    val textY = (baseY - stagger).coerceAtLeast(plotTop + 12.dp.toPx())
                    labelPaint.color = anchor.color.copy(alpha = 0.95f).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        text,
                        textX,
                        textY,
                        labelPaint
                    )
                }
        }
    }
}

private fun lerp(start: Color, stop: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (stop.red - start.red) * t,
        green = start.green + (stop.green - start.green) * t,
        blue = start.blue + (stop.blue - start.blue) * t,
        alpha = start.alpha + (stop.alpha - start.alpha) * t
    )
}

private fun compactSsid(ssid: String, maxLen: Int = 14): String {
    val clean = ssid.trim().ifEmpty { "<hidden>" }
    return if (clean.length <= maxLen) clean else clean.take(maxLen - 1) + "…"
}

@Composable
private fun WifiApTableHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        title = { Text(stringResource(R.string.wifi_param_help)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.wifi_help_ssid))
                Text(stringResource(R.string.wifi_help_freq))
                Text(stringResource(R.string.wifi_help_ch))
                Text(stringResource(R.string.wifi_help_bw))
                Text(stringResource(R.string.wifi_help_std))
                Text(stringResource(R.string.wifi_help_rssi_short))
                Text(stringResource(R.string.wifi_help_ue))
                Text(stringResource(R.string.wifi_help_busy))
            }
        }
    )
}

@Composable
private fun WifiApTable(items: List<WifiChannelSample>) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val rowBg = MaterialTheme.colorScheme.surfaceContainerLow
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = borderColor, shape = shape)
            .background(color = rowBg, shape = shape)
            .padding(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TableCell(text = "SSID", weight = 3.0f, isHeader = true)
            TableCell(text = "FREQ", weight = 1f, isHeader = true, align = TextAlign.End)
            TableCell(text = "CH", weight = 0.75f, isHeader = true, align = TextAlign.End)
            TableCell(text = "BW", weight = 0.9f, isHeader = true, align = TextAlign.End)
            TableCell(text = "STD", weight = 0.8f, isHeader = true, align = TextAlign.End)
            TableCell(text = "RSSI", weight = 1f, isHeader = true, align = TextAlign.End)
            TableCell(text = "UE", weight = 0.75f, isHeader = true, align = TextAlign.End)
            TableCell(text = "BUSY", weight = 0.9f, isHeader = true, align = TextAlign.End)
        }

        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(borderColor.copy(alpha = 0.6f))
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TableCell(text = item.ssid, weight = 3.0f, maxLines = 2)
                TableCell(text = item.frequencyMhz.toString(), weight = 1f, align = TextAlign.End)
                TableCell(text = item.channel.toString(), weight = 0.75f, align = TextAlign.End)
                TableCell(
                    text = item.channelWidthMhz?.let { "${it}M" } ?: "-",
                    weight = 0.9f,
                    align = TextAlign.End
                )
                TableCell(
                    text = item.wifiGeneration,
                    weight = 0.8f,
                    align = TextAlign.End
                )
                TableCell(text = "${item.levelDbm}", weight = 1f, align = TextAlign.End)
                TableCell(
                    text = item.ueCount?.toString() ?: "-",
                    weight = 0.75f,
                    align = TextAlign.End
                )
                TableCell(
                    text = item.busyPercent?.let { "$it%" } ?: "-",
                    weight = 0.9f,
                    align = TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    isHeader: Boolean = false,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = if (isHeader) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            color = if (isHeader) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun buildAdaptiveAxisChannels(
    samples: List<WifiChannelSample>,
    band: WifiBandFilter
): List<Int> {
    val pool = band.channelPool
    if (samples.isEmpty()) return pool

    val detected = samples
        .map { it.channel }
        .distinct()
        .sortedBy { pool.indexOf(it).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
    if (detected.isEmpty()) return pool

    if (band == WifiBandFilter.BAND_24) {
        val minCh = (detected.first() - 1).coerceAtLeast(1)
        val maxCh = (detected.last() + 1).coerceAtMost(14)
        return (minCh..maxCh).toList()
    }

    val firstIdx = pool.indexOf(detected.first()).coerceAtLeast(0)
    val lastIdx = pool.indexOf(detected.last()).coerceAtLeast(firstIdx)
    val axis = pool.subList(firstIdx, lastIdx + 1).toMutableList()

    var left = firstIdx - 1
    var right = lastIdx + 1
    while (axis.size < 6 && (left >= 0 || right < pool.size)) {
        if (left >= 0) axis.add(0, pool[left--])
        if (axis.size >= 6) break
        if (right < pool.size) axis.add(pool[right++])
    }
    return axis
}

private fun chooseTickChannels(axisChannels: List<Int>, maxTicks: Int): List<Int> {
    if (axisChannels.size <= maxTicks) return axisChannels
    val last = axisChannels.lastIndex
    return (0 until maxTicks).map { i ->
        val idx = ((i.toFloat() * last) / (maxTicks - 1)).toInt().coerceIn(0, last)
        axisChannels[idx]
    }.distinct()
}
