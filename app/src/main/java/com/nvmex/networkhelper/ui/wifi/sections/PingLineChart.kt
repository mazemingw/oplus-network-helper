package com.nvmex.networkhelper.ui.wifi.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.wifi.PingSample
import com.nvmex.networkhelper.ui.chart.ChartPointLabel
import com.nvmex.networkhelper.ui.chart.EChartsLineChart
import com.nvmex.networkhelper.ui.chart.chartXFraction
import com.nvmex.networkhelper.ui.chart.chartYFraction
import com.nvmex.networkhelper.ui.chart.progressiveChartX
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

private data class PingPointInfo(
    val text: String,
    val xFraction: Float,
    val yFraction: Float,
    val preferAbove: Boolean,
)

@Composable
fun PingLineChart(
    samples: List<PingSample>,
    modifier: Modifier = Modifier,
    windowSeconds: Int = 60,
    yMaxMs: Double? = null,
    showGrid: Boolean = true,
    showMinMax: Boolean = true,
    marks: List<Int> = emptyList(),
    useEChartsChart: Boolean = false,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val markColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
    val haloColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    val maxLabelPattern = stringResource(R.string.signal_chart_max, "%s")
    val minLabelPattern = stringResource(R.string.signal_chart_min, "%s")

    val chartSamples = remember(samples, windowSeconds) {
        samples.takeLast(windowSeconds)
    }
    val rtts: List<Float?> = remember(chartSamples) {
        chartSamples.map { it.ms?.toFloat() }
    }
    var chartEpochMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(chartSamples.firstOrNull()?.t, chartSamples.isEmpty()) {
        if (chartSamples.isEmpty()) {
            chartEpochMs = 0L
        } else if (chartEpochMs == 0L) {
            chartEpochMs = chartSamples.first().t
        }
    }

    val yMax = remember(rtts, yMaxMs) {
        if (yMaxMs != null) {
            yMaxMs.toFloat()
        } else {
            val ok = rtts.filterNotNull()
            val max = ok.maxOrNull() ?: 100f
            (max * 1.2f).coerceAtLeast(10f)
        }
    }

    if (!useEChartsChart) {
        LegacyPingLineChart(
            rtts = rtts,
            modifier = modifier,
            windowSeconds = windowSeconds,
            yMax = yMax,
            showGrid = showGrid,
            showMinMax = showMinMax,
            marks = marks,
            lineColor = lineColor,
            gridColor = gridColor,
            markColor = markColor,
            textColor = textColor,
            haloColor = haloColor,
            maxLabelPattern = maxLabelPattern,
            minLabelPattern = minLabelPattern
        )
        return
    }

    val optionJson = remember(
        rtts,
        marks,
        yMax,
        showGrid,
        showMinMax,
        lineColor,
        gridColor,
        markColor,
        textColor,
        haloColor,
        maxLabelPattern,
        minLabelPattern,
        windowSeconds,
        chartSamples,
        chartEpochMs
    ) {
        val pointCount = rtts.size.coerceAtLeast(0)
        val windowFull = pointCount >= windowSeconds
        val animating = pointCount >= 2
        val updateDurationMs = if (animating) 420 else 0
        val epochMs = chartEpochMs.takeIf { it != 0L } ?: chartSamples.firstOrNull()?.t ?: 0L
        fun absoluteX(index: Int): Double {
            val t = chartSamples.getOrNull(index)?.t ?: (epochMs + index * 1000L)
            return (t - epochMs).toDouble() / 1000.0
        }
        val xAxisMax = if (windowFull) {
            absoluteX(pointCount - 1)
        } else {
            (windowSeconds - 1).toDouble()
        }
        val xAxisMin = if (windowFull) {
            xAxisMax - (windowSeconds - 1).toDouble()
        } else {
            0.0
        }
        fun xOf(index: Int): Double {
            return if (windowFull) absoluteX(index) else progressiveChartX(index, pointCount, windowSeconds)
        }
        fun lineItem(index: Int, y: Any): JSONObject {
            val name = chartSamples.getOrNull(index)?.t?.toString() ?: index.toString()
            return JSONObject()
                .put("name", "p-$name")
                .put("value", JSONArray().put(xOf(index)).put(y))
        }
        val lineData = JSONArray()
        rtts.forEachIndexed { index, value ->
            lineData.put(
                lineItem(index, value?.toDouble() ?: JSONObject.NULL)
            )
        }

        val handoverData = JSONArray()
        marks.forEach { idx ->
            if (idx in rtts.indices) {
                val x = xOf(idx)
                handoverData.put(JSONArray().put(x).put(0))
                handoverData.put(JSONArray().put(x).put(yMax.toDouble()))
                handoverData.put(JSONArray().put(JSONObject.NULL).put(JSONObject.NULL))
            }
        }

        val maxPointData = JSONArray()
        val minPointData = JSONArray()
        if (showMinMax) {
                val valid = rtts.withIndex().filter { it.value != null }
                if (valid.isNotEmpty()) {
                    val maxItem = valid.maxBy { it.value!! }
                    val minItem = valid.minBy { it.value!! }
                    maxPointData.put(
                        JSONObject()
                            .put("name", "max")
                            .put("value", JSONArray().put(xOf(maxItem.index)).put(maxItem.value!!.toDouble()))
                    )
                    minPointData.put(
                        JSONObject()
                            .put("name", "min")
                            .put("value", JSONArray().put(xOf(minItem.index)).put(minItem.value!!.toDouble()))
                    )
                }
        }

        val series = JSONArray()
        series.put(
            JSONObject()
                .put("id", "main")
                .put("name", "Gateway RTT")
                .put("type", "line")
                .put("data", lineData)
                .put("showSymbol", false)
                .put("connectNulls", false)
                .put("smooth", false)
                .put("clip", true)
                .put("animationDuration", if (animating) 180 else 0)
                .put("animationDurationUpdate", updateDurationMs)
                .put("animationEasingUpdate", "linear")
                .put("lineStyle", JSONObject().put("color", lineColor.toCssRgba()).put("width", 2.5))
                .put("itemStyle", JSONObject().put("color", lineColor.toCssRgba()))
        )

        series.put(
            JSONObject()
                .put("id", "handover")
                .put("name", "handover")
                .put("type", "line")
                .put("data", handoverData)
                .put("showSymbol", false)
                .put("connectNulls", false)
                .put("silent", true)
                .put("animation", animating)
                .put("animationDuration", if (animating) 180 else 0)
                .put("animationDurationUpdate", updateDurationMs)
                .put("animationEasingUpdate", "linear")
                .put("lineStyle", JSONObject().put("color", markColor.toCssRgba()).put("width", 1.5))
        )

        series.put(
            JSONObject()
                .put("id", "max-point")
                .put("name", "max-point")
                .put("type", "scatter")
                .put("data", maxPointData)
                .put("symbolSize", 7)
                .put("silent", true)
                .put("animation", animating)
                .put("animationDuration", if (animating) 180 else 0)
                .put("animationDurationUpdate", updateDurationMs)
                .put("animationEasingUpdate", "linear")
                .put("itemStyle", JSONObject().put("color", lineColor.toCssRgba()).put("borderColor", haloColor.toCssRgba()).put("borderWidth", 2))
                .put(
                    "label",
                    JSONObject().put("show", false)
                )
        )

        series.put(
            JSONObject()
                .put("id", "min-point")
                .put("name", "min-point")
                .put("type", "scatter")
                .put("data", minPointData)
                .put("symbolSize", 7)
                .put("silent", true)
                .put("animation", animating)
                .put("animationDuration", if (animating) 180 else 0)
                .put("animationDurationUpdate", updateDurationMs)
                .put("animationEasingUpdate", "linear")
                .put("itemStyle", JSONObject().put("color", lineColor.toCssRgba()).put("borderColor", haloColor.toCssRgba()).put("borderWidth", 2))
                .put(
                    "label",
                    JSONObject().put("show", false)
                )
        )

        val lastValidIdx = rtts.indexOfLast { it != null }
        val tailData = JSONArray()
        if (lastValidIdx >= 0) {
            tailData.put(
                JSONArray()
                    .put(xOf(lastValidIdx))
                    .put(rtts[lastValidIdx]!!.toDouble())
            )
        }
        series.put(
            JSONObject()
                .put("id", "tail")
                .put("name", "tail")
                .put("type", "scatter")
                .put("data", tailData)
                .put("symbolSize", 7)
                .put("silent", true)
                .put("animation", animating)
                .put("animationDuration", if (animating) 180 else 0)
                .put("animationDurationUpdate", updateDurationMs)
                .put("animationEasingUpdate", "linear")
                .put("itemStyle", JSONObject().put("color", lineColor.toCssRgba()).put("borderColor", haloColor.toCssRgba()).put("borderWidth", 2))
        )

        JSONObject()
            .put("animation", true)
            .put("backgroundColor", "transparent")
            .put("grid", JSONObject().put("left", 0).put("right", 0).put("top", 8).put("bottom", 4).put("containLabel", false))
            .put("tooltip", JSONObject().put("show", false))
            .put("axisPointer", JSONObject().put("show", false))
            .put(
                "xAxis",
                JSONObject()
                    .put("type", "value")
                    .put("min", xAxisMin)
                    .put("max", xAxisMax)
                    .put("splitNumber", 6)
                    .put("axisLine", JSONObject().put("show", false))
                    .put("axisTick", JSONObject().put("show", false))
                    .put("axisLabel", JSONObject().put("show", false))
                    .put("splitLine", JSONObject().put("show", showGrid).put("lineStyle", JSONObject().put("color", gridColor.toCssRgba()).put("width", 1)))
            )
            .put(
                "yAxis",
                JSONObject()
                    .put("type", "value")
                    .put("min", 0)
                    .put("max", yMax.toDouble())
                    .put("axisLine", JSONObject().put("show", false))
                    .put("axisTick", JSONObject().put("show", false))
                    .put("axisLabel", JSONObject().put("show", false))
                    .put("splitLine", JSONObject().put("show", showGrid).put("lineStyle", JSONObject().put("color", gridColor.toCssRgba()).put("width", 1)))
            )
            .put("series", series)
            .toString()
    }

    val minMaxPoints = remember(rtts, yMax, showMinMax, maxLabelPattern, minLabelPattern, windowSeconds) {
        if (!showMinMax) {
            null
        } else {
            val valid = rtts.withIndex().filter { it.value != null }
            if (valid.isEmpty()) {
                null
            } else {
                val pointCount = rtts.size.coerceAtLeast(0)
                val maxItem = valid.maxBy { it.value!! }
                val minItem = valid.minBy { it.value!! }
                listOf(
                    PingPointInfo(
                        text = maxLabelPattern.format("${formatOneDecimal(maxItem.value!!)}ms"),
                        xFraction = chartXFraction(maxItem.index, pointCount, windowSeconds),
                        yFraction = chartYFraction(maxItem.value!!, 0f, yMax),
                        preferAbove = true
                    ),
                    PingPointInfo(
                        text = minLabelPattern.format("${formatOneDecimal(minItem.value!!)}ms"),
                        xFraction = chartXFraction(minItem.index, pointCount, windowSeconds),
                        yFraction = chartYFraction(minItem.value!!, 0f, yMax),
                        preferAbove = false
                    )
                )
            }
        }
    }
    var chartSize by remember { mutableStateOf(IntSize.Zero) }
    val chartAnimating = rtts.size >= 2

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .onSizeChanged { chartSize = it }
    ) {
        EChartsLineChart(
            optionJson = optionJson,
            modifier = Modifier.fillMaxSize()
        )

        minMaxPoints?.forEach { point ->
            ChartPointLabel(
                text = point.text,
                xFraction = point.xFraction,
                yFraction = point.yFraction,
                preferAbove = point.preferAbove,
                chartSize = chartSize,
                animatePosition = chartAnimating,
                animationDurationMillis = 420,
                topInset = 8.dp,
                bottomInset = 4.dp,
                horizontalInset = 4.dp,
                color = textColor
            )
        }
    }
}

