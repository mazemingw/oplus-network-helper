package com.nvmex.networkhelper.util.network

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.nvmex.networkhelper.R

@Composable
fun NetworkPanelWithPermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current

    var phoneGranted by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.READ_PHONE_STATE)) }
    var locGranted by remember { mutableStateOf(hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)) }

    val phoneReq = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        phoneGranted = it
    }
    val locReq = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        locGranted = it
    }

    if (!phoneGranted || !locGranted) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.network_permission_title)) },
                supportingContent = {
                    Text(stringResource(R.string.network_permission_desc))
                }
            )
            Divider()
            Button(
                onClick = { if (!phoneGranted) phoneReq.launch(Manifest.permission.READ_PHONE_STATE) },
                enabled = !phoneGranted
            ) {
                Text(
                    if (phoneGranted) {
                        stringResource(R.string.network_phone_permission_granted)
                    } else {
                        stringResource(R.string.network_grant_phone_permission)
                    }
                )
            }

            Button(
                onClick = { if (!locGranted) locReq.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                enabled = !locGranted
            ) {
                Text(
                    if (locGranted) {
                        stringResource(R.string.network_location_permission_granted)
                    } else {
                        stringResource(R.string.network_grant_location_permission)
                    }
                )
            }

            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ) { Text(stringResource(R.string.network_open_location_service)) }
        }
        return
    }

    content()
}

private fun hasPerm(ctx: Context, perm: String): Boolean {
    return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}
