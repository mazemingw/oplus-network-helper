package com.nvmex.networkhelper.hotspot.sections

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.utils.buildWifiQrPayload
import com.nvmex.networkhelper.hotspot.utils.generateQrBitmap

@Composable
fun HotspotStatusAndSnapshotPanel(
    // ===== Runtime (left) =====
    apState: String?,
    apFailureReason: String?,
    apBandText: String?,
    apFrequencyMhz: Int?,
    apChannel: Int?,
    apBandwidth: String?,
    apWifiStandard: Int?,
    apClients: Int?,

    // ===== Snapshot (right) =====
    ssid: String?,
    passphrase: String?,
    configBand: Int?,
    configChannel: Int?,
    configMaxBandwidth: Int?
) {
    // ✅反转逻辑：默认显示密码（show=true），点一下切隐藏
    var showPassword by remember { mutableStateOf(true) }
    var showQrDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.hotspot_status_runtime), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.hotspot_status_snapshot), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== Left: Runtime =====
            InfoColumn(
                modifier = Modifier.weight(1f),
                rows = listOf(
                    stringResource(R.string.hotspot_state) to (apState ?: "-"),
                    stringResource(R.string.hotspot_failure_reason) to (apFailureReason ?: "-"),
                    stringResource(R.string.hotspot_band) to (apBandText ?: "-"),
                    stringResource(R.string.hotspot_frequency) to (apFrequencyMhz?.let { "$it MHz" } ?: "-"),
                    stringResource(R.string.hotspot_channel) to (apChannel?.toString() ?: "-"),
                    stringResource(R.string.hotspot_width) to (apBandwidth ?: "-"),
                    stringResource(R.string.hotspot_standard) to (apWifiStandard?.toString() ?: "-"),
                    stringResource(R.string.hotspot_clients) to (apClients?.toString() ?: "-"),
                ),
                errorKey = if (!apFailureReason.isNullOrBlank()) stringResource(R.string.hotspot_failure_reason) else null
            )

            // ===== Right: Snapshot =====
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 先放不需要交互的行
                InfoColumn(
                    rows = listOf(
                        stringResource(R.string.hotspot_ssid) to (ssid ?: "-"),
                        stringResource(R.string.hotspot_target_band) to (configBand?.toString() ?: "-"),
                        stringResource(R.string.hotspot_target_channel) to (configChannel?.toString() ?: "-"),
                        stringResource(R.string.hotspot_target_width) to (configMaxBandwidth?.let { bandwidthLabel(it) } ?: "-"),
                    )
                )

                // ✅密码行：蓝色 + 点击切换显示/隐藏（反转默认行为）
                PasswordRow(
                    passphrase = passphrase,
                    showPassword = showPassword,
                    onToggle = { showPassword = !showPassword }
                )

                // ✅二维码入口：弹 dialog，不占面板空间
                QrEntryRow(
                    enabled = !ssid.isNullOrBlank(),
                    onClick = { showQrDialog = true }
                )
            }
        }
    }

    // ===== QR Dialog =====
    if (showQrDialog) {
        WifiQrDialog(
            onDismiss = { showQrDialog = false },
            ssid = ssid.orEmpty(),
            passphrase = passphrase
        )
    }
}

//密码行
@Composable
private fun PasswordRow(
    passphrase: String?,
    showPassword: Boolean,
    onToggle: () -> Unit
) {
    val blue = Color(0xFF448AFF)

    val valueText = when {
        passphrase == null -> "-"
        passphrase.isBlank() -> "(open)"
        showPassword -> passphrase
        else -> "••••••••"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.hotspot_password_prefix),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = valueText,
            style = MaterialTheme.typography.bodySmall,
            color = blue, // ✅固定蓝色
            modifier = Modifier.weight(0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }


}

//二维码点击工具
@Composable
private fun QrEntryRow(
    enabled: Boolean,
    onClick: () -> Unit
) {
    val blue = Color(0xFF448AFF)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.hotspot_wifi_qr_prefix),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = if (enabled) stringResource(R.string.hotspot_qr_click_to_show) else "-",
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) blue else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


//二维码窗口
@Composable
private fun WifiQrDialog(
    onDismiss: () -> Unit,
    ssid: String,
    passphrase: String?
) {
    val security = if (passphrase.isNullOrBlank()) "nopass" else "WPA"
    val payload = remember(ssid, passphrase) {
        buildWifiQrPayload(
            ssid = ssid,
            password = passphrase,
            hidden = false,
            security = security
        )
    }

    val sizeDp = 260.dp
    val sizePx = with(LocalDensity.current) { sizeDp.roundToPx() }

    val bmp = remember(payload, sizePx) {
        runCatching { generateQrBitmap(payload, sizePx) }.getOrNull() //err Unresolved reference 'generateQrBitmap'.
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        title = { Text(stringResource(R.string.hotspot_qr_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.hotspot_qr_ssid, ssid), style = MaterialTheme.typography.bodySmall)

                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(), //err Unresolved reference 'asImageBitmap'.
                        contentDescription = "Wi-Fi QR",
                        modifier = Modifier.size(sizeDp) // ERR Unresolved reference 'size'.
                    )
                } else {
                    Text(stringResource(R.string.hotspot_qr_failed), color = MaterialTheme.colorScheme.error)
                }

                // 如果你担心泄露 payload，可以删掉这一段
                Text(
                    text = stringResource(R.string.hotspot_qr_format, security, ssid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}


@Composable
private fun InfoColumn(
    modifier: Modifier = Modifier,
    rows: List<Pair<String, String>>,
    errorKey: String? = null
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { (k, v) ->
            InfoRow(
                keyText = k,
                valueText = v,
                isError = (errorKey != null && k == errorKey && v != "-" )
            )
        }
    }
}

@Composable
private fun InfoRow(
    keyText: String,
    valueText: String,
    isError: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$keyText：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
