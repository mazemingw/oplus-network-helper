package com.nvmex.networkhelper.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.ui.chart.ChartPointDot
import com.nvmex.networkhelper.ui.chart.ChartPointLabel
import com.nvmex.networkhelper.ui.chart.ChartVerticalMarker
import com.nvmex.networkhelper.ui.chart.EChartsLineChart
import com.nvmex.networkhelper.ui.chart.chartXFraction
import com.nvmex.networkhelper.ui.chart.chartYFraction
import com.nvmex.networkhelper.ui.chart.progressiveChartX
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

private enum class Metric(val title: String) {
    // LTE
    RSSI("RSSI"),
    RSRP("RSRP"),
    RSRQ("RSRQ"),
    SINR("SINR"),

    // NR（SS）
    SS_RSRP("SS-RSRP"),
    SS_RSRQ("SS-RSRQ"),
    SS_SINR("SS-SINR"),
}

private data class ChartPointInfo(
    val text: String,
    val xFraction: Float,
    val yFraction: Float,
    val preferAbove: Boolean,
)

private data class ChartDotInfo(
    val xFraction: Float,
    val yFraction: Float,
)

private data class ChartMarkInfo(
    val id: Int,
    val xFraction: Float,
)

object BandColorRegistry {

    private val fixed = mapOf(
        // ===== 高频 =====
        "N79" to Color(0xFF7E57C2), // 深紫（最高频）
        "N78" to Color(0xFF5C6BC0), // 靛蓝
        "N41" to Color(0xFF2196F3), // 蓝（主力）

        // ===== 中频 =====
        "N1"  to Color(0xFF26A69A), // 青绿
        "N3"  to Color(0xFF66BB6A), // 绿

        // ===== 中低频 =====
        "N8"  to Color(0xFFD4E157), // 黄绿
        "N5"  to Color(0xFFFFCA28), // 黄

        // ===== 低频 =====
        "N28" to Color(0xFFFFA726), // 橙（低频覆盖）
    )



    private val palette = listOf(
        Color(0xFF26A69A), // teal（青绿）
        Color(0xFF42A5F5), // blue
        Color(0xFF66BB6A), // green
        Color(0xFFFFCA28), // amber
        Color(0xFFAB47BC), // purple
        Color(0xFF8D6E63), // brown
        Color(0xFF78909C), // blue-grey
        Color(0xFFEC407A), // pink（最后兜底，用得很少）
    )


    private val assigned = LinkedHashMap<String, Color>()

    // ✅ 统一提取 nxx/bxx（忽略大小写）
    private val BAND_RE = Regex("""(?i)\b(n\d+|b\d+)\b""")

    private fun normalizeBand(raw: String): String {
        val s = raw.trim()
        if (s.isBlank() || s == "-") return "UNKNOWN"

        // 先提取标准 band
        val m = BAND_RE.find(s)?.value
        return (m ?: s).uppercase()   // 统一成 "N41"/"B3"
    }

    fun colorOf(rawBand: String): Color {
        val band = normalizeBand(rawBand)
        // android.util.Log.i("NetworkHelper999", "[BAND-COLOR] raw='$rawBand' norm='$band' hitFixed=${fixed.containsKey(band)}")

        fixed[band]?.let { return it }
        assigned[band]?.let { return it }

        val c = palette[assigned.size % palette.size]
        assigned[band] = c
        return c
    }

