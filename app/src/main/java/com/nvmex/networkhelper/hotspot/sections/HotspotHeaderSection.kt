package com.nvmex.networkhelper.hotspot.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.Phase

/* ===========================
 * 2) Header / Status
 * =========================== */
@Composable
fun HotspotHeaderSection(
    phase: Phase,
    tetherIfaces: List<String>,
    configApplyResult: String?,
    lastError: String?,
    lastEvent: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.hotspot_console_title), style = MaterialTheme.typography.headlineSmall)

        PhaseStatusCardFixedColors(
            phase = phase,
            lastError = lastError
        )

        if (tetherIfaces.isNotEmpty()) {
            InfoLineCardMd3(
                title = stringResource(R.string.hotspot_tethering_ifaces),
                content = tetherIfaces.joinToString()
            )
        }

        if (!configApplyResult.isNullOrBlank()) {
            InfoLineCardMd3(
                title = stringResource(R.string.hotspot_config_result),
                content = configApplyResult,
                maxLines = 10
            )
        }

        if (!lastError.isNullOrBlank()) {
            ErrorLineCardFixed(
                title = stringResource(R.string.state_error),
                content = lastError,
                maxLines = 12
            )
        }

        if (!lastEvent.isNullOrBlank()) {
            InfoLineCardMd3(
                title = stringResource(R.string.hotspot_message),
                content = lastEvent,
                maxLines = 8
            )
        }
    }
}

@Composable
private fun PhaseStatusCardFixedColors(
    phase: Phase,
    lastError: String?
) {
    val runningColor = Color(0xFF4CAF50)   // 绿
    val failedColor = Color(0xFFF44336)    // 红
    val pendingColor = Color(0xFFFFC107)   // 黄（STARTING/STOPPING）
    val idleColor = Color(0xFF607D8B)      // 蓝灰（IDLE）

    val hasError = !lastError.isNullOrBlank()

    val bg = when {
        hasError -> failedColor
        phase == Phase.RUNNING -> runningColor
        phase == Phase.STARTING || phase == Phase.STOPPING -> pendingColor
        else -> idleColor
    }

    // 这几种底色用白字最稳（黄底也 OK，但我给它更高对比一点）
    val fg = Color.White

    val headline = when {
        hasError -> stringResource(R.string.state_error)
        phase == Phase.RUNNING -> stringResource(R.string.hotspot_phase_running)
        phase == Phase.STARTING -> stringResource(R.string.hotspot_phase_starting)
        phase == Phase.STOPPING -> stringResource(R.string.hotspot_phase_stopping)
        else -> stringResource(R.string.hotspot_phase_idle)
    }

    val statusText = stringResource(R.string.hotspot_phase_status, phase.toString())
    val errorSuffix = stringResource(R.string.hotspot_phase_has_error)
    val sub = buildString {
        append(statusText)
        if (hasError) append(" · ").append(errorSuffix)
    }

    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleLarge,
                color = fg
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = fg.copy(alpha = 0.92f)
            )
        }
    }
}

/** ✅普通信息卡：依赖 MD3（自动适配日夜间） */
@Composable
private fun InfoLineCardMd3(
    title: String,
    content: String,
    maxLines: Int = 4
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            )
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 错误卡：固定红（不依赖系统） */
@Composable
private fun ErrorLineCardFixed(
    title: String,
    content: String,
    maxLines: Int = 8
) {
    val failedColor = Color(0xFFF44336)

    Surface(
        color = failedColor,
        contentColor = Color.White,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

