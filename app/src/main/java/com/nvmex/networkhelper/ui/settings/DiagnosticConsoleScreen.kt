package com.nvmex.networkhelper.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nvmex.networkhelper.util.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DiagnosticConsoleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by DiagnosticReporter.logs.collectAsState()
    val dropped by DiagnosticReporter.dropped.collectAsState()
    val enabled by DiagnosticReporter.enabled.collectAsState()
    var level by remember { mutableStateOf<DiagnosticLevel?>(null) }
    val visible = remember(logs, level) { logs.filter { level == null || it.level == level } }
    var busy by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var receipt by rememberSaveable { mutableStateOf<String?>(null) }
    var lastReceipt by remember { mutableStateOf(DiagnosticReporter.lastReceiptCode()) }
    fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    fun copy(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > 512 * 1024) { toast("日志较大，请导出文件"); return }
        runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("日志", text)) }
            .onSuccess { toast("已复制") }.onFailure { toast("复制失败，请导出文件") }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val file = DiagnosticReporter.exportRedacted(context, visible)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri).use { output ->
                        checkNotNull(output); file.inputStream().use { it.copyTo(output) }
                    }
                }
                toast("已导出脱敏日志")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { toast("导出失败") }
            finally { busy = false }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("日志监控台") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("自动错误上报", Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = DiagnosticReporter::setEnabled, enabled = !busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = level == null, onClick = { level = null }, label = { Text("全部") })
                DiagnosticLevel.entries.forEach { item -> FilterChip(selected = level == item,
                    onClick = { level = item }, label = { Text(item.label) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val buttonShape = RoundedCornerShape(4.dp)
                OutlinedButton(onClick = { clear = true }, Modifier.weight(1f), enabled = !busy, shape = buttonShape,
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text("清除") }
                OutlinedButton(onClick = { scope.launch {
                    busy = true
                    try { copy(withContext(Dispatchers.IO) { DiagnosticReporter.formatLogs(visible) }) }
                    finally { busy = false }
                } }, Modifier.weight(1f),
                    enabled = !busy && visible.isNotEmpty(), shape = buttonShape, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("复制") }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { exportMenu = true }, Modifier.fillMaxWidth(), enabled = !busy && visible.isNotEmpty(),
                        shape = buttonShape, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("导出") }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(text = { Text("保存文件") }, onClick = { exportMenu = false; save.launch("networkhelper-diagnostics.json") })
                        DropdownMenuItem(text = { Text("分享日志") }, onClick = {
                            exportMenu = false
                            scope.launch {
                                busy = true
                                try {
                                    val file = DiagnosticReporter.exportRedacted(context, visible)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                    val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    send.clipData = ClipData.newRawUri("diagnostics", uri)
                                    context.startActivity(Intent.createChooser(send, "分享脱敏日志"))
                                } catch (error: CancellationException) { throw error }
                                catch (_: Exception) { toast("分享失败") }
                                finally { busy = false }
                            }
                        })
                    }
                }
                OutlinedButton(onClick = { scope.launch {
                    busy = true
                    try {
                        val code = DiagnosticReporter.uploadConsole(visible)
                        receipt = code; lastReceipt = code
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { toast(error.message ?: "上传失败") }
                    finally { busy = false }
                } }, Modifier.weight(1f), enabled = !busy && visible.isNotEmpty(), shape = buttonShape,
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text("上传") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${visible.size} 条 · 上限 16MB" + if (dropped > 0) " · 已限流 $dropped 条" else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                if (lastReceipt.isNotBlank()) TextButton(onClick = { receipt = lastReceipt }) { Text("最近回执") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无日志") }
            else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(visible, key = { it.id }) { entry -> ConsoleRow(entry) }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清除日志？") },
        text = { Text("清除监控台和待上传记录。") }, confirmButton = { TextButton(onClick = {
            clear = false; scope.launch {
                busy = true
                try { DiagnosticReporter.clearLogs() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { toast("清除失败") }
                finally { busy = false }
            }
        }) { Text("清除") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
    receipt?.let { code -> AlertDialog(onDismissRequest = { receipt = null }, title = { Text("日志上传回执") },
        text = { Column { SelectionContainer { Text(code, fontFamily = FontFamily.Monospace) }; Text("将回执提供给开发者即可定位日志。") } },
        confirmButton = { TextButton(onClick = { copy(code); receipt = null }) { Text("复制回执") } },
        dismissButton = { TextButton(onClick = { receipt = null }) { Text("关闭") } }) }
}

@Composable private fun ConsoleRow(entry: DiagnosticLogEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val color = when (entry.level) {
        DiagnosticLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        DiagnosticLevel.WARNING -> Color(0xFFB7791F)
        DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(Modifier.fillMaxWidth().clickable { expanded = !expanded }, shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(entry.lastAt))} · ${entry.level.label} · ${entry.source}" +
                if (entry.repeats > 1) " · ×${entry.repeats}" else "", color = color, style = MaterialTheme.typography.labelSmall)
            SelectionContainer { Text(entry.message, style = MaterialTheme.typography.bodySmall) }
            if (expanded && entry.detail.isNotBlank()) SelectionContainer { Text(entry.detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