    fun knownBands(): Set<String> = fixed.keys + assigned.keys
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



@Composable
fun SignalChartCard(
    sample: NetworkPanelUiState,
    modifier: Modifier = Modifier,
    windowSeconds: Int = 60,
    autoRange: Boolean = false,
    useEChartsChart: Boolean = false,
    playbackSampleKey: Long? = null,
    playbackFrameIndex: Int? = null,
    playbackSessionKey: Int? = null,
) {
    var latest by remember { mutableStateOf(sample) }
    SideEffect { latest = sample }

    val metrics = remember(latest.cellType) {
        if (latest.cellType == "NR")
            listOf(Metric.SS_RSRP, Metric.SS_RSRQ, Metric.SS_SINR)
        else
            listOf(Metric.RSSI, Metric.RSRP, Metric.RSRQ, Metric.SINR)
    }

    var selected by remember(latest.cellType) { mutableStateOf(metrics.first()) }

    val buffers = remember(latest.cellType) {
        metrics.associateWith { mutableStateListOf<Float>() }.toMutableMap()
    }

    // ✅ 每个点对应的小区 key（与 buffers 同步滑窗）
    val cellKeyBuffer = remember(latest.cellType) { mutableStateListOf<String>() }

    // ✅ 记录切换发生的点下标（相对窗口 0..windowSeconds-1）
    val handoverMarks = remember(latest.cellType) { mutableStateListOf<Int>() }

    var tick by remember(latest.cellType) { mutableIntStateOf(0) }
    var lastRaw by remember(latest.cellType) { mutableStateOf("-") }
    var lastParsed by remember(latest.cellType) { mutableStateOf("null") }

    // ✅ HUD：上一次切换信息
    var lastHandoverTick by remember(latest.cellType) { mutableIntStateOf(-1) }
    var lastCellKey by remember(latest.cellType) { mutableStateOf("") }
    var currentCellKey by remember(latest.cellType) { mutableStateOf("") }
    var prevCellKey by remember(latest.cellType) { mutableStateOf<String?>(null) }
    var lastPlaybackFrameIndex by remember(latest.cellType) { mutableIntStateOf(-1) }
    var lastPlaybackSession by remember(latest.cellType) { mutableIntStateOf(Int.MIN_VALUE) }

    data class BandSeg(var band: String, var start: Int, var end: Int)

    // ✅ 每个点对应的 band（与 buffers 同步滑窗）
    val bandKeyBuffer = remember(latest.cellType) { mutableStateListOf<String>() }

    // ✅ 按 band 合并成“区间段”，用于画半透明底色
    val bandSegments = remember(latest.cellType) { mutableStateListOf<BandSeg>() }


    // ✅ 主题色：必须在 Canvas 外取
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val markColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val haloColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f) // min/max 外圈
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f) // min/max 文字

    fun bandColorOf(band: String): Color {
        return BandColorRegistry.colorOf(band)
    }

     val BAND_RE = Regex("""(?i)\b(n\d+|b\d+)\b""") // 匹配 n41 / B3 等（忽略大小写）

    fun bandKeyOf(s: NetworkPanelUiState): String {
        val raw = s.band.trim()
        if (raw.isBlank() || raw == "-") return "UNKNOWN"

        // 先抓 nxx/bxx
        val m = BAND_RE.find(raw)?.value?.uppercase()
        if (m != null) return m  // 已经是 N41/B3 这种

        // ✅ 走到这里，说明 raw 可能是 "41" 这种纯数字
        val digits = raw.filter { it.isDigit() }
        if (digits.isBlank()) return raw

        return if (s.cellType.equals("NR", true)) {
            "N$digits"          // NR 下的 41 -> N41
        } else if (s.cellType.equals("LTE", true)) {
            "B$digits"          // LTE 下的 3 -> B3（看你数据会不会这样）
        } else {
            digits              // 其它制式：先别乱拼
        }
    }



    fun pushBandKey(key: String): Boolean {
        bandKeyBuffer.add(key)
        return if (bandKeyBuffer.size > windowSeconds) {
            bandKeyBuffer.removeAt(0)
            true
        } else false
    }

    fun updateBandSegments(newBand: String) {
        val idx = (bandKeyBuffer.size - 1).coerceAtLeast(0)
        val last = bandSegments.lastOrNull()
        if (last == null) {
            bandSegments.add(BandSeg(newBand, idx, idx))
        } else if (last.band == newBand) {
            last.end = idx
        } else {
            bandSegments.add(BandSeg(newBand, idx, idx))
        }
    }

    fun shiftBandSegmentsLeft() {
        // 每秒滑窗左移一格：start/end 都 -1，移除滑出窗口的段
        bandSegments.forEach { seg ->
            seg.start -= 1
            seg.end -= 1
        }
        bandSegments.removeIf { it.end < 0 }
        bandSegments.forEach { seg ->
            seg.start = seg.start.coerceAtLeast(0)
            seg.end = seg.end.coerceAtMost(windowSeconds - 1)
        }
    }


    fun cellKeyOf(s: NetworkPanelUiState): String {
        // 你也可以改成只用 CI 或 TAC+PCI 等，视你需求
        val ct = s.cellType.ifBlank { "-" }
        val tac = s.tac.ifBlank { "-" }
        val pci = s.pci.ifBlank { "-" }
        val ci = s.ci.ifBlank { "-" }
        return "$ct / $tac / $pci / $ci"
    }

    fun parseDb(str: String): Float? {
        val s = str.trim()
            .replace('－', '-')
            .replace("dBm", "", ignoreCase = true)
            .replace("dB", "", ignoreCase = true)
        if (s.isBlank() || s == "-" || s.contains("加载") || s.equals("null", true)) return null
        val m = Regex("""-?\d+(\.\d+)?""").find(s) ?: return null
        return m.value.toFloatOrNull()
    }

    fun rawOf(metric: Metric, s: NetworkPanelUiState): String = when (metric) {
        Metric.RSSI -> s.rssi
        Metric.RSRP -> s.rsrp
        Metric.RSRQ -> s.rsrq
        Metric.SINR -> s.sinr
        Metric.SS_RSRP -> s.ssRsrp
        Metric.SS_RSRQ -> s.ssRsrq
        Metric.SS_SINR -> s.ssSinr
    }

    fun extract(metric: Metric, s: NetworkPanelUiState): Float {
        val raw = rawOf(metric, s).ifBlank { "-" }
        val parsed = parseDb(raw) ?: Float.NaN
        if (metric == selected) {
            lastRaw = raw
            lastParsed = if (parsed.isNaN()) "NaN" else parsed.toString()
        }
        return parsed
    }

    fun yRange(metric: Metric): Pair<Float, Float> = when (metric) {
        Metric.RSSI -> -120f to -30f
        Metric.RSRP, Metric.SS_RSRP -> -140f to -50f
        Metric.RSRQ, Metric.SS_RSRQ -> -30f to 0f
        Metric.SINR, Metric.SS_SINR -> -20f to 50f
    }

    fun pushValue(metric: Metric, value: Float) {
        val list = buffers[metric] ?: return
        list.add(value)
        while (list.size > windowSeconds) list.removeAt(0)
    }

    fun pushCellKey(key: String): Boolean {
        cellKeyBuffer.add(key)
        return if (cellKeyBuffer.size > windowSeconds) {
            cellKeyBuffer.removeAt(0)
            true
        } else false
    }

    fun pushHandoverMarkIfNeeded(prevKey: String?, newKey: String) {
        if (prevKey != null && prevKey != newKey) {
            // ✅ 切换发生在“刚写入的新点”位置：windowSeconds - 1（右侧）
            val idx = (cellKeyBuffer.size - 1).coerceAtLeast(0)

            // 记录上一次切换信息
            lastHandoverTick = tick
            lastCellKey = prevKey

            // 记录分割线位置（滑窗下标）
            handoverMarks.add(idx)
        }
        // ✅ handoverMarks 也要随滑窗左移：当我们丢掉最老点时，idx 全部 -1，并移除 <0 的
        while (handoverMarks.any { it >= windowSeconds }) {
            // 理论上不会发生（idx<=windowSeconds-1），留个保险
            handoverMarks.removeIf { it >= windowSeconds }
        }
    }

    fun rebuildTimelineDecorations() {
        handoverMarks.clear()
        for (i in 1 until cellKeyBuffer.size) {
            if (cellKeyBuffer[i - 1] != cellKeyBuffer[i]) {
                handoverMarks.add(i)
            }
        }

        bandSegments.clear()
        bandKeyBuffer.forEachIndexed { idx, band ->
            val last = bandSegments.lastOrNull()
            if (last == null) {
                bandSegments.add(BandSeg(band, idx, idx))
            } else if (last.band == band) {
                last.end = idx
            } else {
                bandSegments.add(BandSeg(band, idx, idx))
            }
        }

        currentCellKey = cellKeyBuffer.lastOrNull().orEmpty()
        prevCellKey = currentCellKey.takeIf { it.isNotBlank() }
        val lastMark = handoverMarks.lastOrNull()
        if (lastMark != null) {
            lastCellKey = cellKeyBuffer.getOrNull(lastMark - 1).orEmpty()
            lastHandoverTick = tick
        } else {
            lastCellKey = ""
            lastHandoverTick = -1
        }
    }

    // ===== HUD：实时读当前选中指标 =====
    val hudRaw = remember(selected, latest, tick) { rawOf(selected, latest).ifBlank { "-" } }
    val hudParsed = remember(hudRaw) { parseDb(hudRaw)?.toString() ?: "NaN" }

