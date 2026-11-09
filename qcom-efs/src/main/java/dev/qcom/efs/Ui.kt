package dev.qcom.efs

import android.Manifest
import android.os.Build
import android.provider.Settings
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import dev.qcom.efs.bulk.ui.BulkImportDialog

private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/** The helper answers inline reads only up to this size; larger files are save-only. */

internal fun time(v: Int): String =
    if (v <= 0) "-" else stamp.format(Date(v.toLong() * 1000L))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel, onBack: () -> Unit, onFeatures: () -> Unit) {
    val state by vm.state.collectAsState()

    var showNv by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var showMkdir by remember { mutableStateOf(false) }
    var chmodTarget by remember { mutableStateOf<Detail?>(null) }
    var confirmDelete by remember { mutableStateOf<Detail?>(null) }
    var importTarget by remember { mutableStateOf<String?>(null) }
    var importAsItem by remember { mutableStateOf<Boolean?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var ssrNeedsWrites by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> vm.completeExport(uri) }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            importTarget = null
        } else {
            val target = importTarget
            if (target != null) {
                importTarget = null
                vm.importInto(uri, target, importAsItem)
            } else {
                pendingImport = uri
            }
        }
    }

    val bulkImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.startBulkImport(uri) }

    val ctx = LocalContext.current
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.onStoragePermissionChanged(hasStorageAccess(ctx)) }
    val requestStoragePermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")))
            }.onFailure {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
    LaunchedEffect(state.pendingExport) {
        state.pendingExport?.let { exporter.launch(it.suggestedName) }
    }
    // A platform toast rather than a Scaffold snackbar on purpose: a snackbar
    // lives in the activity's own window, so an open dialog draws over it and
    // dims it with its scrim, and the keyboard covers it outright.  The system
    // draws a toast above both, which is the whole point of these messages.
    LaunchedEffect(state.toast) {
        state.toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.dismissToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state.searchActive) {
                        SearchField(query = state.searchQuery, onQuery = vm::setSearchQuery)
                    } else {
                        Column {
                            Text(stringResource(R.string.efs_browser_title), maxLines = 1)
                            state.info?.let {
                                Text(
                                    "后台服务 ${it.version} · 子系统 0x${it.subsys.toString(16)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (state.searchActive) {
                        IconButton(onClick = { vm.closeSearch() }) {
                            Icon(Icons.Filled.Close, "关闭搜索")
                        }
                    }
                },
                actions = {
                    // While the search field owns the bar, every other action
                    // hides; the Close button in the navigation slot exits it.
                    if (!state.searchActive) {
                        if (state.phase == Phase.READY) {
                            IconButton(onClick = { vm.toggleReadOnly() }) {
                                Icon(
                                    if (state.readOnly) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    contentDescription = "只读模式",
                                    tint = if (state.readOnly) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "刷新") }
                            IconButton(onClick = { vm.openSearch() }) { Icon(Icons.Filled.Search, "搜索") }
                            var sortMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "排序") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortKey.entries.forEach { key ->
                                    val active = state.sortKey == key
                                    DropdownMenuItem(
                                        text = {
                                            Text(sortLabel(key, if (active) state.sortDescending else key.defaultDescending))
                                        },
                                        leadingIcon = {
                                            if (active) Icon(Icons.Filled.Check, null) else null
                                        },
                                        onClick = { sortMenu = false; vm.chooseSort(key) },
                                    )
                                }
                            }
                        }
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "菜单") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (state.phase == Phase.READY) {
                                DropdownMenuItem(
                                    text = { Text("备份 EFS/NV 原始分区") },
                                    enabled = !state.busy,
                                    onClick = { menu = false; vm.requestRawBackup() },
                                )
                                DropdownMenuItem(
                                    text = { Text("导出当前目录（基带 TAR）") },
                                    leadingIcon = { Icon(Icons.Filled.Archive, null) },
                                    onClick = { menu = false; vm.requestImageBackup(state.path) },
                                )
                                DropdownMenuItem(
                                    text = { Text("导出当前目录文件（ZIP）") },
                                    leadingIcon = { Icon(Icons.Filled.Archive, null) },
                                    onClick = { menu = false; vm.requestTreeBackup(state.path) },
                                )
                                DropdownMenuItem(
                                    text = { Text("NV 项读写") },
                                    leadingIcon = { Icon(Icons.Filled.Memory, null) },
                                    onClick = { menu = false; showNv = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("批量导入") },
                                    leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                                    onClick = {
                                        menu = false
                                        bulkImporter.launch(arrayOf("text/plain", "application/json"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("基带功能与恢复") },
                                    leadingIcon = { Icon(Icons.Filled.Tune, null) },
                                    onClick = { menu = false; onFeatures() },
                                )
                                DropdownMenuItem(
                                    text = { Text("原始 DIAG 数据包") },
                                    leadingIcon = { Icon(Icons.Filled.Code, null) },
                                    onClick = { menu = false; showRaw = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("同步 EFS 日志") },
                                    leadingIcon = { Icon(Icons.Filled.Sync, null) },
                                    onClick = { menu = false; vm.sync() },
                                )
                                // Always tappable: under the read-only lock the
                                // helper refuses the SSR, so the tap offers to
                                // lift the lock instead of silently doing
                                // nothing.
                                DropdownMenuItem(
                                    text = { Text("重启基带") },
                                    leadingIcon = { Icon(Icons.Filled.RestartAlt, null) },
                                    onClick = {
                                        menu = false
                                        if (state.readOnly) {
                                            ssrNeedsWrites = true
                                        } else {
                                            vm.modemSsr()
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                            if (state.phase == Phase.READY) {
                                // Always offered: the app cannot reliably tell a
                                // dead session from a live one, and a restart of
                                // a healthy helper costs next to nothing.
                                DropdownMenuItem(
                                    text = { Text("重新连接") },
                                    leadingIcon = { Icon(Icons.Filled.Link, null) },
                                    onClick = { menu = false; vm.reconnect() },
                                )
                                DropdownMenuItem(
                                    text = { Text("断开连接") },
                                    leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, null) },
                                    onClick = { menu = false; vm.disconnect() },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.phase == Phase.READY && !state.readOnly) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton(onClick = { showMkdir = true }) {
                        Icon(Icons.Filled.CreateNewFolder, "新建目录")
                    }
                    FloatingActionButton(onClick = { importTarget = null; importer.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.Upload, "上传文件")
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!state.storageGranted) StoragePermissionScreen(onRequest = requestStoragePermission)
            else when (state.phase) {
                Phase.READY -> BrowserScreen(state, vm, onBack)
                else -> ConnectScreen(state, vm)
            }
            if (state.busy) {
                Column(Modifier.align(Alignment.TopCenter)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.busyLabel?.let {
                        Text(
                            it,
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    state.detail?.let { detail ->
        DetailSheet(
            detail = detail,
            maxInline = state.info?.maxInline ?: DEFAULT_MAX_INLINE,
            preview = state.preview,
            previewLoading = state.previewLoading,
            previewError = state.previewError,
            readOnly = state.readOnly,
            onDismiss = { vm.closeDetail() },
            onLoadContent = { vm.preview(detail.path) },
            onEdit = { vm.edit(detail) },
            onExport = { vm.requestExport(detail.path) },
            onReplace = {
                importTarget = detail.path
                importAsItem = detail.entry.isItem
                importer.launch(arrayOf("*/*"))
            },
            onChmod = { chmodTarget = detail },
            onDelete = { confirmDelete = detail },
        )
    }

    state.editor?.let { ed ->
        EditorDialog(ed, state.busy, onSave = { vm.saveEditor(it) }, onDismiss = { vm.closeEditor() })
    }

    if (showNv) NvDialog(state, vm) { showNv = false }
    if (showRaw) RawDialog(vm) { showRaw = false }

    state.update?.let { release ->
        UpdateDialog(
            release = release,
            onDownload = {
                vm.dismissUpdate()
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(release.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onSkip = { vm.skipUpdate() },
            onDismiss = { vm.dismissUpdate() },
        )
    }

    state.bulk?.let { bulk ->
        BulkImportDialog(
            state = bulk,
            readOnly = state.readOnly,
            busy = state.busy,
            ssrDone = state.ssrDone,
            onSpcUnlock = { spc -> vm.spcUnlock(spc) },
            onEnableWrites = { vm.toggleReadOnly() },
            onStart = { spc -> vm.runBulkImport(spc) },
            onSsr = { vm.modemSsr() },
            onDismiss = { vm.closeBulkImport() },
        )
    }

    if (state.backupRunning) BackupProgressDialog(state)

    if (showMkdir) {
        TextPromptDialog(
            title = "在 ${state.path} 中新建目录",
            label = "名称",
            initial = "",
            onConfirm = { showMkdir = false; if (it.isNotBlank()) vm.createDir(it.trim()) },
            onDismiss = { showMkdir = false },
        )
    }

    chmodTarget?.let { d ->
        TextPromptDialog(
            title = "${d.entry.name} 的权限",
            label = "权限值（八进制）",
            initial = (d.entry.mode and 0xFFF).toString(8),
            onConfirm = { text ->
                chmodTarget = null
                text.trim().toIntOrNull(8)?.let { vm.chmod(d.path, it) }
            },
            onDismiss = { chmodTarget = null },
        )
    }

    pendingImport?.let { uri ->
        ImportDialog(
            dir = state.path,
            initialName = remember(uri) { vm.displayName(uri) },
            onConfirm = { name, asItem ->
                pendingImport = null
                if (name.isNotBlank()) {
                    vm.importInto(uri, Paths.child(state.path, name.trim()), asItem)
                }
            },
            onDismiss = { pendingImport = null },
        )
    }

    if (ssrNeedsWrites) {
        AlertDialog(
            onDismissRequest = { ssrNeedsWrites = false },
            icon = { Icon(Icons.Filled.Lock, null) },
            title = { Text("尚未启用写入") },
            text = {
                Text(
                    "重启基带会改变基带状态，需要先启用写入。" +
                            "启用后，应用内其他修改操作也会同时解锁。" +
                            "修改 EFS 可能导致基带无法工作。是否启用写入并立即重启基带？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ssrNeedsWrites = false
                    vm.enableWritesAndModemSsr()
                }) { Text("启用写入并重启") }
            },
            dismissButton = {
                TextButton(onClick = { ssrNeedsWrites = false }) { Text("取消") }
            },
        )
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text("删除 ${d.entry.name}？") },
            text = {
                Text(
                    if (d.entry.isDir)
                        "将从基带中删除 ${d.path} 及其全部子目录和文件。" +
                                "此操作无法撤销，可能导致基带无法使用。"
                    else
                        "将从基带中删除 ${d.path}。校准及配置" +
                                "文件在没有备份的情况下无法恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; vm.delete(d) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

/**
 * The top-bar search field.  Transparent container and indicators so it
 * reads as part of the app bar; focused on entry so typing can start at once.
 */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        placeholder = { Text("搜索当前目录") },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = {
                    onQuery("")
                    focus.requestFocus()
                }) { Icon(Icons.Filled.Clear, "清空") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

private fun sortLabel(key: SortKey, descending: Boolean): String = when (key) {
    SortKey.NAME -> if (descending) "名称：降序" else "名称：升序"
    SortKey.SIZE -> if (descending) "大小：从大到小" else "大小：从小到大"
    SortKey.DATE -> if (descending) "时间：从新到旧" else "时间：从旧到新"
}

@Composable
private fun ConnectScreen(state: UiState, vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("基带 EFS 浏览器", style = MaterialTheme.typography.headlineSmall)
        if (!state.initialBackupComplete) Text("首次连接后将自动备份 EFS/NV 原始分区到 Download/EFS-BACKUP/，默认在全部校验通过前保持只读。")
        Text(
            "本应用通过具有 Root 权限的后台服务，使用 DIAG 访问高通基带的 EFS 文件系统。" +
                    "后台服务优先通过 QRTR 连接，失败时尝试 /dev/diag。需要 " +
                    "Root 授权；连接过程不会修改系统分区或 SELinux 设置。",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("连接失败", style = MaterialTheme.typography.titleMedium)
                    Text(state.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.verbose, onCheckedChange = { vm.setVerbose(it) })
            Text("记录后台服务详细日志")
        }


        Button(
            onClick = { vm.connect() },
            enabled = state.phase != Phase.CONNECTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.phase == Phase.CONNECTING) "正在连接…" else "授予 Root 权限并连接")
        }

        if (state.log.isNotEmpty()) {
            Text("后台服务输出", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    state.log.takeLast(40).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun BrowserScreen(state: UiState, vm: MainViewModel, onBack: () -> Unit) {
    var confirmExit by remember { mutableStateOf(false) }

    // The system back gesture closes an active search first, then walks up
    // the tree exactly like the arrow in the toolbar.  At the root there is
    // nowhere left to go, so it offers the exit -- which closes the session
    // rather than leaving the helper behind.
    BackHandler {
        when {
            state.busy || state.backupRunning -> Unit
            state.searchActive -> vm.closeSearch()
            state.path != "/" -> vm.up()
            else -> confirmExit = true
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出 EFS 浏览器？") },
            text = { Text("断开基带连接并返回网络助手。") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; vm.disconnectAndExit(onBack) }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("取消") }
            },
        )
    }

    // A new directory is shown from its first entry, not at whatever offset the
    // previous one happened to be scrolled to.  Re-reading the same path (the
    // refresh button, a delete) keeps the position, since the key is unchanged.
    val listState = rememberLazyListState()
    LaunchedEffect(state.path) { listState.scrollToItem(0) }

    // The visible list derives from the repository's listing: name filter,
    // then the chosen sort.  The repository's own dirs-first order stays the
    // data source; this only reorders (and hides) entries.
    val visible = remember(state.entries, state.searchQuery, state.sortKey, state.sortDescending) {
        state.entries.filterByName(state.searchQuery).sortedBy(state.sortKey, state.sortDescending)
    }

    // Typing shrinks the list and a new sort reshuffles it; either way the
    // answer the user is looking for is at the top, so start there rather
    // than leaving them wherever the old order had them scrolled to.
    LaunchedEffect(state.searchQuery, state.sortKey, state.sortDescending) {
        listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        if (!state.initialBackupComplete || !state.cloudBackupComplete) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (state.backupBypassed) stringResource(R.string.efs_backup_bypassed) else "本地与云端备份尚未全部通过校验，写入已锁定", color = MaterialTheme.colorScheme.error)
                state.initialBackupError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                BackupChainStatus(state, vm)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Paths.crumbs(state.path).forEachIndexed { i, (label, target) ->
                if (i > 0) Text(" / ", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (label == "/") "efs" else label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (target == state.path) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { vm.open(target) }
                        .padding(vertical = 4.dp),
                )
            }
        }
        HorizontalDivider()

        if (state.entries.isEmpty() && !state.busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("目录为空", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        // A filter can hide every entry of a non-empty directory -- that is a
        // different situation from the modem reporting an empty directory,
        // so it gets its own message.
        if (visible.isEmpty() && !state.busy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有找到与“${state.searchQuery}”匹配的文件", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(visible, key = { it.name }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(modeOctal(entry.mode))
                                if (!entry.isDir) append(" · ${humanSize(entry.size.toLong())}")
                                if (entry.mtime > 0) append(" · ${time(entry.mtime)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = {
                        Icon(
                            when {
                                entry.isDir -> Icons.Filled.Folder
                                entry.isLink -> Icons.Filled.Link
                                entry.isItem -> Icons.Filled.Memory
                                else -> Icons.Filled.Description
                            },
                            contentDescription = efsTypeLabel(entry.type),
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            if (entry.isDir) vm.openDetailForDir(entry) else vm.onEntryClicked(entry)
                        }) { Icon(Icons.Filled.Info, "详情") }
                    },
                    modifier = Modifier.clickable { vm.onEntryClicked(entry) },
                )
                HorizontalDivider(Modifier.padding(start = 56.dp))
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}
