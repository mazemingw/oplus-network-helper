package com.nvmex.networkhelper.ui.cellparam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.cellparam.LteCellParam
import com.nvmex.networkhelper.model.cellparam.NrCellParam
import com.nvmex.networkhelper.util.cellparam.TemplateExportHelper
import com.nvmex.networkhelper.util.windows.WindowUtils
import com.nvmex.networkhelper.viewmodel.cellparam.ImportType
import com.nvmex.networkhelper.viewmodel.cellparam.ImportViewModel
import com.nvmex.networkhelper.viewmodel.cellparam.parseFile
import com.nvmex.networkhelper.xposed.logger.Logger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportCellParamsScreen(
    navController: NavHostController,
    viewModel: ImportViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val statusBarHeight = WindowUtils.getStatusBarHeight(context)

    var hasStoragePermission by remember { mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) }
    var isCheckingPermission by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) }
    var showFieldDescDialog by remember { mutableStateOf(false) }

    val unifiedButtonShape = RoundedCornerShape(14.dp)
    val unifiedButtonHeight = 44.dp
    val unifiedChipHeight = 40.dp

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasStoragePermission = granted
        isCheckingPermission = false

        if (granted) {
            Logger.log("READ_EXTERNAL_STORAGE 已授予")
        } else {
            Logger.log("READ_EXTERNAL_STORAGE 被拒绝")
        }
    }

    fun checkAndRequestPermissionIfNeeded() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                hasStoragePermission = true
                isCheckingPermission = false
            }

            else -> {
                // minSdk=29，所以只考虑 Android 10~12
                isCheckingPermission = true
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    LaunchedEffect(Unit) {
        checkAndRequestPermissionIfNeeded()
    }

    fun getDisplayName(context: android.content.Context, uri: android.net.Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                Logger.log("选择了文件: $uri")
                val fileName = getDisplayName(context, it)
                viewModel.setSelectedFile(it.toString(), fileName)

                coroutineScope.launch {
                    try {
                        val data = parseFile(context, it, uiState.importType)
                        @Suppress("UNCHECKED_CAST")
                        when (uiState.importType) {
                            ImportType.NR -> viewModel.setParsedData(data as List<NrCellParam>, uiState.importType)
                            ImportType.LTE -> viewModel.setParsedData(data as List<LteCellParam>, uiState.importType)
                        }
                        Toast.makeText(context, context.getString(R.string.import_cell_parse_success, data.size), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Logger.logE("解析失败", e)
                        Toast.makeText(context, context.getString(R.string.import_cell_parse_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            Logger.log("用户取消了文件选择")
        }
    }

    fun openFilePicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/csv",
                        "text/plain",
                        "application/csv",
                        "*/*"
                    )
                )
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

                try {
                    val downloadsUri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Download"
                    )
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
                } catch (e: Exception) {
                    Logger.log("设置初始目录失败: ${e.message}")
                }
            }
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Logger.logE("打开文件选择器失败", e)
            Toast.makeText(context, context.getString(R.string.import_cell_picker_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    fun exportAllTemplateFiles() {
        coroutineScope.launch {
            try {
                val result = TemplateExportHelper.exportAllTemplates(context)
                val msg = if (result.failedCount == 0) {
                    context.getString(R.string.import_cell_templates_released, result.successCount)
                } else {
                    context.getString(R.string.import_cell_templates_partial, result.successCount, result.failedCount)
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Logger.logE("释放模板失败", e)
                Toast.makeText(context, context.getString(R.string.import_cell_templates_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showFieldDescDialog) {
        val scrollState = rememberScrollState()

        AlertDialog(
            onDismissRequest = { showFieldDescDialog = false },
            title = {
                Text(
                    text = if (uiState.importType == ImportType.NR) {
                        stringResource(R.string.import_cell_nr_field_desc)
                    } else {
                        stringResource(R.string.import_cell_lte_field_desc)
                    }
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = TemplateExportHelper.getFieldDescription(context, uiState.importType),
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFieldDescDialog = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(statusBarHeight))
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    title = { Text(stringResource(R.string.import_cell_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isCheckingPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.import_cell_checking_permission))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { exportAllTemplateFiles() },
                    modifier = Modifier
                        .weight(1f)
                        .height(unifiedButtonHeight),
                    enabled = !isCheckingPermission,
                    shape = unifiedButtonShape
                ) {
                    Text(stringResource(R.string.import_cell_release_template))
                }

                OutlinedButton(
                    onClick = { showFieldDescDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(unifiedButtonHeight),
                    shape = unifiedButtonShape
                ) {
                    Text(stringResource(R.string.import_cell_field_help))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.importType == ImportType.NR,
                    onClick = { viewModel.setImportType(ImportType.NR) },
                    label = { Text("5G NR") },
                    shape = unifiedButtonShape,
                    modifier = Modifier.height(unifiedChipHeight)
                )
                FilterChip(
                    selected = uiState.importType == ImportType.LTE,
                    onClick = { viewModel.setImportType(ImportType.LTE) },
                    label = { Text("4G LTE") },
                    shape = unifiedButtonShape,
                    modifier = Modifier.height(unifiedChipHeight)
                )
            }

            Button(
                onClick = { openFilePicker() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(unifiedButtonHeight),
                enabled = !isCheckingPermission,
                shape = unifiedButtonShape
            ) {
                Text(
                    if (uiState.fileName.isNullOrBlank()) {
                        stringResource(R.string.import_cell_choose_file)
                    } else {
                        stringResource(R.string.import_cell_choose_again)
                    }
                )
            }

            if (uiState.fileName.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.import_cell_guide_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.import_cell_guide_body),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (!uiState.fileName.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.import_cell_selected_file),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = uiState.fileName ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (uiState.parsedDataCount > 0) {
                    Text(
                        text = stringResource(R.string.import_cell_parse_count, uiState.parsedDataCount),
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (uiState.parsedDataCount == 0 && !uiState.isUploading) {
                    Text(
                        text = stringResource(R.string.import_cell_no_data),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (uiState.parsedDataCount > 0) {
                Text(stringResource(R.string.import_cell_preview), style = MaterialTheme.typography.titleMedium)

                LazyColumn(
                    modifier = Modifier.height(200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val previewData = when (uiState.importType) {
                        ImportType.NR -> {
                            val data = viewModel.getParsedDataForCurrentType()
                            if (data is List<*>) data.filterIsInstance<NrCellParam>().take(5) else emptyList()
                        }

                        ImportType.LTE -> {
                            val data = viewModel.getParsedDataForCurrentType()
                            if (data is List<*>) data.filterIsInstance<LteCellParam>().take(5) else emptyList()
                        }
                    }

                    items(previewData) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = when (item) {
                                    is NrCellParam -> "GCellID: ${item.gcellId} | CellName: ${item.cellName}"
                                    is LteCellParam -> "ECI: ${item.eci} | CellName: ${item.cell_name}"
                                    else -> stringResource(R.string.import_cell_unknown_type)
                                },
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.saveToLocalDatabase() },
                    enabled = uiState.parsedDataCount > 0 && !uiState.isSavingLocal && !uiState.isUploading,
                    modifier = Modifier
                        .weight(1f)
                        .height(unifiedButtonHeight),
                    shape = unifiedButtonShape
                ) {
                    if (uiState.isSavingLocal) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.import_cell_saving))
                    } else {
                        Text(stringResource(R.string.import_cell_save_local))
                    }
                }

                Button(
                    onClick = { viewModel.uploadToServer() },
                    enabled = uiState.parsedDataCount > 0 && !uiState.isUploading && !uiState.isSavingLocal,
                    modifier = Modifier
                        .weight(1f)
                        .height(unifiedButtonHeight),
                    shape = unifiedButtonShape
                ) {
                    if (uiState.isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.import_cell_uploading))
                    } else {
                        Text(stringResource(R.string.import_cell_upload))
                    }
                }
            }

            if (uiState.uploadSuccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.import_cell_upload_success, uiState.uploadMessage?.let { "\n$it" } ?: ""),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (uiState.uploadError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.import_cell_upload_failed, uiState.uploadError ?: ""),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (uiState.localSaveSuccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.import_cell_local_success, uiState.localSaveMessage?.let { "\n$it" } ?: ""),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (uiState.localSaveError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.import_cell_local_failed, uiState.localSaveError ?: ""),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
