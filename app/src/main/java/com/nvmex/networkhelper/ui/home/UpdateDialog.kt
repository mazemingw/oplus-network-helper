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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.home.UpdatePhase
import com.nvmex.networkhelper.network.model.VersionUpdateResponse
import java.time.ZoneId

@Composable
fun UpdateDialog(
    info: VersionUpdateResponse,
    mandatory: Boolean,
    downloadProgress: Int,
    downloadSpeed: Int,
    phase: UpdatePhase,
    error: String?,
    onDismiss: () -> Unit,
    onSkipThisVersion: () -> Unit,
    onUpdate: () -> Unit,
) {
    val updateLocked = phase.isBusy

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

                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when (phase) {
                        UpdatePhase.VERIFYING -> Text(stringResource(R.string.update_verifying))
                        UpdatePhase.OPENING_INSTALLER -> Text(stringResource(R.string.update_opening_installer))
                        UpdatePhase.READY_TO_INSTALL -> Text(stringResource(R.string.update_ready_to_install))
                        UpdatePhase.AWAITING_PERMISSION -> Text(stringResource(R.string.update_permission_hint))
                        UpdatePhase.INSTALLER_LAUNCHED -> Text(stringResource(R.string.update_installer_launched))
                        else -> Unit
                    }
                    if (phase == UpdatePhase.DOWNLOADING) {
                        Spacer(Modifier.height(6.dp))

                        Text(if (downloadProgress < 0) stringResource(R.string.update_download_unknown_size)
                            else stringResource(R.string.update_download_progress, downloadProgress))
                        Text(stringResource(R.string.update_download_speed, downloadSpeed))

                        if (downloadProgress < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !updateLocked, // ✅ 直接禁用
                onClick = {
                    if (updateLocked) return@Button
                    onUpdate()
                }
            ) {
                Text(
                    when {
                        phase == UpdatePhase.DOWNLOADING -> stringResource(R.string.update_downloading)
                        phase == UpdatePhase.VERIFYING -> stringResource(R.string.update_verifying)
                        phase == UpdatePhase.OPENING_INSTALLER -> stringResource(R.string.update_opening_installer)
                        phase == UpdatePhase.SIGNATURE_MISMATCH -> stringResource(R.string.update_continue_install)
                        error != null -> stringResource(R.string.update_retry)
                        phase == UpdatePhase.AWAITING_PERMISSION -> stringResource(R.string.update_continue_install)
                        phase == UpdatePhase.INSTALLER_LAUNCHED -> stringResource(R.string.update_reopen_installer)
                        phase == UpdatePhase.READY_TO_INSTALL -> stringResource(R.string.update_continue_install)
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


