package com.nvmex.networkhelper.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.network.model.VersionUpdateResponse
import java.time.ZoneId

@Composable
fun UpdateDialog(
    info: VersionUpdateResponse,
    mandatory: Boolean,
    downloadProgress: Int,
    downloadSpeed: Int,
    onDismiss: () -> Unit,
    onSkipThisVersion: () -> Unit,
    onUpdate: () -> Unit,
) {
    // ✅ 防抖锁
    var updateLocked by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!mandatory && !updateLocked) onDismiss()
        },
        title = {
            Text(if (mandatory) stringResource(R.string.update_title_mandatory) else stringResource(R.string.update_title_optional))
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.update_version, info.versionName, info.versionCode))

                    info.releaseDate
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            Text(stringResource(R.string.update_release_date, info.readableReleaseDate(zoneId = ZoneId.of("Asia/Shanghai"))))
                        }

                    Divider()

                    Text(info.updateContent)

                    if (downloadProgress > 0) {
                        Spacer(Modifier.height(6.dp))

                        Text(stringResource(R.string.update_download_progress, downloadProgress))
                        Text(stringResource(R.string.update_download_speed, downloadSpeed))

                        LinearProgressIndicator(
                            progress = downloadProgress / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !updateLocked, // ✅ 直接禁用
                onClick = {
                    if (updateLocked) return@Button
                    updateLocked = true
                    onUpdate()
                }
            ) {
                Text(
                    when {
                        updateLocked -> stringResource(R.string.state_processing)
                        mandatory -> stringResource(R.string.update_action_now)
                        else -> stringResource(R.string.update_action_update)
                    }
                )
            }
        },
        dismissButton = {
            if (!mandatory) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextButton(
                        enabled = !updateLocked,
                        onClick = onSkipThisVersion
                    ) {
                        Text(stringResource(R.string.update_action_skip_version))
                    }

                    TextButton(
                        enabled = !updateLocked,
                        onClick = onDismiss
                    ) {
                        Text(stringResource(R.string.update_action_later))
                    }
                }
            }
        }
    )
}