// ✅ 采样周期：固定 1s（你要快再单独做“采样频率”开关）
    val samplePeriodMs = 1000L

// ✅ autoRange 打开时才需要这些参数
    val rangeEmaAlpha = if (autoRange) 0.18f else 0f
    val rangePaddingRatio = if (autoRange) 0.15f else 0f
    val minRange = if (autoRange) 3f else 0f


    fun sampleOnce() {
        val sNow = if (
            latest.nrMode.startsWith("NSA") &&
            !latest.preferNrInNsa &&
            latest.nsaLteAnchor != null
        ) latest.nsaLteAnchor!! else latest

        val keyNow = cellKeyOf(sNow)
        currentCellKey = keyNow

        val dropped = pushCellKey(keyNow)
        pushHandoverMarkIfNeeded(prevCellKey, keyNow)
        prevCellKey = keyNow

        val bandNow = bandKeyOf(sNow)
        pushBandKey(bandNow)
        updateBandSegments(bandNow)

        metrics.forEach { m ->
            val list = buffers[m] ?: return@forEach
            list.add(extract(m, sNow))
            if (list.size > windowSeconds) list.removeAt(0)
        }

        if (dropped) {
            handoverMarks.replaceAll { it - 1 }
            handoverMarks.removeIf { it < 0 }
            shiftBandSegmentsLeft()
        }

        tick++
    }

    fun clearTimeline() {
        buffers.values.forEach { it.clear() }
        cellKeyBuffer.clear()
        handoverMarks.clear()
        bandKeyBuffer.clear()
        bandSegments.clear()

        tick = 0
        lastHandoverTick = -1
        lastCellKey = ""
        currentCellKey = ""
        prevCellKey = null
    }

    fun popLastPlaybackFrame() {
        buffers.values.forEach { list ->
            if (list.isNotEmpty()) list.removeAt(list.lastIndex)
        }
        if (cellKeyBuffer.isNotEmpty()) cellKeyBuffer.removeAt(cellKeyBuffer.lastIndex)
        if (bandKeyBuffer.isNotEmpty()) bandKeyBuffer.removeAt(bandKeyBuffer.lastIndex)
        rebuildTimelineDecorations()
        tick++
    }

