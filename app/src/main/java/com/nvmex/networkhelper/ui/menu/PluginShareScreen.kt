package com.nvmex.networkhelper.ui.menu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.windows.WindowUtils
import com.nvmex.networkhelper.viewmodel.menu.PluginShareViewModel
import com.nvmex.networkhelper.viewmodel.menu.toReadableSize
import com.nvmex.networkhelper.viewmodel.menu.toReadableSpeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginShareScreen(
    onBack: () -> Unit,
    vm: PluginShareViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val statusBarHeight = WindowUtils.getStatusBarHeight(context)
    val ui by vm.ui.collectAsState()
    val showProgress = ui.isBusy || ui.isUploading || ui.isDownloading
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = statusBarHeight),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                title = { Text(stringResource(R.string.plugin_share_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringResource(R.string.plugin_share_stage, stageLabel(ui.stage)), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.plugin_share_su,
                            when (ui.hasSu) {
                                true -> stringResource(R.string.plugin_share_available)
                                false -> stringResource(R.string.plugin_share_unavailable)
                                null -> stringResource(R.string.state_unknown)
                            }
                        )
                    )
                    Text(stringResource(R.string.plugin_share_brand, ui.brand.ifBlank { "-" }))
                    Text(stringResource(R.string.plugin_share_model, ui.model.ifBlank { "-" }))
                    Text(stringResource(R.string.plugin_share_system_version, ui.buildDisplayId.ifBlank { "-" }))
                    Text(
                        stringResource(
                            R.string.plugin_share_file,
                            when (ui.pluginExists) {
                                true -> stringResource(R.string.plugin_share_found, ui.pluginSizeBytes.toReadableSize())
                                false -> stringResource(R.string.plugin_share_not_found)
                                null -> stringResource(R.string.plugin_share_not_checked)
                            }
                        )
                    )
                    if (ui.packageSizeBytes > 0L) {
                        Text(stringResource(R.string.plugin_share_final_package, ui.packageSizeBytes.toReadableSize()))
                    }
                    if (ui.pluginSourcePath.isNotBlank()) {
                        Text(stringResource(R.string.plugin_share_source_path, ui.pluginSourcePath))
                    }
                    ui.ossObjectKey?.let { Text(stringResource(R.string.plugin_share_oss_object, it)) }
                    ui.requestId?.let { Text(stringResource(R.string.plugin_share_request_id, it)) }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = stringResource(R.string.plugin_share_tip),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }

            if (showProgress) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = when {
                                    ui.isUploading -> stringResource(R.string.plugin_share_uploading)
                                    ui.isDownloading -> stringResource(R.string.plugin_share_downloading)
                                    else -> stringResource(R.string.plugin_share_processing)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        LinearProgressIndicator(
                            progress = { ui.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(stringResource(R.string.plugin_share_progress, (ui.progress * 100).toInt()))
                        Text(stringResource(R.string.plugin_share_speed, ui.speedBps.toReadableSpeed()))
                    }
                }
            }

            ui.error?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.state_error_prefix, it),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (ui.success) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    stringResource(R.string.plugin_share_success),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Button(
                onClick = { vm.startShare() },
                enabled = !ui.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    if (ui.isBusy) {
                        stringResource(R.string.plugin_share_busy)
                    } else {
                        stringResource(R.string.plugin_share_start_upload)
                    }
                )
            }

            OutlinedButton(
                onClick = { vm.queryDownloadCandidates() },
                enabled = !ui.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text(
                    if (ui.isBusy) {
                        stringResource(R.string.plugin_share_busy)
                    } else {
                        stringResource(R.string.plugin_share_query_downloads)
                    }
                )
            }

            OutlinedButton(
                onClick = { showClearConfirm = true },
                enabled = !ui.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.plugin_share_clear_engineer_mode))
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (ui.showDownloadPicker) {
        AlertDialog(
            onDismissRequest = { vm.dismissDownloadPicker() },
            title = { Text(stringResource(R.string.plugin_share_pick_version)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ui.downloadCandidates.forEach { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(stringResource(R.string.plugin_share_version, item.buildDisplayId), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.plugin_share_size, item.fileSizeBytes.toReadableSize()))
                                Text(stringResource(R.string.plugin_share_updated_at, formatUpdatedAt(item.updatedAt)))
                                TextButton(
                                    onClick = { vm.selectDownloadCandidate(item) },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(stringResource(R.string.plugin_share_download_this))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissDownloadPicker() }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    ui.selectedDownloadCandidate?.let { selected ->
        AlertDialog(
            onDismissRequest = { vm.dismissDownloadConfirm() },
            title = { Text(stringResource(R.string.plugin_share_confirm_download_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.plugin_share_version, selected.buildDisplayId))
                    Text(stringResource(R.string.plugin_share_size, selected.fileSizeBytes.toReadableSize()))
                    Text(stringResource(R.string.plugin_share_write_to, "/data/data/com.oplus.engineernetwork/files/plugin-release.zip"))
                    Text(stringResource(R.string.plugin_share_continue_question))
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.downloadSelectedPlugin() }) {
                    Text(stringResource(R.string.plugin_share_confirm_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissDownloadConfirm() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.plugin_share_clear_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.plugin_share_clear_confirm_desc))
                    Text(
                        text = "/data/data/com.oplus.engineernetwork/files/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        vm.clearEngineerModeFiles()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.plugin_share_clear_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun stageLabel(raw: String): String {
    return when (raw) {
        "Ready" -> stringResource(R.string.plugin_stage_ready)
        "Checking SU..." -> stringResource(R.string.plugin_stage_checking_su)
        "Locating plugin file..." -> stringResource(R.string.plugin_stage_locating)
        "Preparing local copy..." -> stringResource(R.string.plugin_stage_preparing)
        "Requesting OSS sign..." -> stringResource(R.string.plugin_stage_requesting_sign)
        "Uploading..." -> stringResource(R.string.plugin_stage_uploading)
        "Upload completed" -> stringResource(R.string.plugin_stage_upload_completed)
        "Upload failed" -> stringResource(R.string.plugin_stage_upload_failed)
        "正在清除工程模式..." -> stringResource(R.string.plugin_stage_clearing)
        "工程模式已清除" -> stringResource(R.string.plugin_stage_cleared)
        "清除失败" -> stringResource(R.string.plugin_stage_clear_failed)
        else -> raw
    }
}

private fun formatUpdatedAt(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return "-"
    return text.replace("T", " ").replace("Z", "")
}
