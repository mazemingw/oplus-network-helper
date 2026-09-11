package com.nvmex.networkhelper.ui.wifi.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R

@Composable
fun WifiParamsHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
        title = { Text(stringResource(R.string.wifi_params_help_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wifi_params_help_rssi))
                Text(stringResource(R.string.wifi_params_help_tx_rx))
                Text(stringResource(R.string.wifi_params_help_retry))
                Text(stringResource(R.string.wifi_params_help_bad))
                Text(stringResource(R.string.wifi_params_help_quality))
            }
        }
    )
}
