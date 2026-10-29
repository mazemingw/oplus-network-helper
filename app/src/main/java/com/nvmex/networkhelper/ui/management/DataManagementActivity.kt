@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.nvmex.networkhelper.ui.management

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.nvmex.networkhelper.db.entity.ManagedCell
import com.nvmex.networkhelper.repository.CellParamLocalRepository
import com.nvmex.networkhelper.ui.home.*
import com.nvmex.networkhelper.ui.theme.NetworkHelperTheme
import com.nvmex.networkhelper.util.map.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class DataManagementActivity : ComponentActivity() {
    @Inject lateinit var localRepository: CellParamLocalRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NetworkHelperTheme {
                val kind = intent.getStringExtra("kind") ?: "local"
                Scaffold(topBar = { TopAppBar(title = { Text(when (kind) { "home" -> "首页日志管理"; "drive" -> "路测记录"; else -> "本地工参管理" }) },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                        when (kind) {
                            "home" -> HomeRecordsPage { file -> result("home_replay_path", file.absolutePath) }
                            "drive" -> DriveRecordsPage { sid, resume ->
                                setResult(Activity.RESULT_OK, Intent().putExtra("session_id", sid).putExtra("resume", resume)); finish()
                            }
                            else -> LocalCellsPage(localRepository)
                        }
                    }
                }
            }
        }
    }
    private fun result(key: String, value: String) { setResult(Activity.RESULT_OK, Intent().putExtra(key, value)); finish() }
    companion object {
        fun intent(context: Context, kind: String) = Intent(context, DataManagementActivity::class.java).putExtra("kind", kind)
    }
}

private fun stamp(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
private data class Transfers(val busy: Boolean, val importFile: () -> Unit, val exportFile: (String, suspend () -> String) -> Unit)
class TransferPayloadViewModel : androidx.lifecycle.ViewModel() {
    var text by mutableStateOf("")
}

@Composable
private fun rememberTransfers(importer: suspend (String) -> String, changed: () -> Unit): Transfers {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val currentImporter by rememberUpdatedState(importer)
    val currentChanged by rememberUpdatedState(changed)
    val payload: TransferPayloadViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var busy by remember { mutableStateOf(false) }
    fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val out = StringBuilder(); val buffer = CharArray(8192)
                        while (true) { val n = reader.read(buffer); if (n < 0) break; require(out.length + n <= 64 * 1024 * 1024) { "文件超过 64 MB，请拆分导入" }; out.append(buffer, 0, n) }
                        out.toString()
                    } ?: error("无法读取文件")
                    currentImporter(text)
                }
            }.onSuccess { message(it); currentChanged() }.onFailure { message(it.message ?: "导入失败") }
            busy = false
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) { payload.text = ""; busy = false }
        else scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                require(payload.text.isNotEmpty()) { "导出内容已失效，请重新导出" }
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入文件")
                stream.writer(Charsets.UTF_8).use { it.write(payload.text) }
            } }.onSuccess { message("CSV 已导出，可用 Excel 打开") }.onFailure { message(it.message ?: "导出失败") }
            payload.text = ""; busy = false
        }
    }
    return Transfers(busy, { open.launch(arrayOf("*/*")) }, { name, create ->
        scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) { create() } }.onSuccess { payload.text = it; save.launch(name) }
                .onFailure { message(it.message ?: "导出失败"); busy = false }
        }
    })
}