// ===== reset：只随 cellType 重置，不随 selected 或回放帧重置 =====
    LaunchedEffect(latest.cellType) {
        clearTimeline()
        lastPlaybackFrameIndex = -1
        lastPlaybackSession = Int.MIN_VALUE
    }

// ===== ticker：实时模式按 1s 采样；回放模式按回放帧采样 =====
    LaunchedEffect(latest.cellType, playbackSampleKey, playbackFrameIndex, playbackSessionKey) {
        if (playbackSampleKey != null && playbackFrameIndex != null) {
            val session = playbackSessionKey ?: 0
            if (lastPlaybackSession != session) {
                clearTimeline()
                lastPlaybackFrameIndex = -1
                lastPlaybackSession = session
            }

            when {
                lastPlaybackFrameIndex < 0 -> {
                    sampleOnce()
                }
                playbackFrameIndex > lastPlaybackFrameIndex -> {
                    sampleOnce()
                }
                playbackFrameIndex < lastPlaybackFrameIndex -> {
                    val removeCount = lastPlaybackFrameIndex - playbackFrameIndex
                    repeat(removeCount.coerceAtMost(cellKeyBuffer.size)) {
                        popLastPlaybackFrame()
                    }
                }
                else -> Unit
            }
            lastPlaybackFrameIndex = playbackFrameIndex
            return@LaunchedEffect
        }

        if (lastPlaybackSession != Int.MIN_VALUE) {
            clearTimeline()
            lastPlaybackFrameIndex = -1
            lastPlaybackSession = Int.MIN_VALUE
        }

        while (true) {
            sampleOnce()
            delay(samplePeriodMs)
        }
    }


    val rawList = buffers.getValue(selected)
    val data = remember(selected, tick) { rawList.toList() }
    val marks = remember(tick) { handoverMarks.toList() }
    val bandKeys = remember(tick) { bandKeyBuffer.toList() }
    val bandSegs = remember(tick) { bandSegments.map { it.copy() } }
    val (baseMin, baseMax) = remember(selected) { yRange(selected) }

