package com.nvmex.networkhelper.hotspot.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.Phase

/* ===========================
 * 6) Actions Section
 * =========================== */
@Composable
fun HotspotActionsSection(
    phase: Phase,
    configApplying: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenConfig: () -> Unit,
    onRestart: () -> Unit,
    onRefreshSnapshot: () -> Unit
) {
    // ===== 第一行：主操作 =====
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Button(
            onClick = onStart,
            enabled = phase == Phase.IDLE,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF448AFF),   // ✅启动热点高亮蓝
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF448AFF).copy(alpha = 0.38f),
                disabledContentColor = Color.White.copy(alpha = 0.7f)
            )
        ) {
            Text(stringResource(R.string.hotspot_action_start))
        }

        OutlinedButton(
            onClick = onStop,
            enabled = phase != Phase.IDLE,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.hotspot_action_stop))
        }
    }

    // ===== 第二行：配置类 =====
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = onOpenConfig,
            modifier = Modifier.weight(1f),
            enabled = !configApplying
        ) {
            Text(stringResource(R.string.hotspot_action_configure))
        }

        OutlinedButton(
            onClick = onRestart,
            modifier = Modifier.weight(1f),
            enabled = !configApplying
        ) {
            Text(stringResource(R.string.hotspot_action_restart))
        }
    }

    // ===== 第三行：工具操作 =====
    OutlinedButton(
        onClick = onRefreshSnapshot,
        modifier = Modifier.fillMaxWidth(),
        enabled = !configApplying
    ) {
        Text(stringResource(R.string.hotspot_action_refresh_snapshot))
    }

    Text(
        stringResource(R.string.hotspot_actions_flow_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
