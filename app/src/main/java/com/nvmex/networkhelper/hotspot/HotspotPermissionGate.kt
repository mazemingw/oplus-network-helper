package com.nvmex.networkhelper.hotspot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.nvmex.networkhelper.R

@Composable
fun HotspotPermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current

    val needNearby = Build.VERSION.SDK_INT >= 33
    var locGranted by remember {
        mutableStateOf(
            hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasPerm(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }
    var nearbyGranted by remember {
        mutableStateOf(!needNearby || hasPerm(ctx, Manifest.permission.NEARBY_WIFI_DEVICES))
    }

    val locReq = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locGranted = it }
    val nearbyReq = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { nearbyGranted = it }

    val ok = locGranted && (!needNearby || nearbyGranted)

    if (!ok) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.hotspot_permission_title)) },
                supportingContent = { Text(stringResource(R.string.hotspot_permission_desc)) }
            )
            Divider()
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

            if (needNearby) {
                Button(
                    onClick = { if (!nearbyGranted) nearbyReq.launch(Manifest.permission.NEARBY_WIFI_DEVICES) },
                    enabled = !nearbyGranted
                ) {
                    Text(
                        if (nearbyGranted) {
                            stringResource(R.string.hotspot_nearby_permission_granted)
                        } else {
                            stringResource(R.string.hotspot_grant_nearby_permission)
                        }
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            ) { Text(stringResource(R.string.network_open_location_service)) }
        }
        return
    }

    content()
}

private fun hasPerm(ctx: Context, perm: String): Boolean {
    return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}
