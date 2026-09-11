package com.nvmex.networkhelper.iperf.client

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun SpeedGauge(
    currentMbps: Float,
    points: List<IperfPoint>,
    maxMbps: Float = 2000f,
    modifier: Modifier = Modifier
) {
    val outlineColor = MaterialTheme.colorScheme.outline
    val titleStyle = MaterialTheme.typography.titleMedium
    val valueStyle = MaterialTheme.typography.headlineSmall

    val startAngle = -210f
    val sweepAngle = 240f

    fun valueToAngle(v: Float): Float {
        val vv = v.coerceAtLeast(0f)
        val t = (log10(1f + vv) / log10(1f + maxMbps)).coerceIn(0f, 1f)
        return startAngle + t * sweepAngle
    }

    val targetAngle = valueToAngle(currentMbps)
    val animatedAngle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "SpeedGaugeAngle"
    )

    val tickValues = listOf(0f, 1f, 5f, 10f, 50f, 100f, 500f, 1000f, 2000f)
        .filter { it <= maxMbps }
    var showMBps by rememberSaveable { mutableStateOf(false) } // false=Mbps, true=MB/s
    fun formatSpeed(mbps: Float): String {
        val v = if (showMBps) mbps / 8f else mbps
        return if (showMBps) String.format("%.2f MB/s", v) else String.format("%.2f Mbps", v)
    }

    val progressColor = Color(0xFFFF5722)
    val lineColor = Color(0xFFFF9800)
    val sparkColor = lineColor.copy(alpha = 0.55f)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("实时速率", style = titleStyle)

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    val pad = 18.dp.toPx()
                    val gap = 10.dp.toPx()

                    // =========================
                    // 1) 顶部折线区域（不与仪表盘重叠）
                    // =========================
                    val sparkH = (h * 0.22f).coerceIn(80.dp.toPx(), 110.dp.toPx())
                    val sparkTop = pad
                    val sparkBottom = sparkTop + sparkH

                    if (points.size >= 2) {
                        // 你可以限制最大显示点数，避免线太密；不限制也行
                        val maxPoints = 20
                        val data = if (points.size > maxPoints) points.takeLast(maxPoints) else points

                        val safeMax = max(1.0, data.maxOfOrNull { it.mbps } ?: 1.0)

                        val plotW = (w - 2f * pad).coerceAtLeast(1f)
                        val n = data.size

                        // ✅ 关键：随着点数增加，折线“占用宽度”从 0 -> plotW
                        // 比如 60 个点铺满；刚开始 2 个点只占很小一截
                        val fillCount = maxPoints.coerceAtLeast(2)
                        val progress = ((n - 1).toFloat() / (fillCount - 1).toFloat()).coerceIn(0f, 1f)
                        val usedW = plotW * progress

                        val stepX = (usedW / (n - 1).coerceAtLeast(1)).coerceAtLeast(0f)

                        fun xOf(idx: Int): Float = pad + idx * stepX

                        fun yOf(mbps: Double): Float {
                            val k = (mbps / safeMax).coerceIn(0.0, 1.0)
                            return (sparkBottom - (k * sparkH).toFloat())
                        }

                        // 底线
                        drawLine(
                            color = outlineColor.copy(alpha = 0.25f),
                            start = Offset(pad, sparkBottom),
                            end = Offset(pad + usedW, sparkBottom), // ✅ 只画到当前使用宽度
                            strokeWidth = 1.5f
                        )

                        val path = Path()
                        data.forEachIndexed { idx, p ->
                            val x = xOf(idx)
                            val y = yOf(p.mbps)
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }

                        drawPath(
                            path = path,
                            color = sparkColor,
                            style = Stroke(width = 3f, cap = StrokeCap.Round)
                        )

                        // 末端点（当前最新点）
                        val lastIdx = n - 1
                        val last = data.last()
                        drawCircle(
                            color = sparkColor,
                            radius = 4.5f,
                            center = Offset(xOf(lastIdx), yOf(last.mbps))
                        )
                    }


                    // =========================
                    // 2) 仪表盘区域：从 sparkBottom + gap 开始
                    // =========================
                    val contentTop = sparkBottom + gap

                    val cx = w / 2f
                    // 把圆心限制在剩余区域内（比固定 0.82 更稳）
                    val cy = (contentTop + (h - contentTop) * 0.86f)

                    val stroke = min(w, (h - contentTop)) * 0.06f

                    val rByW = (w / 2f) - pad
                    val rByTop = (cy - contentTop) - pad
                    val radius = min(rByW, rByTop).coerceAtLeast(stroke * 2f)

                    // 背景弧
                    drawArc(
                        color = outlineColor.copy(alpha = 0.35f),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(radius * 2, radius * 2)
                    )

                    // 进度弧
                    val progressSweep =
                        (animatedAngle - startAngle).coerceIn(0f, sweepAngle)

                    if (progressSweep > 0.5f) {
                        drawArc(
                            color = progressColor,
                            startAngle = startAngle,
                            sweepAngle = progressSweep,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                            topLeft = Offset(cx - radius, cy - radius),
                            size = Size(radius * 2, radius * 2)
                        )
                    }

                    // 刻度（仍然在弧内侧）
                    tickValues.forEach { tv ->
                        val a = Math.toRadians(valueToAngle(tv).toDouble())
                        val inner = radius - stroke * 0.95f
                        val outer = radius - stroke * 0.20f

                        val x1 = cx + inner * cos(a).toFloat()
                        val y1 = cy + inner * sin(a).toFloat()
                        val x2 = cx + outer * cos(a).toFloat()
                        val y2 = cy + outer * sin(a).toFloat()

                        drawLine(
                            color = outlineColor.copy(alpha = 0.65f),
                            start = Offset(x1, y1),
                            end = Offset(x2, y2),
                            strokeWidth = stroke * 0.18f,
                            cap = StrokeCap.Round
                        )
                    }

                    // 指针
                    run {
                        val a = Math.toRadians(animatedAngle.toDouble())
                        val len = radius - stroke * 1.35f
                        val x = cx + len * cos(a).toFloat()
                        val y = cy + len * sin(a).toFloat()

                        drawLine(
                            color = lineColor,
                            start = Offset(cx, cy),
                            end = Offset(x, y),
                            strokeWidth = stroke * 0.22f,
                            cap = StrokeCap.Round
                        )
                        drawCircle(
                            color = lineColor,
                            radius = stroke * 0.35f,
                            center = Offset(cx, cy)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable { showMBps = !showMBps }
            ) {
                Text(text = formatSpeed(currentMbps), style = valueStyle)
                AssistChip(
                    onClick = { showMBps = !showMBps },
                    label = { Text(if (showMBps) "MB/s" else "Mbps") }
                )
            }

        }
    }
}



