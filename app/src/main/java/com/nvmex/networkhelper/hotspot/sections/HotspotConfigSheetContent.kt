package com.nvmex.networkhelper.hotspot.sections

import android.net.wifi.SoftApConfiguration
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.ChannelOption

/* ===========================
 * 1) Config Sheet Content
 * =========================== */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
 fun HotspotConfigSheetContent(
    ssid: String,
    onSsidChange: (String) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    hidden: Boolean,
    onHiddenChange: (Boolean) -> Unit,

    selectedBand: Int,
    onBandChange: (Int) -> Unit,
    selectedChannel: Int,
    onChannelChange: (Int) -> Unit,
    selectedBandwidth: Int,
    onBandwidthChange: (Int) -> Unit,

    capBand24: Boolean?,
    capBand5: Boolean?,
    capBand6: Boolean?,
    capBand60: Boolean?,
    channelOptions: List<ChannelOption>,
    bandwidthOptions: List<Int>,

    configApplying: Boolean,
    onCancel: () -> Unit,
    onApply: () -> Unit

) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.hotspot_config_title), style = MaterialTheme.typography.titleLarge)
        var showPassword by remember { mutableStateOf(true) }

        OutlinedTextField(
            value = ssid,
            onValueChange = onSsidChange,
            label = { Text("SSID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = passphrase,
            onValueChange = onPassphraseChange,
            label = { Text(stringResource(R.string.hotspot_password_label)) },
            singleLine = true,
            visualTransformation = if (showPassword)
                VisualTransformation.None
            else
                PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Image(
                        painter = painterResource(
                            id = if (showPassword)
                                R.drawable.visibility_off
                            else
                                R.drawable.visibility
                        ),
                        contentDescription = if (showPassword) {
                            stringResource(R.string.hotspot_hide_password)
                        } else {
                            stringResource(R.string.hotspot_show_password)
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )


        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = !hidden,
                onClick = { onHiddenChange(false) },
                label = { Text(stringResource(R.string.hotspot_visible)) }
            )
            FilterChip(
                selected = hidden,
                onClick = { onHiddenChange(true) },
                label = { Text(stringResource(R.string.hotspot_hidden_ssid)) }
            )
        }

        Divider()

        Text(stringResource(R.string.hotspot_band), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = selectedBand == SoftApConfiguration.BAND_2GHZ,
                onClick = { onBandChange(SoftApConfiguration.BAND_2GHZ) },
                label = { Text("2.4G") },
                enabled = capBand24 != false
            )
            FilterChip(
                selected = selectedBand == SoftApConfiguration.BAND_5GHZ,
                onClick = { onBandChange(SoftApConfiguration.BAND_5GHZ) },
                label = { Text("5G") },
                enabled = capBand5 != false
            )
            FilterChip(
                selected = selectedBand == SoftApConfiguration.BAND_6GHZ,
                onClick = { onBandChange(SoftApConfiguration.BAND_6GHZ) },
                label = { Text("6G") },
                enabled = capBand6 != false
            )
            FilterChip(
                selected = selectedBand == SoftApConfiguration.BAND_60GHZ,
                onClick = { onBandChange(SoftApConfiguration.BAND_60GHZ) },
                label = { Text("60G") },
                enabled = capBand60 != false
            )
        }

        // 带宽选择（API33+ 才真正生效，但 UI 统一暴露）
        BandwidthDropdown(
            options = bandwidthOptions,
            value = selectedBandwidth,
            onValueChange = onBandwidthChange,
            enabled = true
        )

        // 信道选择（包含 频率/DFS 标识，DFS 会被禁用）
        ChannelDropdown(
            options = channelOptions,
            value = selectedChannel,
            onValueChange = onChannelChange
        )
        Text(
            stringResource(R.string.hotspot_channel_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.action_cancel)) }

            Button(
                onClick = onApply,
                enabled = !configApplying && ssid.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (configApplying) {
                        stringResource(R.string.hotspot_applying)
                    } else {
                        stringResource(R.string.hotspot_apply_config)
                    }
                )
            }
        }

        Text(
            stringResource(R.string.hotspot_restart_tip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )


        if (Build.VERSION.SDK_INT < 33) {
            Text(
                stringResource(R.string.hotspot_api33_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}