@Composable
private fun LocalCellsPage(repository: CellParamLocalRepository) {
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<ManagedCell>()) }
    var count by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(100) }
    var editor by remember { mutableStateOf<ManagedCell?>(null) }
    var deleting by remember { mutableStateOf<ManagedCell?>(null) }
    var adding by remember { mutableStateOf(false) }
    var rat by remember { mutableStateOf("LTE") }
    var ci by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var legacy by remember { mutableStateOf(false) }
    var editorLegacy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, revision, limit, legacy) {
        withContext(Dispatchers.IO) {
            if (legacy) repository.listLegacy(query.trim(), limit) to repository.countLegacy()
            else repository.listManaged(query.trim(), limit) to repository.countManaged()
        }.let { rows = it.first; count = it.second }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("本地工参 · $count 条", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !legacy, onClick = { legacy = false; limit = 100 }, label = { Text("我的工参") })
                FilterChip(selected = legacy, onClick = { legacy = true; limit = 100 }, label = { Text("历史本机副本") })
            }
            if (legacy) Text("旧版未区分来源的工参及查询缓存；此处操作仅影响本机副本。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it; limit = 100 }, Modifier.weight(1f), label = { Text("搜索名称或小区长号") }, singleLine = true)
                TextButton(onClick = { adding = true }) { Text("新增") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(rows, key = { "${it.rat}|${it.ci}" }) { row ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.name.ifBlank { "未命名基站" }, fontWeight = FontWeight.SemiBold)
                    Text("${row.rat} · ${row.ci}\nTAC=${row.tac ?: "—"} · PCI=${row.pci ?: "—"} · 频点=${row.arfcn ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    Text("WGS84：${row.latitude ?: "—"}, ${row.longitude ?: "—"} · 方位角 ${row.azimuth ?: "—"}°", style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = { editorLegacy = legacy; editor = row }) { Text("编辑") }; TextButton(onClick = { deleting = row }) { Text("删除") } }
                }
            }
        }
        if (rows.size >= limit) item { TextButton(onClick = { limit += 100 }) { Text("加载更多") } }
        if (rows.isEmpty()) item { Text("暂无匹配的本地工参") }
    }
    editor?.let { row -> LocalCellEditor(row, repository, { editor = null }, { if (editorLegacy) legacy = false; revision++ }, replaceLegacy = editorLegacy) }
    deleting?.let { row -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除本地工参？") }, text = { Text("${row.rat} · ${row.ci}\n${row.name}") },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                if (legacy) repository.deleteLegacy(row.rat, row.ci) else repository.deleteManaged(row.rat, row.ci)
            } }
                .onSuccess { deleting = null; revision++ }.onFailure { error = it.message }
        } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("新增本地基站") }, text = {
        Column { Row { listOf("LTE", "NR").forEach { type -> FilterChip(selected = rat == type, onClick = { rat = type }, label = { Text(type) }) } }
            OutlinedTextField(ci, { ci = it }, label = { Text(if (rat == "LTE") "ECI（长号）" else "NCI（长号）") }) }
    }, confirmButton = { TextButton(onClick = {
        runCatching { ManagedCell(rat, ci.trim(), "新基站").also { it.validate() } }.onSuccess { editorLegacy = false; legacy = false; editor = it.copy(name = ""); adding = false }.onFailure { error = it.message; adding = false }
    }) { Text("下一步") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
}

private data class HomeEntry(val file: File, val start: Long, val size: Int)
@Composable
private fun HomeRecordsPage(replay: (File) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember { mutableStateOf(emptyList<HomeEntry>()) }
    var revision by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<List<HomeLogSample>?>(null) }
    var previewLimit by remember { mutableIntStateOf(100) }
    var deleting by remember { mutableStateOf<HomeEntry?>(null) }
    val scope = rememberCoroutineScope()
    val transfers = rememberTransfers(importer = { text -> val samples = HomeLogFiles.importText(text); HomeLogFiles.save(context.filesDir, samples); "已导入 ${samples.size} 个采样" }, changed = { revision++ })
    LaunchedEffect(revision) { rows = withContext(Dispatchers.IO) { HomeLogFiles.list(context.filesDir).mapNotNull { file -> runCatching { val s = HomeLogFiles.read(file); HomeEntry(file, s.first().ts, s.size) }.getOrNull() } } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Row { Button(enabled = !transfers.busy, onClick = transfers.importFile) { Text("导入 CSV / JSON") }; Text("  共 ${rows.size} 条记录", Modifier.padding(12.dp)) } }
        items(rows, key = { it.file.name }) { entry ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp)) {
                    Text(stamp(entry.start), fontWeight = FontWeight.SemiBold)
                    Text("${entry.size} 个采样", style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(enabled = !transfers.busy, onClick = { scope.launch { previewLimit = 100; preview = withContext(Dispatchers.IO) { HomeLogFiles.read(entry.file) } } }) { Text("预览") }
                        TextButton(onClick = { replay(entry.file) }) { Text("回读") }
                        TextButton(enabled = !transfers.busy, onClick = { transfers.exportFile("home_log_${entry.start}.csv") { HomeLogFiles.csv(HomeLogFiles.read(entry.file)) } }) { Text("导出 CSV") }
                        TextButton(onClick = { deleting = entry }) { Text("删除") }
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Text("暂无日志；首页录制停止后会保存到这里。") }
    }
    preview?.let { samples -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("日志预览 · ${samples.size} 个采样") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            items(samples.take(previewLimit)) { s -> Text(stamp(s.ts) + "\n" + s.panelBySubId.values.joinToString("\n") { "Sub${it.subId} ${it.operatorName} ${it.cellType} PCI=${it.pci} RSRP=${it.rsrp}" }, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall) }
            if (samples.size > previewLimit) item { TextButton(onClick = { previewLimit += 100 }) { Text("加载更多") } }
        }
    }, confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }) }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除此日志？") }, text = { Text(stamp(entry.start)) },
        confirmButton = { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { HomeLogFiles.delete(context.filesDir, entry.file) }; deleting = null; revision++ } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun DriveRecordsPage(open: (String, Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { AppDb.get(context) }
    val dao = remember { db.drivePointDao() }
    var rows by remember { mutableStateOf(emptyList<DriveSessionSummary>()) }
    var names by remember { mutableStateOf(emptyMap<String, DriveSessionEntity?>()) }
    var revision by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<DrivePointEntity>?>(null) }
    var previewLimit by remember { mutableIntStateOf(100) }
    var deleting by remember { mutableStateOf<DriveSessionSummary?>(null) }
    val scope = rememberCoroutineScope()
    val transfers = rememberTransfers(importer = { text ->
        val recordings = DriveCsv.decode(text)
        db.withTransaction { recordings.forEach { r -> dao.recordBatch(r.points, r.city); dao.insertStations(r.stations) } }
        "已导入 ${recordings.size} 条路测记录、${recordings.sumOf { it.points.size }} 个打点"
    }, changed = { revision++ })
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            val sessions = dao.getSessionSummaries(Int.MAX_VALUE)
            sessions to sessions.associate { summary ->
                val meta = dao.getSession(summary.sessionId) ?: DriveSessionEntity(summary.sessionId, distanceMeters =
                    driveTrackLength(dao.getBySession(summary.sessionId).map { DriveTrackFix(it.ts, it.lat, it.lng, it.segmentStart) })).also { dao.putSession(it) }
                summary.sessionId to meta
            }
        }.let { rows = it.first; names = it.second }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索城市或开始时间") })
            Row { Button(enabled = !transfers.busy, onClick = transfers.importFile) { Text("导入路测 CSV") }; Text("  共 ${rows.size} 条记录", Modifier.padding(12.dp)) }
        }
        items(rows.filter { query.isBlank() || names[it.sessionId]?.cityName.orEmpty().contains(query) || stamp(it.startTs).contains(query) }, key = { it.sessionId }) { row ->
            val city = names[row.sessionId]?.cityName.orEmpty().ifBlank { "起点城市未知" }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp)) {
                    Text("$city · ${stamp(row.startTs)}", fontWeight = FontWeight.SemiBold)
                    Text("${row.pointCount} 个打点 · %.2f km".format((names[row.sessionId]?.distanceMeters ?: 0.0) / 1000), style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(enabled = !transfers.busy, onClick = { open(row.sessionId, false) }) { Text("回读") }
                        TextButton(enabled = !transfers.busy, onClick = { open(row.sessionId, true) }) { Text("继续录制") }
                        TextButton(enabled = !transfers.busy, onClick = { scope.launch { previewLimit = 100; preview = withContext(Dispatchers.IO) { dao.getBySession(row.sessionId) } } }) { Text("打点预览") }
                        TextButton(enabled = !transfers.busy, onClick = { transfers.exportFile("drive_${row.startTs}.csv") { DriveCsv.encode(dao.getBySession(row.sessionId), dao.getStations(row.sessionId), city) } }) { Text("导出 CSV") }
                        TextButton(enabled = !transfers.busy, onClick = { deleting = row }) { Text("删除") }
                    }
                }
            }
        }
        if (rows.isEmpty()) item { Text("暂无路测记录") }
    }
    preview?.let { points -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("打点预览 · ${points.size} 个") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            items(points.take(previewLimit)) { p ->
                val wgs = if (p.rawCoordType?.startsWith("WGS84", true) == true && p.rawLat != null && p.rawLng != null)
                    p.rawLat to p.rawLng else if (p.lat != null && p.lng != null) Gcj02.gcj02ToWgs84(p.lat, p.lng) else null to null
                Text("${stamp(p.ts)} · SIM${p.simSlot + 1} · ${p.positionSource}\n原始：${p.rawCoordType ?: "未保存"} ${p.rawLat ?: "—"}, ${p.rawLng ?: "—"}\nWGS84：${wgs.first ?: "—"}, ${wgs.second ?: "—"}\nGCJ02：${p.lat ?: "—"}, ${p.lng ?: "—"}", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (points.size > previewLimit) item { TextButton(onClick = { previewLimit += 100 }) { Text("加载更多") } }
        }
    }, confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }) }
    deleting?.let { row -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除路测记录？") }, text = { Text("${stamp(row.startTs)} · ${row.pointCount} 个打点") },
        confirmButton = { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { dao.deleteRecording(row.sessionId) }; deleting = null; revision++ } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