@Composable
private fun LegacyPingLineChart(
    rtts: List<Float?>,
    modifier: Modifier,
    windowSeconds: Int,
    yMax: Float,
    showGrid: Boolean,
    showMinMax: Boolean,
    marks: List<Int>,
    lineColor: Color,
    gridColor: Color,
    markColor: Color,
    textColor: Color,
    haloColor: Color,
    maxLabelPattern: String,
    minLabelPattern: String,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        val w = size.width
        val h = size.height
        if (windowSeconds <= 1) return@Canvas

        fun normY(v: Float): Float {
            val t = v.coerceIn(0f, yMax) / yMax.coerceAtLeast(0.0001f)
            return h * (1f - t)
        }

        if (showGrid) {
            for (i in 1 until 4) {
                val y = h * i / 4
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            for (i in 1 until 6) {
                val x = w * i / 6
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }
        }

        val stepX = w / (windowSeconds - 1)
        fun xOf(idx: Int): Float {
            val last = rtts.size - 1
            return w - (last - idx) * stepX
        }

        marks.forEach { idx ->
            if (idx in rtts.indices) {
                val x = xOf(idx)
                drawLine(markColor, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
            }
        }

        var prev: Offset? = null
        rtts.forEachIndexed { idx, value ->
            if (value == null) {
                prev = null
                return@forEachIndexed
            }
            val p = Offset(xOf(idx), normY(value))
            prev?.let { drawLine(lineColor, it, p, strokeWidth = 3f) }
            prev = p
        }

        val lastValidIdx = rtts.indexOfLast { it != null }
        if (lastValidIdx >= 0) {
            val p = Offset(xOf(lastValidIdx), normY(rtts[lastValidIdx]!!))
            drawCircle(lineColor, radius = 5.5f, center = p)
        }

        if (showMinMax) {
            val valid = rtts.withIndex().filter { it.value != null }
            if (valid.isNotEmpty()) {
                val maxItem = valid.maxBy { it.value!! }
                val minItem = valid.minBy { it.value!! }

                fun clampX(x: Float, pad: Float = 10f) = x.coerceIn(pad, w - pad)
                fun clampY(y: Float, pad: Float = 10f) = y.coerceIn(pad, h - pad)

                val maxP = Offset(clampX(xOf(maxItem.index)), clampY(normY(maxItem.value!!)))
                val minP = Offset(clampX(xOf(minItem.index)), clampY(normY(minItem.value!!)))

                drawCircle(haloColor, radius = 7f, center = maxP)
                drawCircle(haloColor, radius = 7f, center = minP)
                drawCircle(lineColor, radius = 4.5f, center = maxP)
                drawCircle(lineColor, radius = 4.5f, center = minP)

                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        textSize = 28f
                        color = textColor.toArgb()
                    }

                    fun drawLabel(label: String, p: Offset, above: Boolean) {
                        val textW = paint.measureText(label)
                        val x = if (p.x + 10f + textW <= w) {
                            p.x + 10f
                        } else {
                            (p.x - 10f - textW).coerceAtLeast(0f)
                        }
                        val y = if (above) {
                            (p.y - 10f).coerceAtLeast(28f)
                        } else {
                            (p.y + 28f).coerceAtMost(h - 6f)
                        }
                        drawText(label, x, y, paint)
                    }

                    drawLabel(maxLabelPattern.format("${formatOneDecimal(maxItem.value!!)}ms"), maxP, above = true)
                    drawLabel(minLabelPattern.format("${formatOneDecimal(minItem.value!!)}ms"), minP, above = false)
                }
            }
        }
    }
}

private fun Color.toCssRgba(alphaOverride: Float? = null): String {
    val r = (red * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * 255f).roundToInt().coerceIn(0, 255)
    val a = (alphaOverride ?: alpha).coerceIn(0f, 1f)
    return "rgba($r,$g,$b,$a)"
}

private fun formatOneDecimal(value: Float): String {
    return String.format(Locale.US, "%.1f", value)
}
