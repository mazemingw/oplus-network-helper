package com.nvmex.networkhelper.ui.wifi.sections

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.model.wifi.WifiUiModel
import com.nvmex.networkhelper.util.wifi.badToRatio
import com.nvmex.networkhelper.util.wifi.retryToRatio
import com.nvmex.networkhelper.util.wifi.rssiToRatio
import com.nvmex.networkhelper.util.wifi.speedToRatio

@Composable
fun WifiSignalBarsPanel(ui: WifiUiModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        WifiMetricBar(
            title = "RSSI",
            valueText = ui.rssiDbm?.let { "$it dBm" } ?: "-",
            ratio = ui.rssiDbm?.let { rssiToRatio(it) } ?: 0f
        )

        WifiMetricBar(
            title = "TX",
            valueText = ui.txSpeedMbps?.let { "$it Mbps" } ?: "-",
            ratio = ui.txSpeedMbps?.let { speedToRatio(it, ui.standardLabel) } ?: 0f
        )

        WifiMetricBar(
            title = "RX",
            valueText = ui.rxSpeedMbps?.let { "$it Mbps" } ?: "-",
            ratio = ui.rxSpeedMbps?.let { speedToRatio(it, ui.standardLabel) } ?: 0f
        )


        WifiMetricBar(
            title = "Retry/s",
            valueText = ui.txRetryPerSec?.let { String.format("%.1f /s", it) } ?: "-",
            ratio = ui.txRetryPerSec?.let { retryToRatio(it) } ?: 0f,
            invert = true // 重传越高越差：反向显示
        )

        WifiMetricBar(
            title = "Bad/s",
            valueText = ui.txBadPerSec?.let { String.format("%.1f /s", it) } ?: "-",
            ratio = ui.txBadPerSec?.let { badToRatio(it) } ?: 0f,
            invert = true
        )
    }
}

@Composable
private fun WifiMetricBar(
    title: String,
    valueText: String,
    ratio: Float,
    invert: Boolean = false,
    barHeight: Dp = 10.dp
) {
    val raw = ratio.coerceIn(0f, 1f)
    val target = if (invert) (1f - raw) else raw

    val hasValue = valueText.isNotBlank() && valueText != "-"

    // ===== 动画进度 =====
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "WifiMetricBar:$title"
    )

    // ===== 动态颜色 =====
    val barColor = if (!hasValue) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    } else {
        qualityColor(animated)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {

        // 左侧标题
        Text(
            text = title,
            modifier = Modifier.width(86.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )

        // 中间信号条（无白底、无描边）
        Box(
            modifier = Modifier
                .weight(1f)
                .height(barHeight)
                .clip(RoundedCornerShape(barHeight))
                // ✅ 直接用 surfaceVariant 当底板
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // 内槽（轨道）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            ) {
                // 进度部分
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animated)
                        .clip(RoundedCornerShape(999.dp))
                        .background(barColor)
                )

                // 50% 中线（可选，已经是自适应色）
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                        .align(Alignment.Center)
                )
            }
        }


        Spacer(Modifier.width(10.dp))

        // 右侧数值
        Text(
            text = valueText,
            modifier = Modifier.widthIn(min = 64.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun qualityColor(f: Float): Color {

    // 防御：确保永远在 0..1
    val v = f.coerceIn(0f, 1f)

    val bad = Color(0xFFFF3B30)   // 红（危险）
    val mid = Color(0xFFFFCC00)   // 黄（警告）
    val good = Color(0xFF34C759)  // 绿（健康）

    return when {
        v < 0.4f -> {
            // 红 -> 黄（危险区变化更敏感）
            lerp(bad, mid, v / 0.4f)
        }
        v < 0.7f -> {
            // 纯黄区（让“告警”稳定，不乱闪）
            mid
        }
        else -> {
            // 黄 -> 绿（好信号区变化更温和）
            lerp(mid, good, (v - 0.7f) / 0.3f)
        }
    }
}