// ✅ 平滑后的动态范围（状态）
    var smoothMin by remember(selected, latest.cellType) { mutableFloatStateOf(baseMin) }
    var smoothMax by remember(selected, latest.cellType) { mutableFloatStateOf(baseMax) }

// ✅ 根据窗口内数据计算“目标范围”，并做 EMA 平滑
    LaunchedEffect(selected, tick, autoRange) {
        val valid = buffers.getValue(selected).filter { !it.isNaN() }
        if (valid.isEmpty()) {
            smoothMin = baseMin
            smoothMax = baseMax
            return@LaunchedEffect
        }

        var mn = valid.minOrNull() ?: baseMin
        var mx = valid.maxOrNull() ?: baseMax

        if (!autoRange) {
            val baseSpan = (baseMax - baseMin).coerceAtLeast(1f)
            val pad = (baseSpan * 0.08f).coerceAtLeast(2f)
            smoothMin = if (mn < baseMin) mn - pad else baseMin
            smoothMax = if (mx > baseMax) mx + pad else baseMax
            return@LaunchedEffect
        }

        if (mx - mn < minRange) {
            val mid = (mx + mn) / 2f
            mn = mid - minRange / 2f
            mx = mid + minRange / 2f
        }

        val pad = ((mx - mn) * rangePaddingRatio).coerceAtLeast(1f)
        val targetMin = mn - pad
        val targetMax = mx + pad

        // EMA 平滑：越大越跟手（也更抖）
        val a = rangeEmaAlpha.coerceIn(0.05f, 0.9f)
        smoothMin = smoothMin + a * (targetMin - smoothMin)
        smoothMax = smoothMax + a * (targetMax - smoothMax)
    }

    val yMin = smoothMin
    val yMax = smoothMax

    val latestText = remember(selected, latest, tick) { rawOf(selected, latest).ifBlank { "-" } }

    val lastHandoverAgo = if (lastHandoverTick >= 0) (tick - lastHandoverTick) else -1
    val maxLabelPattern = stringResource(R.string.signal_chart_max, "%s")
    val minLabelPattern = stringResource(R.string.signal_chart_min, "%s")

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.signal_chart_title, windowSeconds),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    latestText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            //BTN ROW
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                metrics.forEach { m ->
                    val isSel = (m == selected)

                    AssistChip(
                        onClick = { selected = m },
                        label = {
                            Text(
                                m.title,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(28.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isSel) {
                                // ✅ 选中：明显但不炸
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                // ✅ 未选中：柔和、低存在感
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            labelColor = if (isSel) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        border = AssistChipDefaults.assistChipBorder(
                            enabled = true,
                            borderColor = if (isSel) {
                                // 选中边框几乎不可见（让填充说话）
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                            } else {
                                // 未选中边框 = 很淡的 outline
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            }
                        )
                    )
                }
            }

            val legendBands = remember(tick) {
                val out = ArrayList<String>()
                for (b in bandKeyBuffer) if (b.isNotBlank() && !out.contains(b)) out.add(b)
                out.take(10)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                legendBands.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(bandColorOf(b).copy(alpha = 0.35f), shape = CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(b, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }


            val chartOptionJson = remember(
                data,
                bandKeys,
                yMin,
                yMax,
                lineColor,
                gridColor,
                selected,
                windowSeconds,
                tick
            ) {
                val pointCount = data.size.coerceAtLeast(0)
                val windowFull = pointCount >= windowSeconds
                val animating = pointCount >= 2
                val updateDurationMs = if (animating) 420 else 0
                val firstAbsIndex = if (windowFull) (tick - pointCount).coerceAtLeast(0) else 0
                fun xOf(index: Int): Double {
                    return if (windowFull) {
                        index.toDouble()
                    } else {
                        progressiveChartX(index, pointCount, windowSeconds)
                    }
                }
                fun lineItem(index: Int, y: Any): JSONObject {
                    val id = "p-${firstAbsIndex + index}"
                    return JSONObject()
                        .put("id", id)
                        .put("name", id)
                        .put("value", JSONArray().put(xOf(index)).put(y))
                }
                val series = JSONArray()

                val bandSeriesNames = (BandColorRegistry.knownBands() + bandKeys)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()

                bandSeriesNames.forEach { band ->
                    val bandData = JSONArray()
                    data.forEachIndexed { index, value ->
                        val pointBand = bandKeys.getOrNull(index)
                        bandData.put(
                            lineItem(
                                index,
                                if (pointBand == band && !value.isNaN()) value.toDouble() else JSONObject.NULL
                            )
                        )
                    }
                    series.put(
                        JSONObject()
                            .put("id", "band-$band")
                            .put("name", "band-$band")
                            .put("type", "line")
                            .put("data", bandData)
                            .put("showSymbol", false)
                            .put("silent", true)
                            .put("connectNulls", false)
                            .put("animation", animating)
                            .put("animationDuration", if (animating) 220 else 0)
                            .put("animationDurationUpdate", updateDurationMs)
                            .put("animationEasingUpdate", "linear")
                            .put("lineStyle", JSONObject().put("opacity", 0).put("width", 0))
                            .put(
                                "areaStyle",
                                JSONObject()
                                    .put("origin", "start")
                                    .put("color", bandColorOf(band).toCssRgba(0.12f))
                            )
                            .put("emphasis", JSONObject().put("disabled", true))
                    )
                }

                val lineData = JSONArray()
                data.forEachIndexed { index, value ->
                    lineData.put(
                        lineItem(index, if (!value.isNaN()) value.toDouble() else JSONObject.NULL)
                    )
                }

                series.put(
                    JSONObject()
                        .put("id", "main")
                        .put("name", selected.title)
                        .put("type", "line")
                        .put("data", lineData)
                        .put("showSymbol", false)
                        .put("connectNulls", false)
                        .put("smooth", false)
                        .put("clip", true)
                        .put("animationDuration", if (animating) 220 else 0)
                        .put("animationDurationUpdate", updateDurationMs)
                        .put("animationEasingUpdate", "linear")
                        .put("lineStyle", JSONObject().put("color", lineColor.toCssRgba()).put("width", 2.5))
                        .put("itemStyle", JSONObject().put("color", lineColor.toCssRgba()))
                )

                JSONObject()
                    .put("animation", true)
                    .put("backgroundColor", "transparent")
                    .put("grid", JSONObject().put("left", 8).put("right", 8).put("top", 8).put("bottom", 46).put("containLabel", false))
                    .put("tooltip", JSONObject().put("show", false))
                    .put("axisPointer", JSONObject().put("show", false))
                    .put(
                        "xAxis",
                        JSONObject()
                            .put("type", "value")
                            .put("min", 0)
                            .put("max", windowSeconds - 1)
                            .put("splitNumber", 6)
                            .put("axisLine", JSONObject().put("show", false))
                            .put("axisTick", JSONObject().put("show", false))
                            .put("axisLabel", JSONObject().put("show", false))
                            .put("splitLine", JSONObject().put("show", true).put("lineStyle", JSONObject().put("color", gridColor.toCssRgba()).put("width", 1)))
                    )
                    .put(
                        "yAxis",
                        JSONObject()
                            .put("type", "value")
                            .put("min", yMin.toDouble())
                            .put("max", yMax.toDouble())
                            .put("axisLine", JSONObject().put("show", false))
                            .put("axisTick", JSONObject().put("show", false))
                            .put("axisLabel", JSONObject().put("show", false))
                            .put("splitLine", JSONObject().put("show", true).put("lineStyle", JSONObject().put("color", gridColor.toCssRgba()).put("width", 1)))
                    )
                    .put("series", series)
                    .toString()
            }

            val minMaxPoints = remember(data, yMin, yMax, windowSeconds, maxLabelPattern, minLabelPattern) {
                val valid = data.withIndex().filter { !it.value.isNaN() }
                if (valid.isEmpty()) {
                    null
                } else {
                    val pointCount = data.size.coerceAtLeast(0)
                    val maxItem = valid.maxBy { it.value }
                    val minItem = valid.minBy { it.value }
                    val maxYFraction = chartYFraction(maxItem.value, yMin, yMax)
                    val minYFraction = chartYFraction(minItem.value, yMin, yMax)
                    listOf(
                        ChartPointInfo(
                            text = maxLabelPattern.format(formatOneDecimal(maxItem.value)),
                            xFraction = chartXFraction(maxItem.index, pointCount, windowSeconds),
                            yFraction = maxYFraction,
                            preferAbove = maxYFraction >= 0.18f
                        ),
                        ChartPointInfo(
                            text = minLabelPattern.format(formatOneDecimal(minItem.value)),
                            xFraction = chartXFraction(minItem.index, pointCount, windowSeconds),
                            yFraction = minYFraction,
                            preferAbove = minYFraction > 0.82f
                        )
                    )
                }
            }
            val tailPoint = remember(data, yMin, yMax, windowSeconds) {
                val pointCount = data.size.coerceAtLeast(0)
                val lastValidIdx = data.indexOfLast { !it.isNaN() }
                if (lastValidIdx >= 0) {
                    ChartDotInfo(
                        xFraction = chartXFraction(lastValidIdx, pointCount, windowSeconds),
                        yFraction = chartYFraction(data[lastValidIdx], yMin, yMax)
                    )
                } else {
                    null
                }
            }
            val markInfos = remember(marks, data.size, tick, windowSeconds) {
                val pointCount = data.size.coerceAtLeast(0)
                val firstAbsIndex = if (pointCount >= windowSeconds) {
                    (tick - pointCount).coerceAtLeast(0)
                } else {
                    0
                }
                marks
                    .filter { it in data.indices }
                    .map {
                        ChartMarkInfo(
                            id = firstAbsIndex + it,
                            xFraction = chartXFraction(it, pointCount, windowSeconds)
                        )
                    }
            }
            var chartSize by remember { mutableStateOf(IntSize.Zero) }
            val chartAnimating = data.size >= 2

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .onSizeChanged { chartSize = it }
            ) {
                if (useEChartsChart) {
                    EChartsLineChart(
                        optionJson = chartOptionJson,
                        modifier = Modifier.fillMaxSize()
                    )

                    markInfos.forEach { mark ->
                        key(mark.id) {
                            ChartVerticalMarker(
                                xFraction = mark.xFraction,
                                animatePosition = chartAnimating,
                                animationDurationMillis = 420,
                                topInset = 8.dp,
                                bottomInset = 46.dp,
                                horizontalInset = 8.dp,
                                lineWidth = 2.dp,
                                color = markColor
                            )
                        }
                    }

                    minMaxPoints?.forEach { point ->
                        ChartPointDot(
                            xFraction = point.xFraction,
                            yFraction = point.yFraction,
                            chartSize = chartSize,
                            animatePosition = chartAnimating,
                            animationDurationMillis = 420,
                            topInset = 8.dp,
                            bottomInset = 46.dp,
                            horizontalInset = 8.dp,
                            color = lineColor,
                            borderColor = haloColor
                        )
                    }

                    tailPoint?.let { point ->
                        ChartPointDot(
                            xFraction = point.xFraction,
                            yFraction = point.yFraction,
                            chartSize = chartSize,
                            animatePosition = chartAnimating,
                            animationDurationMillis = 420,
                            topInset = 8.dp,
                            bottomInset = 46.dp,
                            horizontalInset = 8.dp,
                            color = lineColor,
                            borderColor = haloColor
                        )
                    }

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
                            bottomInset = 46.dp,
                            horizontalInset = 8.dp,
                            verticalGap = 8.dp,
                            color = labelColor
                        )
                    }
                } else {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val hudPadBottom = 46.dp.toPx()
                        val plotH = (h - hudPadBottom).coerceAtLeast(1f)
                        val yRange = (yMax - yMin).takeIf { it > 0.0001f } ?: 1f

                        fun normY(v: Float): Float {
                            val clamped = v.coerceIn(yMin, yMax)
                            val t = (clamped - yMin) / yRange
                            return plotH * (1f - t)
                        }

                        val stepX = if (windowSeconds <= 1) 0f else w / (windowSeconds - 1)
                        val startIndex = (windowSeconds - data.size).coerceAtLeast(0)

                        fun drawBandArea(segStart: Int, segEnd: Int, band: String) {
                            if (segEnd < segStart) return
                            var i = segStart
                            while (i <= segEnd) {
                                while (i <= segEnd) {
                                    val dataIndex = i - startIndex
                                    val v = data.getOrNull(dataIndex)
                                    if (v != null && !v.isNaN()) break
                                    i++
                                }
                                if (i > segEnd) break

                                val runStart = i
                                while (i <= segEnd) {
                                    val dataIndex = i - startIndex
                                    val v = data.getOrNull(dataIndex)
                                    if (v == null || v.isNaN()) break
                                    i++
                                }
                                val runEnd = i - 1
                                if (runEnd < runStart) continue

                                val path = androidx.compose.ui.graphics.Path()
                                val xStart = runStart * stepX
                                val xEnd = runEnd * stepX
                                path.moveTo(xStart, plotH)
                                for (xIdx in runStart..runEnd) {
                                    val dataIndex = xIdx - startIndex
                                    val v = data[dataIndex]
                                    path.lineTo(xIdx * stepX, normY(v))
                                }
                                path.lineTo(xEnd, plotH)
                                path.close()
                                drawPath(path = path, color = bandColorOf(band).copy(alpha = 0.12f))
                            }
                        }

                        bandSegs.forEach { seg ->
                            val start = (startIndex + seg.start).coerceAtLeast(startIndex)
                            val end = (startIndex + seg.end).coerceAtMost(startIndex + data.lastIndex)
                            drawBandArea(start, end, seg.band)
                        }

                        for (i in 1 until 4) {
                            val y = plotH * i / 4
                            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                        }
                        for (i in 1 until 6) {
                            val x = w * i / 6
                            drawLine(gridColor, Offset(x, 0f), Offset(x, plotH), strokeWidth = 1f)
                        }

                        marks.forEach { idx ->
                            val x = (startIndex + idx) * stepX
                            drawLine(markColor, Offset(x, 0f), Offset(x, plotH), strokeWidth = 2f)
                        }

                        var prev: Offset? = null
                        data.forEachIndexed { idx, v ->
                            val x = (startIndex + idx) * stepX
                            if (v.isNaN()) {
                                prev = null
                                return@forEachIndexed
                            }
                            val p = Offset(x, normY(v))
                            prev?.let { drawLine(lineColor, it, p, strokeWidth = 3f) }
                            prev = p
                        }

                        val lastValidIdx = data.indexOfLast { !it.isNaN() }
                        if (lastValidIdx >= 0) {
                            val p = Offset((startIndex + lastValidIdx) * stepX, normY(data[lastValidIdx]))
                            drawCircle(haloColor, radius = 7f, center = p)
                            drawCircle(lineColor, radius = 4.5f, center = p)
                        }

                        val valid = data.withIndex().filter { !it.value.isNaN() }
                        if (valid.isNotEmpty()) {
                            val maxItem = valid.maxBy { it.value }
                            val minItem = valid.minBy { it.value }
                            fun clampX(x: Float, pad: Float = 10f) = x.coerceIn(pad, w - pad)
                            fun clampY(y: Float, pad: Float = 10f) = y.coerceIn(pad, plotH - pad)
                            val maxP = Offset(clampX((startIndex + maxItem.index) * stepX), clampY(normY(maxItem.value)))
                            val minP = Offset(clampX((startIndex + minItem.index) * stepX), clampY(normY(minItem.value)))

                            drawCircle(haloColor, radius = 7f, center = maxP)
                            drawCircle(haloColor, radius = 7f, center = minP)
                            drawCircle(lineColor, radius = 4.5f, center = maxP)
                            drawCircle(lineColor, radius = 4.5f, center = minP)

                            drawContext.canvas.nativeCanvas.apply {
                                val paint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    textSize = 28f
                                    color = labelColor.toArgb()
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
                                        (p.y + 28f).coerceAtMost(plotH - 6f)
                                    }
                                    drawText(label, x, y, paint)
                                }

                                drawLabel(maxLabelPattern.format(formatOneDecimal(maxItem.value)), maxP, above = true)
                                drawLabel(minLabelPattern.format(formatOneDecimal(minItem.value)), minP, above = false)
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = "${selected.title}：$yMin ~ $yMax",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val handoverText = if (lastHandoverAgo >= 0 && lastCellKey.isNotBlank()) {
                        stringResource(R.string.signal_chart_last_cell, lastCellKey, lastHandoverAgo)
                    } else {
                        stringResource(R.string.signal_chart_last_cell_empty)
                    }

                    Text(
                        text = handoverText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

//                    Text(
//                        text = "debug: size=${data.size} tick=$tick raw=$hudRaw parsed=$hudParsed",
//                        style = MaterialTheme.typography.bodySmall,
//                        color = MaterialTheme.colorScheme.onSurfaceVariant
//                    )

                }
            }
        }
    }
}
