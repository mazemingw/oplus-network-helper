package dev.qcom.efs

import org.json.JSONObject

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.qcom.efs.bulk.BulkCommand
import dev.qcom.efs.bulk.BulkOp
import dev.qcom.efs.bulk.NvImportParseException
import dev.qcom.efs.bulk.NvImportParser
import dev.qcom.efs.features.EfsFeatureAccess
import dev.qcom.efs.features.FeatureRestorer
import dev.qcom.efs.features.FeatureChecker
import dev.qcom.efs.features.FeatureDef
import dev.qcom.efs.features.FeatureStatus
import dev.qcom.efs.features.ALL_FEATURES
import dev.qcom.efs.update.Release
import dev.qcom.efs.update.UpdateChecker
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { DISCONNECTED, CONNECTING, READY, FAILED }

data class Detail(val entry: EfsEntry, val path: String, val stat: EfsStat? = null)

data class PreviewData(val bytes: ByteArray, val asText: Boolean) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** A file staged in the cache, waiting for the user to pick a destination. */
data class PendingExport(val file: File, val suggestedName: String, val mime: String)

/**
 * A file open in the editor.  [original] is what was read from the modem: the
 * editor compares against it to know whether anything was actually changed, and
 * [mode] and [isItem] are carried along so that saving writes the file back as
 * the same kind of object it already was.
 */
data class EditorData(
    val path: String,
    val original: ByteArray,
    val mode: Int,
    val isItem: Boolean,
    val startAsText: Boolean,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Anything larger is better edited on a computer than in a text field. */
private const val MAX_EDIT = 64 * 1024

/** Preference key holding the one release the user chose not to be reminded about. */
private const val KEY_SKIPPED_VERSION = "skipped_version"

/** SIM slot the Disable-features dialog opens on. */
private const val DEFAULT_FEATURE_SLOT = 0

/** Preference key recording that the feature-disable warning is off for good. */
private const val KEY_DISABLE_WARNING_SUPPRESSED = "disable_warning_suppressed"

/** One executed bulk command, shown in the dialog's result list. */
data class BulkResult(
    val op: BulkOp,
    val path: String,
    val simTag: String,
    val ok: Boolean,
    val error: String? = null,
)

sealed interface BulkState {
    /** File picked and parsed; the import has not started yet. */
    data class Preview(
        val fileName: String,
        val commands: List<BulkCommand>,
        val error: String? = null,
        /** Feedback that would otherwise go to the snackbar behind the dialog. */
        val note: String? = null,
    ) : BulkState

    /** Sequential execution in progress; [results] has [done] entries. */
    data class Running(
        val fileName: String,
        val commands: List<BulkCommand>,
        val done: Int,
        val results: List<BulkResult>,
    ) : BulkState

    /** Finished. [summary] is a non-fatal note (e.g. a failed journal flush). */
    data class Done(
        val results: List<BulkResult>,
        val summary: String? = null,
        /** Feedback that would otherwise go to the snackbar behind the dialog. */
        val note: String? = null,
    ) : BulkState
}

/** State of the Disable-features dialog. */
sealed interface FeaturesState {
    data class Checking(val simSlot: Int) : FeaturesState
    data class Ready(
        val simSlot: Int,
        val statuses: Map<String, FeatureStatus>,
        /** True until the user ticks "不再提醒" (one-way, persisted). */
        val warnBeforeDisable: Boolean,
        val restorable: Set<String> = emptySet(),
        val note: String? = null,
        val originals: Map<String, List<dev.qcom.efs.features.OriginalItem>> = emptyMap(),
    ) : FeaturesState
}

data class UiState(
    val phase: Phase = Phase.DISCONNECTED,
    val error: String? = null,
    val info: DaemonInfo? = null,
    val path: String = "/",
    val entries: List<EfsEntry> = emptyList(),
    /** True while the top bar shows the current-directory search field. */
    val searchActive: Boolean = false,
    /** Name filter for the current directory; blank means no filtering. */
    val searchQuery: String = "",
    /** Sort of the browser list; chosen key and direction persist across runs. */
    val sortKey: SortKey = SortKey.NAME,
    /** False: Name A-Z, Size smallest, Date oldest.  True flips the order. */
    val sortDescending: Boolean = false,
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val readOnly: Boolean = true,
    val verbose: Boolean = true,
    val storageGranted: Boolean = false,
    val initialBackupComplete: Boolean = false,
    val cloudBackupComplete: Boolean = false,
    val cloudBackupMessage: String? = null,
    val recoveryPackagePath: String? = null,
    val initialBackupError: String? = null,
    val backupPartitionNames: List<String> = REQUIRED_EFS_PARTITIONS,
    val backupBypassed: Boolean = false,
    val backupBypassDialog: Boolean = false,
    val backupBypassRemaining: Int = 5,
    val backupRunning: Boolean = false,
    val backupIsRaw: Boolean = false,
    val backupMessage: String? = null,
    val lastBackupPath: String? = null,
    val localEnforce: Int? = null,
    val log: List<String> = emptyList(),
    val toast: String? = null,
    val detail: Detail? = null,
    val preview: PreviewData? = null,
    /** True while the detail sheet's inline content is being read. */
    val previewLoading: Boolean = false,
    /** Inline-content read failure, shown inside the sheet rather than the snackbar. */
    val previewError: String? = null,
    val editor: EditorData? = null,
    val pendingExport: PendingExport? = null,
    val nv: NvResult? = null,
    val nvError: String? = null,
    /** Non-null while the bulk-import dialog is open. */
    val bulk: BulkState? = null,
    val features: FeaturesState? = null,
    /** Non-null while the "new version available" dialog is open. */
    val update: Release? = null,
    /**
     * True once a modem restart came back with a live session, so the SSR
     * button in the bulk-import and features dialogs can say "Done!".  It
     * belongs to the changes made before that restart: opening either dialog
     * or writing a new feature clears it again.
     */
    val ssrDone: Boolean = false,
)

/**
 * Closes the detail sheet (or swaps in [detail]) and drops its inline content:
 * the sheet and the content loaded for it always reset together.
 */
private fun UiState.withoutSheet(detail: Detail? = null) = copy(
    detail = detail,
    preview = null,
    previewLoading = false,
    previewError = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = EfsRepository(app)
    private val backupStorage = BackupStorage(app)
    private val cloudBackup = EfsCloudBackup(app, backupStorage) {
        if (!repo.connected) null else repo.nvRead(550).takeIf { it.status == 0 }?.let { decodeNvImei(it.hex) }
    }
    private val logger = EfsLogger(app, backupStorage.directory)
    private val featureBackups = backupStorage.featureStore(logger::record)
    private val featureAccess = EfsFeatureAccess(repo)
    private val featureRestorer = FeatureRestorer(featureAccess, { path ->
        try { repo.unlink(path) } catch (t: EfsException) { if (t.efsErrno != 2) throw t }
    }, { repo.sync() })
    private var moduleEntryJob: Job? = null
    private var backupLogJob: Job? = null
    private val backupBypassGate = BackupBypassGate(android.os.SystemClock::elapsedRealtime)
    private var backupBypassCountdown: Job? = null

    private val _state = MutableStateFlow(UiState(storageGranted = hasStorageAccess(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("updates", Context.MODE_PRIVATE)
    }

    /** The installed versionName, read from the package rather than BuildConfig. */
    private val installedVersion: String by lazy {
        val app = getApplication<Application>()
        runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "0"
    }

    // Must stay below every property it touches: viewModelScope dispatches on
    // Main.immediate, so the check runs synchronously during construction and
    // a lazy declared further down would still be null.
    init {
        val saved = prefs.getString("sort_key", null)
        val key = SortKey.entries.firstOrNull { it.name == saved }
        _state.update {
            it.copy(
                sortKey = key ?: SortKey.NAME,
                // The pair is always written together, so a stray sort_desc is
                // noise; an unknown key resets the direction along with it.
                sortDescending = if (key != null) prefs.getBoolean("sort_desc", false) else false,
            )
        }
        logger.record("应用启动：自动日志已启用，备份目录 ${backupStorage.directory}")
        // Diagnostics may reuse the existing read-only IMEI/NV fallback, never open a new session.
        val diagnosticsOwner = java.lang.ref.WeakReference(this)
        DiagnosticEvents.imeiReader = {
            val owner = diagnosticsOwner.get()
            if (owner?.repo?.connected == true) runCatching { owner.cloudBackup.identity().imei }.getOrNull() else null
        }
        // Embedded module follows Network Helper releases.
    }

    // ---- plumbing ------------------------------------------------------

    private fun work(label: String? = null, onSuccess: (() -> Unit)? = null, body: suspend () -> Unit) {
        if (_state.value.backupRunning || _state.value.busy) {
            _state.update { it.copy(toast = "操作正在进行，请等待完成") }
            return
        }
        viewModelScope.launch {
            var completed = false
            logger.record("操作开始：${label ?: "基带操作"}")
            _state.update { it.copy(busy = true, busyLabel = label) }
            try {
                body()
                completed = true
                logger.record("操作完成：${label ?: "基带操作"}")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logger.record("操作失败：${label ?: "基带操作"}：${t.message}")
                _state.update { it.copy(toast = describe(t)) }
            } finally {
                withContext(Dispatchers.IO) { logger.captureNative() }
                _state.update { it.copy(busy = false, busyLabel = null) }
            }
            if (completed) onSuccess?.invoke()
        }
    }

    private fun describe(t: Throwable): String {
        val base = chineseMessage(t.message ?: t.javaClass.simpleName)
        val errno = (t as? EfsException)?.efsErrno
        return if (errno != null && errno != 0) "$base (${errnoName(errno)})" else base
    }

    fun dismissToast() = _state.update { it.copy(toast = null) }
    fun configureCloud(endpoint: String) { cloudBackup.endpoint = endpoint }
    fun enterModule() {
        onStoragePermissionChanged(hasStorageAccess(getApplication()))
    }
    fun setVerbose(on: Boolean) = _state.update { it.copy(verbose = on) }

    /** The picker's own name for a chosen file, for pre-filling the import dialog. */
    fun displayName(uri: Uri): String = repo.displayName(uri)

    // ---- session -------------------------------------------------------

    fun connect() {
        if (_state.value.phase == Phase.CONNECTING || _state.value.busy || _state.value.backupRunning) return
        _state.update { it.copy(phase = Phase.CONNECTING) }
        viewModelScope.launch {
            if (startSession()) {
                ensureFirstBackup()
                _state.update { it.copy(phase = Phase.READY) }
                open("/")
            }
        }
    }

    /**
     * Starts a fresh helper and session from inside the browser, and puts the
     * user back in the folder they were in -- or on the root, when that folder
     * is gone or unreadable.  Always offered rather than only after a detected
     * failure: a dead helper is not the only way to end up with a useless
     * session (a modem restart sent as a raw packet leaves the helper talking
     * to the modem that went away), and restarting a healthy helper costs two
     * seconds and puts the read-only lock back on, nothing more.
     */
    fun reconnect() {
        if (_state.value.phase == Phase.CONNECTING || _state.value.busy || _state.value.backupRunning) return
        val savedPath = _state.value.path
        val featureSlot = (_state.value.features as? FeaturesState.Ready)?.simSlot
        closeFeatures()
        _state.update { it.copy(phase = Phase.CONNECTING) }
        viewModelScope.launch {
            if (!startSession()) return@launch
            ensureFirstBackup()
            _state.update { it.copy(phase = Phase.READY) }
            if (featureSlot != null) checkFeatures(featureSlot)
            // Inline rather than open(): a failed read here must fall back to
            // the root instead of leaving an error toast over an empty list.
            val (path, entries) = runCatching { savedPath to repo.list(savedPath) }
                .getOrElse { "/" to runCatching { repo.list("/") }.getOrDefault(emptyList()) }
            _state.update {
                val s = it.copy(path = path, entries = entries).withoutSheet()
                if (path != savedPath) s.copy(searchActive = false, searchQuery = "") else s
            }
        }
    }

    /** Starts the helper and opens the modem session; true when it worked. */
    private suspend fun startSession(): Boolean {
        if (!withContext(Dispatchers.IO) { QualcommPlatform.isQualcomm() }) {
            _state.update { it.copy(phase = Phase.FAILED, error = "仅支持已确认的高通平台，EFS 服务未启动") }
            return false
        }
        if (!hasStorageAccess(getApplication())) {
            _state.update { it.copy(storageGranted = false, toast = "请先授予所有文件访问权限") }
            return false
        }
        _state.update { it.copy(phase = Phase.CONNECTING, error = null) }
        logger.record("开始连接基带，详细日志=${_state.value.verbose}")
        return try {
            logger.beginNativeSession()
            val (info, log) = repo.connect(_state.value.verbose)
            _state.update {
                it.copy(
                    phase = Phase.CONNECTING,
                    info = info,
                    readOnly = info.readOnly,
                    localEnforce = repo.enforceState(),
                    log = log,
                )
            }
            logger.record("基带连接成功：${info.transport}，后台版本 ${info.version}")
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.record("基带连接失败：${t.message}")
            logger.captureNative()
            _state.update {
                it.copy(
                    phase = Phase.FAILED,
                    error = describe(t),
                    localEnforce = repo.enforceState(),
                    log = it.log + repo.lastStartLog + repo.daemonLog(),
                )
            }
            false
        }
    }

    fun disconnect() = work("正在断开连接") {
        closeFeatures()
        repo.disconnect()
        _state.update {
            UiState(
                verbose = it.verbose,
                storageGranted = it.storageGranted,
                initialBackupComplete = it.initialBackupComplete,
                cloudBackupComplete = it.cloudBackupComplete,
                cloudBackupMessage = it.cloudBackupMessage,
                initialBackupError = it.initialBackupError,
                backupPartitionNames = it.backupPartitionNames,
                backupBypassed = it.backupBypassed,
                lastBackupPath = it.lastBackupPath,
                localEnforce = it.localEnforce,
                log = it.log,
                sortKey = it.sortKey,
                sortDescending = it.sortDescending,
            )
        }
    }

    /**
     * Leaving by the back gesture: close the session first, then let the
     * activity finish.  Without this the helper would keep running as root
     * after the app is gone, until the next launch kills it.
     */
    fun disconnectAndExit(onExit: () -> Unit) = work("正在断开连接", onSuccess = onExit) {
        closeFeatures()
        repo.disconnect()
        _state.update {
            UiState(
                verbose = it.verbose,
                storageGranted = it.storageGranted,
                initialBackupComplete = it.initialBackupComplete,
                cloudBackupComplete = it.cloudBackupComplete,
                cloudBackupMessage = it.cloudBackupMessage,
                initialBackupError = it.initialBackupError,
                backupPartitionNames = it.backupPartitionNames,
                backupBypassed = it.backupBypassed,
                lastBackupPath = it.lastBackupPath,
                localEnforce = it.localEnforce,
                log = it.log,
                sortKey = it.sortKey,
                sortDescending = it.sortDescending,
            )
        }
    }

    fun refreshLog() {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) { logger.captureNative(); logger.tail() + repo.daemonLog() }
            _state.update { it.copy(log = lines) }
        }
    }

    fun toggleReadOnly() = work {
        val next = !_state.value.readOnly
        if (!next) requireInitialBackup()
        repo.setReadOnly(next)
        _state.update {
            it.copy(
                readOnly = next,
                toast = if (next) "已恢复只读模式"
                else "已启用写入，修改 EFS 可能导致基带无法工作",
            )
        }
    }

    // ---- browsing ------------------------------------------------------

    fun open(path: String) = work("正在读取 $path") {
        val entries = repo.list(path)
        // A move to a different directory ends the search (a re-read of the
        // same path -- refresh, post-mutation re-lists -- keeps it).
        val moved = path != _state.value.path
        _state.update {
            val s = it.copy(path = path, entries = entries).withoutSheet()
            if (moved) s.copy(searchActive = false, searchQuery = "") else s
        }
    }

    fun refresh() = open(_state.value.path)

    fun up() {
        val p = _state.value.path
        if (p != "/") open(Paths.parent(p))
    }

    // ---- search and sorting --------------------------------------------

    fun openSearch() = _state.update { it.copy(searchActive = true) }

    fun closeSearch() = _state.update { it.copy(searchActive = false, searchQuery = "") }

    fun setSearchQuery(query: String) = _state.update { it.copy(searchQuery = query) }

    /**
     * First tap picks a key with its useful default direction (Name A-Z, Size
     * largest first, Date newest first); tapping the active key again flips it.
     */
    fun chooseSort(key: SortKey) {
        val (next, desc) = _state.value.let { s ->
            if (s.sortKey == key) key to !s.sortDescending
            else key to key.defaultDescending
        }
        prefs.edit().putString("sort_key", next.name).putBoolean("sort_desc", desc).apply()
        _state.update { it.copy(sortKey = next, sortDescending = desc) }
    }

    fun onEntryClicked(entry: EfsEntry) {
        val full = Paths.child(_state.value.path, entry.name)
        if (entry.isDir) {
            open(full)
        } else {
            work("正在读取文件信息") {
                val st = runCatching { repo.stat(full) }.getOrNull()
                _state.update { it.withoutSheet(Detail(entry, full, st)) }
            }
        }
    }

    fun openDetailForDir(entry: EfsEntry) {
        val full = Paths.child(_state.value.path, entry.name)
        work("正在读取文件信息") {
            val st = runCatching { repo.stat(full) }.getOrNull()
            _state.update { it.withoutSheet(Detail(entry, full, st)) }
        }
    }

    fun closeDetail() = _state.update { it.withoutSheet() }
    fun closeEditor() = _state.update { it.copy(editor = null) }

    fun preview(path: String) = work("正在读取 $path") {
        _state.update { it.copy(preview = null, previewError = null, previewLoading = true) }
        try {
            val bytes = repo.readInline(path)
            _state.update { s ->
                // A sheet that closed or moved on mid-read must not show the
                // stale bytes; the reply is dropped unless it still matches.
                if (s.detail?.path != path) s
                else s.copy(preview = PreviewData(bytes, looksLikeText(bytes)), previewLoading = false)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.update { s ->
                if (s.detail?.path != path) s
                else s.copy(previewError = describe(t), previewLoading = false)
            }
        }
    }

    // ---- editing -------------------------------------------------------

    fun edit(detail: Detail) = work("正在读取 ${detail.path}") {
        val bytes = repo.readInline(detail.path)
        if (bytes.size > MAX_EDIT) {
            _state.update {
                it.copy(toast = "文件大小为 ${humanSize(bytes.size.toLong())}，超过编辑器限制。" +
                        "请先导出，在电脑上修改后通过“替换”写回")
            }
            return@work
        }
        val mode = ((detail.stat?.mode ?: detail.entry.mode) and 0xFFF).let { if (it == 0) 420 else it }
        _state.update {
            it.copy(
                editor = EditorData(
                    path = detail.path,
                    original = bytes,
                    mode = mode,
                    isItem = detail.entry.isItem,
                    // An item file is a blob with a layout the modem cares
                    // about, so it opens in hex even when it reads as text.
                    startAsText = !detail.entry.isItem && looksLikeText(bytes),
                ),
            ).withoutSheet()
        }
    }

    /**
     * Commits the EFS journal after a change.  Until this runs the change only
     * exists in the modem's journal, and a modem restart rolls it straight
     * back — so a save that is not committed is not really saved.  Every
     * mutation needs it, not just file writes: a deleted file comes back and a
     * new directory disappears just the same.  Returns text to append to the
     * toast when the commit did not go through, since the change itself did
     * happen and saying nothing would be a lie.
     */
    private suspend fun commitChange(): String =
        runCatching { repo.sync() }.fold(
            { "" },
            { "（EFS 日志同步失败，修改可能在重启基带后丢失）" },
        )

    fun saveEditor(bytes: ByteArray) {
        val ed = _state.value.editor ?: return
        work("正在写入 ${ed.path}") {
            requireWriteAccess()
            repo.writeFile(ed.path, bytes, ed.mode, ed.isItem)
            val warn = commitChange()
            val entries = repo.list(_state.value.path)
            _state.update {
                it.copy(
                    editor = null,
                    entries = entries,
                    toast = "已向 ${ed.path.substringAfterLast('/')} 写入 ${bytes.size} 字节$warn",
                )
            }
        }
    }

    // ---- transfers -----------------------------------------------------

    fun requestExport(path: String) = work("正在导出 $path") {
        val file = repo.pullToCache(path)
        _state.update {
            it.copy(pendingExport = PendingExport(file, path.trimStart('/').replace('/', '_'), "application/octet-stream"))
        }
    }

    fun requestImageBackup(path: String) = startBackup(path, tar = true)

    fun requestTreeBackup(path: String) = startBackup(path, tar = false)

    fun retryInitialBackup() = requestRawBackup()

    fun requestRawBackup() {
        if (_state.value.busy || _state.value.backupRunning || featuresJob?.isActive == true || bulkRun?.isActive == true) return
        viewModelScope.launch { performRawBackup() }
    }

    private fun startBackup(path: String, tar: Boolean) {
        if (_state.value.busy || _state.value.backupRunning || featuresJob?.isActive == true || bulkRun?.isActive == true) return
        viewModelScope.launch { performBackup(path, tar) }
    }

    private suspend fun ensureFirstBackup() {
        if (_state.value.backupBypassed) return
        val complete = try { withContext(Dispatchers.IO) {
            if (!backupStorage.hasInitialBackup()) false else {
                val source = SuPartitionSource().also { it.checkRoot() }
                val plan = discoverPartitionPlan(source)
                _state.update { it.copy(backupPartitionNames = plan.partitions.map { part -> part.name }) }
                val coverage = backupStorage.recordedCoverage() ?: return@withContext false
                val manifest = JSONObject(coverage.manifest.readText())
                manifest.optString("model") == android.os.Build.MODEL && manifest.optString("firmware") == android.os.Build.FINGERPRINT &&
                    rawBackupCoversPlan(manifest, plan)
            }
        } } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (repo.connected) runCatching { repo.setReadOnly(true) }
            _state.update { it.copy(initialBackupComplete = false, cloudBackupComplete = false, readOnly = true,
                initialBackupError = describe(t), toast = "无法验证分区覆盖范围：${describe(t)}") }
            return
        }
        _state.update { it.copy(initialBackupComplete = complete) }
        if (!complete) { _state.update { it.copy(cloudBackupComplete = false) }; performRawBackup() }
        else if (!_state.value.cloudBackupComplete) performCloudBackup()
    }

    fun retryCloudBackup() {
        if (_state.value.busy || _state.value.backupRunning) return
        viewModelScope.launch { performCloudBackup() }
    }

    private suspend fun performCloudBackup() {
        if (_state.value.backupRunning) return
        _state.update { it.copy(backupRunning = true, backupIsRaw = true, busy = true, cloudBackupComplete = false) }
        startBackupLog()
        try {
            check(withContext(Dispatchers.IO) { backupStorage.hasInitialBackup() }) { "请先完成本地分区备份" }
            if (repo.connected) repo.setReadOnly(true)
            _state.update { it.copy(readOnly = true) }
            val baseline = withContext(Dispatchers.IO) { backupStorage.recordedBaseline() } ?: error("没有本地基线清单")
            cloudBackup.ensure(baseline, ::cloudProgress)
            val coverage = withContext(Dispatchers.IO) { backupStorage.recordedCoverage() } ?: error("没有分区覆盖清单")
            if (coverage.manifest != baseline.manifest) cloudBackup.ensure(coverage, ::cloudProgress)
            _state.update { it.copy(initialBackupComplete = true, cloudBackupComplete = true,
                cloudBackupMessage = "云端完整校验成功", initialBackupError = null, backupBypassed = false) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            cloudProgress("云备份未完成：${describe(t)}；写入仍锁定")
            _state.update { it.copy(cloudBackupComplete = false, initialBackupError = describe(t), toast = describe(t)) }
        } finally { finishBackup() }
    }

    private fun cloudProgress(message: String) {
        logger.record(message)
        _state.update { it.copy(cloudBackupMessage = message, backupMessage = message) }
    }

    private fun startBackupLog() {
        backupLogJob = viewModelScope.launch {
            while (true) {
                val lines = withContext(Dispatchers.IO) { logger.captureNative(); logger.tail() + repo.daemonLog() }
                _state.update { it.copy(log = lines) }
                delay(1000)
            }
        }
    }

    private suspend fun finishBackup() {
        backupLogJob?.cancel(); backupLogJob = null
        withContext(Dispatchers.IO) { logger.captureNative() }
        _state.update { it.copy(backupRunning = false, busy = false, backupMessage = null) }
        refreshLog()
    }

    private suspend fun performRawBackup() {
        if (_state.value.backupRunning) return
        if (!withContext(Dispatchers.IO) { QualcommPlatform.isQualcomm() }) {
            _state.update { it.copy(toast = "仅高通平台允许 EFS 备份") }; return
        }
        _state.update { it.copy(backupRunning = true, backupIsRaw = true, busy = true,
            backupMessage = "正在准备 EFS/NV 原始分区备份…", initialBackupError = null, backupBypassed = false) }
        logger.record("原始分区备份开始；只读采集，全部校验通过后才满足首次备份门槛")
        startBackupLog()
        try {
            check(hasStorageAccess(getApplication())) { "请先授予所有文件访问权限" }
            check(repo.connected) { "请先连接基带" }
            // No app writes can occur during acquisition; also leave the helper read-only afterward.
            repo.setReadOnly(true)
            _state.update { it.copy(readOnly = true) }
            val backup = withContext(Dispatchers.IO) {
                val raw = RawPartitionBackup().capture(backupStorage.directory, backupStorage.deviceId,
                    android.os.Build.MODEL, android.os.Build.FINGERPRINT,
                    onDiscovered = { names -> _state.update { it.copy(backupPartitionNames = names) } }) { message ->
                    logger.record(message)
                    _state.update { it.copy(backupMessage = message) }
                }
                backupStorage.acceptRawBaseline(raw)
                raw
            }
            _state.update { it.copy(initialBackupComplete = true, cloudBackupComplete = false) }
            val baseline = withContext(Dispatchers.IO) { backupStorage.recordedBaseline() }
            check(baseline != null) { "首次分区基线清单不可用" }
            cloudBackup.ensure(baseline, ::cloudProgress)
            if (baseline.manifest != backup.manifest) cloudBackup.ensure(backup, ::cloudProgress)
            check(withContext(Dispatchers.IO) { cloudBackup.hasReceipt(baseline) }) { "首次分区基线尚未完成云备份" }
            val message = "EFS/NV 分区备份已保存并通过校验：${backup.directory.absolutePath}"
            logger.record(message)
            _state.update { it.copy(initialBackupComplete = true, cloudBackupComplete = true, initialBackupError = null,
                lastBackupPath = backup.directory.absolutePath, toast = message) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.record("原始分区备份失败：${t.message}")
            val stillValid = withContext(Dispatchers.IO) { backupStorage.hasInitialBackup() }
            _state.update { it.copy(initialBackupComplete = stillValid,
                cloudBackupComplete = false, cloudBackupMessage = if (stillValid) describe(t) else it.cloudBackupMessage,
                initialBackupError = describe(t), toast = "备份链未完成：${describe(t)}") }
        } finally {
            finishBackup()
        }
    }

    private suspend fun performBackup(path: String, tar: Boolean) {
        if (_state.value.backupRunning) return
        _state.update { it.copy(backupRunning = true, backupIsRaw = false, busy = true, backupMessage = "正在导出文件 $path") }
        logger.record("文件导出开始：path=$path，requested=${if (tar) "TAR" else "ZIP"}；不影响分区备份门槛")
        startBackupLog()
        try {
            check(hasStorageAccess(getApplication())) { "请先授予所有文件访问权限" }
            check(repo.connected) { "请先连接基带" }
            val saved = withContext(Dispatchers.IO) {
                var tarFile: File? = null
                val tarKey = "tar_unsupported_${android.os.Build.FINGERPRINT.hashCode()}"
                if (tar && !prefs.getBoolean(tarKey, false)) {
                    try {
                        logger.record("请求 FS_IMAGE TAR 备份：$path")
                        tarFile = repo.imageBackup(path)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        logger.record("TAR 失败（原始错误）：${t.message}")
                        if (!isUnsupportedTar(t.message.orEmpty())) throw t
                        prefs.edit().putBoolean(tarKey, true).apply()
                        logger.record("基带不支持 FS_IMAGE，自动切换为逐文件 ZIP")
                        _state.update { it.copy(backupMessage = "基带不支持 TAR（bad command），正在改用 ZIP 备份…") }
                    }
                } else if (tar) logger.record("已知此基带不支持 TAR，直接使用 ZIP")
                if (tarFile != null) backupStorage.saveTar(tarFile!!)
                else {
                    val (zip, summary) = repo.treeBackup(path, maxFile = 0)
                    summary.errorList.forEach { logger.record("备份失败条目：$it") }
                    logger.record("ZIP 汇总：文件=${summary.files}，目录=${summary.dirs}，链接=${summary.links}，字节=${summary.bytes}，失败=${summary.errors}")
                    _state.update { it.copy(backupMessage = "正在校验压缩包并保存到 Download/EFS-BACKUP/…") }
                    backupStorage.saveZip(zip, path, summary)
                }
            }
            val message = if (saved.complete) "备份已保存：${saved.file.absolutePath}"
                else "已保存部分文件备份，读取失败 ${saved.summary?.errors ?: 0} 项，请查看 EFS-BACKUP/logs；分区备份状态不受影响"
            logger.record(message)
            _state.update {
                it.copy(lastBackupPath = saved.file.absolutePath, toast = message)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.record("备份失败：${t.message}")
            val message = describe(t)
            _state.update { it.copy(toast = "文件导出失败：$message") }
        } finally {
            finishBackup()
        }
    }

    fun onStoragePermissionChanged(granted: Boolean) {
        _state.update { it.copy(storageGranted = granted) }
        if (!granted) {
            _state.update { it.copy(readOnly = true) }
            viewModelScope.launch { if (repo.connected) runCatching { repo.setReadOnly(true) } }
            return
        }
        if (moduleEntryJob?.isActive == true) return
        moduleEntryJob = viewModelScope.launch {
            val complete = withContext(Dispatchers.IO) { backupStorage.hasInitialBackup() }
            _state.update { it.copy(initialBackupComplete = complete, readOnly = if (complete) it.readOnly else true) }
            val state = _state.value
            when (moduleEntryAction(state.phase, state.busy, state.backupRunning, repo.connected)) {
                ModuleEntryAction.CONNECT -> connect()
                ModuleEntryAction.RECONNECT -> reconnect()
                ModuleEntryAction.VERIFY -> ensureFirstBackup()
                ModuleEntryAction.WAIT -> Unit
            }
        }
    }

    private fun requireInitialBackup() {
        check(hasStorageAccess(getApplication())) { "请先授予所有文件访问权限" }
        check(!_state.value.backupRunning) { "备份进行中，暂不可修改" }
        if (_state.value.backupBypassed) return
        check(_state.value.initialBackupComplete && !_state.value.backupRunning) { "首次 EFS/NV 分区备份尚未通过校验，写入保持锁定，请先备份" }
        check(_state.value.cloudBackupComplete) { "完整云备份尚未通过校验，写入保持锁定" }
    }

    fun requestBackupBypass() {
        if (!_state.value.canOfferBackupBypass) return
        backupBypassCountdown?.cancel()
        backupBypassGate.begin()
        _state.update { it.copy(backupBypassDialog = true, backupBypassRemaining = 5) }
        backupBypassCountdown = viewModelScope.launch {
            while (_state.value.backupBypassDialog) {
                val remaining = backupBypassGate.remainingSeconds()
                _state.update { it.copy(backupBypassRemaining = remaining) }
                if (remaining == 0) break
                delay(200)
            }
        }
    }

    fun cancelBackupBypass() {
        backupBypassCountdown?.cancel(); backupBypassGate.cancel()
        _state.update { it.copy(backupBypassDialog = false, backupBypassRemaining = 5) }
    }

    fun confirmBackupBypass() {
        if (!_state.value.backupBypassDialog || !_state.value.canOfferBackupBypass || !backupBypassGate.confirm()) return
        backupBypassCountdown?.cancel()
        logger.record("危险操作：用户在备份失败后确认跳过完整备份门槛；本地与云端未被标记为成功，仅本次应用会话允许手动启用修改")
        _state.update { it.copy(backupBypassed = true, backupBypassDialog = false,
            toast = "已强制跳过备份，请自行承担风险；仍需手动允许修改") }
    }

    fun exportRecoveryPackage() {
        val file = _state.value.recoveryPackagePath?.let(::File)?.takeIf { it.isFile }
        if (file == null) _state.update { it.copy(toast = "恢复包已移除，请重新生成") }
        else _state.update { it.copy(pendingExport = PendingExport(file, file.name, "application/zip")) }
    }

    fun prepareFullRecovery(manualArchive: Uri? = null) {
        if (_state.value.busy || _state.value.backupRunning) return
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { QualcommPlatform.isQualcomm() }) {
                _state.update { it.copy(toast = "非高通或未识别平台，已禁止 EFS 恢复") }; return@launch
            }
            _state.update { it.copy(busy = true, backupRunning = true, backupIsRaw = true,
                recoveryPackagePath = null, backupMessage = "正在验证恢复副本…") }
            startBackupLog()
            try {
                if (repo.connected) repo.setReadOnly(true)
                _state.update { it.copy(readOnly = true) }
                val (backup, identity) = if (manualArchive == null) cloudBackup.resolveForRecovery(::cloudProgress)
                else {
                    val device = cloudBackup.identity()
                    val selected = withContext(Dispatchers.IO) {
                        val file = File(getApplication<Application>().cacheDir, "manual-efs-${java.util.UUID.randomUUID()}.zip")
                        getApplication<Application>().contentResolver.openInputStream(manualArchive).use { input ->
                            check(input != null) { "无法读取选中的备份包" }
                            file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024); var total = 0L
                                while (true) { val n = input.read(buffer); if (n < 0) break
                                    total += n; check(total <= 2L * 1024 * 1024 * 1024) { "备份包过大" }; output.write(buffer, 0, n) }
                            }
                        }
                        BackupBundle.extract(file, backupStorage.directory, device)
                    }
                    selected to device
                }
                val recovery = withContext(Dispatchers.IO) { BackupBundle.recoveryPackage(backup, backupStorage.directory, identity) }
                logger.record("离线恢复包已生成并验证；没有执行任何分区写入：${recovery.name}")
                _state.update { it.copy(recoveryPackagePath = recovery.absolutePath,
                    pendingExport = PendingExport(recovery, recovery.name, "application/zip"),
                    toast = "离线恢复包已生成。请在 Recovery/救援环境操作，应用未写入分区") }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logger.record("恢复包准备失败：${t.message}")
                _state.update { it.copy(toast = "恢复校验失败：${describe(t)}，可手动选择云端备份包") }
            } finally { finishBackup() }
        }
    }

    private fun requireWriteAccess() {
        requireInitialBackup()
        check(!_state.value.readOnly) { "请先关闭只读模式并启用写入" }
    }

    private fun allowFeatureWrite(): Boolean = try { requireWriteAccess(); true } catch (t: Exception) {
        _state.update { it.copy(toast = t.message) }; false
    }

    fun completeExport(uri: Uri?) {
        val pending = _state.value.pendingExport ?: return
        _state.update { it.copy(pendingExport = null) }
        if (uri == null) return
        work("正在保存") {
            repo.copyToUri(pending.file, uri)
            _state.update { it.copy(toast = "已保存 ${pending.suggestedName}") }
        }
    }

    fun importInto(uri: Uri, targetPath: String, asItem: Boolean? = null) =
        work("正在写入 $targetPath") {
            requireWriteAccess()
        val bytes = repo.readUri(uri)
        repo.writeFile(targetPath, bytes, item = asItem)
        val warn = commitChange()
        val kind = if (asItem == true) "项目文件" else "文件"
        _state.update { it.copy(toast = "已按$kind 类型写入 ${bytes.size} 字节$warn") }
        val entries = repo.list(_state.value.path)
        _state.update { s ->
            // Replacing the file the sheet is showing leaves it stale, so the
            // sheet closes, the way Edit and Delete close it.
            if (s.detail?.path == targetPath) s.copy(entries = entries).withoutSheet()
            else s.copy(entries = entries)
        }
    }

    // ---- bulk import ----------------------------------------------------

    private var bulkRun: Job? = null

    private val featureChecker by lazy { FeatureChecker(featureAccess) }
    private var featuresJob: Job? = null

    // Bulk import and the feature toggles both write NV items, so the guards
    // on their entry points keep them mutually exclusive: only one
    // NV-writing actor may run at a time.

    fun startBulkImport(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(ssrDone = false) }
        val name = repo.displayName(uri)
        var readError: String? = null
        val text = withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
            } catch (t: Throwable) {
                readError = describe(t)
                null
            }
        }
        if (text == null) {
            _state.update {
                it.copy(bulk = BulkState.Preview(
                    name, emptyList(),
                    error = "无法读取所选文件" +
                            (readError?.let { c -> ": $c" } ?: "")))
            }
            return@launch
        }
        val commands = try {
            NvImportParser.parse(text)
        } catch (e: NvImportParseException) {
            _state.update {
                it.copy(bulk = BulkState.Preview(name, emptyList(), error = e.message?.let(::chineseMessage)))
            }
            return@launch
        }
        _state.update { it.copy(bulk = BulkState.Preview(name, commands)) }
    }

    fun closeBulkImport() = _state.update { it.copy(bulk = null) }

    /**
     * Runs the SPC pre-flight, then executes the commands one by one.  A
     * failure never stops the run - it is recorded and the next command is
     * tried, the way mtb reports per-command results.  Losing the connection
     * aborts the run and marks the remaining commands as skipped.
     */
    fun runBulkImport(spc: String) {
        if (!allowFeatureWrite()) return
        if (bulkRun?.isActive == true) return
        if (featuresJob?.isActive == true) return
        val preview = _state.value.bulk as? BulkState.Preview ?: return
        if (preview.commands.isEmpty()) return
        bulkRun = viewModelScope.launch {
            val cmds = preview.commands

            // Every bulk-state write below is guarded: if the dialog was
            // closed mid-run, the update must not resurrect it.
            if (cmds.any { it.op == BulkOp.WRITE }) {
                val unlocked = try {
                    repo.spcUnlock(spc)
                } catch (t: Throwable) {
                    _state.update { s ->
                        if (s.bulk == null) s
                        else s.copy(bulk = preview.copy(error = "SPC 预检查失败：${describe(t)}"))
                    }
                    return@launch
                }
                if (!unlocked) {
                    _state.update { s ->
                        if (s.bulk == null) s
                        else s.copy(bulk = preview.copy(
                            error = "基带拒绝了 SPC，已终止导入"))
                    }
                    return@launch
                }
            }

            _state.update { s ->
                if (s.bulk == null) s
                else s.copy(bulk = BulkState.Running(preview.fileName, cmds, 0, emptyList()))
            }

            val results = mutableListOf<BulkResult>()
            var aborted = false
            for (cmd in cmds) {
                if (aborted) {
                    results.add(BulkResult(cmd.op, cmd.efsPath, cmd.simTag, ok = false, error = "连接已断开，已跳过"))
                    continue
                }
                val result = try {
                    when (cmd.op) {
                        BulkOp.WRITE -> {
                            repo.writeFile(cmd.efsPath, parseHexText(cmd.dataHex!!), item = null)
                            BulkResult(cmd.op, cmd.efsPath, cmd.simTag, ok = true)
                        }
                        BulkOp.DELETE -> {
                            repo.unlink(cmd.efsPath)
                            BulkResult(cmd.op, cmd.efsPath, cmd.simTag, ok = true)
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (t is EfsException &&
                        (!repo.connected || t.message?.contains("closed the connection") == true)
                    ) aborted = true
                    BulkResult(cmd.op, cmd.efsPath, cmd.simTag, ok = false, error = describe(t))
                }
                results.add(result)
                _state.update { s ->
                    if (s.bulk == null) s
                    else s.copy(bulk = BulkState.Running(preview.fileName, cmds, results.size, results.toList()))
                }
            }

            var summary: String? = null
            if (!aborted && results.any { it.ok }) {
                try {
                    repo.sync()
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    summary = "EFS 日志同步失败：${describe(t)}"
                }
            }
            _state.update { s ->
                if (s.bulk == null) s else s.copy(bulk = BulkState.Done(results.toList(), summary))
            }
        }
    }

    fun modemSsr() = work("正在重启基带") { restartModem() }
    fun saveAndApplyFeatures() = work("正在保存应用") { restartModem() }

    /**
     * The menu's restart with the lock still on: the helper refuses an SSR in
     * read-only mode, so the confirmed tap lifts the lock and restarts within
     * one busy span.  Writes are deliberately left enabled afterwards -- that
     * is what the prompt asked the user to accept -- and [toggleReadOnly]'s
     * own toast is skipped, since the restart note replaces it a few seconds
     * later anyway.
     */
    fun enableWritesAndModemSsr() = work("正在启用写入并重启基带") {
        requireInitialBackup()
        if (_state.value.readOnly) {
            repo.setReadOnly(false)
            _state.update { it.copy(readOnly = false) }
        }
        restartModem()
    }

    private suspend fun restartModem() {
        requireWriteAccess()
        var reconnected = false
        val msg = try {
            reconnected = repo.modemSsr()
            if (reconnected) "基带已重启，EFS 修改已提交并生效"
            else "基带已重启，但连接未恢复，请在菜单中选择“重新连接”"
        } catch (t: Throwable) {
            describe(t)
        }
        _state.update { it.copy(ssrDone = reconnected) }
        // The listing was read from the modem that just went away, so pull it
        // again over the fresh session rather than leaving stale entries up.
        // Inline rather than open(): that is its own work{} and would fight
        // this one over the busy flag.
        if (reconnected) runCatching {
            val entries = repo.list(_state.value.path)
            _state.update { it.copy(entries = entries) }
        }
        // A dialog would cover the toast, so the SSR button's feedback goes
        // into the dialog itself when one is open.
        routeDialogNote(msg)
    }

    private fun routeDialogNote(message: String) {
        _state.update { s ->
            // When both dialogs are somehow open, the bulk dialog wins.
            when (val bulk = s.bulk) {
                is BulkState.Preview -> s.copy(bulk = bulk.copy(note = message))
                is BulkState.Done -> s.copy(bulk = bulk.copy(note = message))
                else -> when (val f = s.features) {
                    is FeaturesState.Ready -> s.copy(features = f.copy(note = message))
                    else -> s.copy(toast = message)
                }
            }
        }
    }

    // ---- feature toggles ----

    fun openFeatures(slot: Int = DEFAULT_FEATURE_SLOT) {
        require(slot == 0 || slot == 1)
        if (featuresJob?.isActive == true) return
        if (bulkRun?.isActive == true) return
        featuresJob = viewModelScope.launch {
            _state.update { it.copy(features = FeaturesState.Checking(slot), ssrDone = false) }
            checkFeatures(slot)
        }
    }

    fun closeFeatures() {
        // Cancelling mid-check is safe: the check only reads, so stopping it
        // between exchanges is harmless.  Never cancel during Writing - a
        // modification must run to completion so the modem is not left
        // half-written.
        if (_state.value.features is FeaturesState.Checking) featuresJob?.cancel()
        _state.update { it.copy(features = null) }
    }

    fun setFeatureSimSlot(slot: Int) {
        if (featuresJob?.isActive == true) return
        if (bulkRun?.isActive == true) return
        featuresJob = viewModelScope.launch {
            var transitioned = false
            _state.update { s ->
                if (s.features !is FeaturesState.Ready) return@update s
                transitioned = true
                s.copy(features = FeaturesState.Checking(slot))
            }
            // Only re-check when the dialog actually transitioned; a closed
            // dialog must not trigger I/O.
            if (transitioned) checkFeatures(slot)
        }
    }

    private suspend fun checkFeatures(slot: Int) {
        try {
            val result = withContext(Dispatchers.IO) { featureChecker.check(ALL_FEATURES, slot) }
            val backups = withContext(Dispatchers.IO) {
                ALL_FEATURES.mapNotNull { feature -> runCatching { featureBackups.load(feature, slot) }.getOrNull()?.let { feature.id to it } }.toMap()
            }
            _state.update { s ->
                if (s.features == null) s
                else s.copy(
                    features = FeaturesState.Ready(
                        slot,
                        result.statuses,
                        warnBeforeDisable = !disableWarningSuppressed(),
                        restorable = backups.filterValues { !it.restored }.keys,
                        originals = backups.mapValues { it.value.items },
                    ),
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.update { it.copy(features = null, toast = describe(t)) }
        }
    }

    fun disableFeature(id: String, spc: String) {
        if (!allowFeatureWrite()) return
        if (featuresJob?.isActive == true) return
        if (bulkRun?.isActive == true) return
        featuresJob = viewModelScope.launch {
            val ready = _state.value.features as? FeaturesState.Ready ?: return@launch
            val feature = ALL_FEATURES.first { it.id == id }
            // A failed write is retryable, so WriteError is accepted here too.
            val status = ready.statuses[id]
            if (status !is FeatureStatus.CanDisable && status !is FeatureStatus.WriteError) return@launch
            if (!ensureSpc(spc, "基带拒绝了 SPC，功能配置未修改。", ::noteFeatures)) return@launch
            // The last check only says what to write; what the write really
            // left behind is read back below.
            updateStatus(feature.id, FeatureStatus.Writing)
            try {
                withContext(Dispatchers.IO) {
                    val old = featureBackups.load(feature, ready.simSlot)
                    val snapshot = old?.takeUnless { it.restored }
                        ?: featureRestorer.capture(feature, ready.simSlot, backupStorage.deviceId)
                    featureBackups.save(snapshot, feature)
                }
                logger.record("禁用前原值双份保存成功：${feature.id} SIM${ready.simSlot}")
                _state.update { current ->
                    val f = current.features as? FeaturesState.Ready ?: return@update current
                    current.copy(features = f.copy(restorable = f.restorable + feature.id))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val message = "原值备份失败，未执行禁用：${describe(t)}"
                updateStatus(feature.id, FeatureStatus.WriteError(message))
                noteFeatures(message)
                logger.record(message)
                return@launch
            }
            // This change needs a restart of its own: an earlier "Done!" no
            // longer speaks for what the dialog shows.
            _state.update { it.copy(ssrDone = false) }
            val error = try {
                withContext(Dispatchers.IO) {
                    requireWriteAccess()
                    featureChecker.disable(feature, ready.simSlot)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                updateStatus(feature.id, FeatureStatus.WriteError(describe(t)))
                return@launch
            }
            if (error != null) {
                updateStatus(feature.id, FeatureStatus.WriteError(chineseMessage(error)))
                noteFeatures(chineseMessage(error))
                return@launch
            }
            // The item files this just wrote are still only in the journal,
            // and the modem restart the dialog asks for would roll them
            // straight back, so commit before anything else.
            val syncWarning = commitChange()
            // A successful write is not proof the feature is off: ul_mimo,
            // tdd_ul_mimo and lowband_4rx share cap_limit_rf_mimo with
            // mutually exclusive payloads, so disabling one re-enables the
            // others.  Re-read every feature instead of assuming.  The row
            // keeps its Writing spinner until the fresh statuses land, which
            // also keeps the dialog locked for the duration.
            checkFeatures(ready.simSlot)
            logger.record("功能配置已写入：${feature.id} SIM${ready.simSlot}$syncWarning")
            noteFeatures("功能配置已写入，请点击“保存应用”后验证实际功能。$syncWarning")
            logger.captureNative()
        }
    }

    fun restoreFeature(id: String, spc: String) {
        if (!allowFeatureWrite() || featuresJob?.isActive == true || bulkRun?.isActive == true) return
        featuresJob = viewModelScope.launch {
            val ready = _state.value.features as? FeaturesState.Ready ?: return@launch
            val feature = ALL_FEATURES.first { it.id == id }
            if (!ensureSpc(spc, "基带拒绝了 SPC，未执行恢复", ::noteFeatures)) return@launch
            updateStatus(id, FeatureStatus.Writing)
            _state.update { it.copy(ssrDone = false) }
            val message = try {
                withContext(Dispatchers.IO) {
                    val snapshot = featureBackups.load(feature, ready.simSlot)
                        ?: error("没有找到此功能的原值备份")
                    check(!snapshot.restored) { "此原值备份已恢复" }
                    featureRestorer.restore(snapshot, feature, backupStorage.deviceId) { logger.record("恢复原值：$it") }
                    runCatching { featureBackups.save(snapshot.copy(restored = true), feature) }
                        .onFailure { logger.record("原值恢复及校验成功，但备份状态标记保存失败：${it.message}") }
                }
                "已恢复禁用前原值并回读验证，请点击“保存应用”后验证实际功能"
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                "恢复失败，原值备份已保留，可重试：${describe(t)}"
            }
            logger.record(message)
            checkFeatures(ready.simSlot)
            noteFeatures(message)
            logger.captureNative()
        }
    }

    /** One-way: once suppressed, the warning never comes back (no reset UI). */
    fun suppressDisableWarning() {
        prefs.edit().putBoolean(KEY_DISABLE_WARNING_SUPPRESSED, true).apply()
        _state.update { s ->
            val f = s.features as? FeaturesState.Ready ?: return@update s
            s.copy(features = f.copy(warnBeforeDisable = false))
        }
    }

    private fun disableWarningSuppressed(): Boolean =
        prefs.getBoolean(KEY_DISABLE_WARNING_SUPPRESSED, false)

    /**
     * SPC pre-flight before any modification.  Reports failure through
     * [onReject] - the unlock's exception, or [rejectionMessage] when the
     * modem rejected the code (each caller keeps its own wording) - and
     * returns false so the caller can bail out.
     */
    private suspend fun ensureSpc(
        spc: String,
        rejectionMessage: String,
        onReject: (String) -> Unit,
    ): Boolean {
        val unlocked = try {
            withContext(Dispatchers.IO) { repo.spcUnlock(spc) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            onReject("SPC 预检查失败：${describe(t)}")
            return false
        }
        if (!unlocked) {
            onReject(rejectionMessage)
            return false
        }
        return true
    }

    private fun updateStatus(id: String, status: FeatureStatus) {
        _state.update { s ->
            val f = s.features as? FeaturesState.Ready ?: return@update s
            s.copy(features = f.copy(statuses = f.statuses + (id to status)))
        }
    }

    private fun noteFeatures(message: String) {
        _state.update { s ->
            val f = s.features as? FeaturesState.Ready
                ?: return@update s.copy(toast = message)
            s.copy(features = f.copy(note = message))
        }
    }

    // ---- update check ---------------------------------------------------

    /**
     * Asks GitHub for the newest release. The automatic check on startup stays
     * quiet unless there is something newer -- being offline is not worth a
     * toast -- while a check the user asked for reports every outcome.
     */
    fun checkForUpdates(manual: Boolean) {
        viewModelScope.launch {
            val current = installedVersion
            val release = try {
                UpdateChecker.latestNewerThan(current)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (manual) _state.update { it.copy(toast = describe(t)) }
                return@launch
            }
            if (release == null) {
                if (manual) {
                    _state.update { it.copy(toast = "当前版本 $current 已是最新上游版本") }
                }
                return@launch
            }
            // A version the user waved away stays away, but only automatically.
            if (!manual && prefs.getString(KEY_SKIPPED_VERSION, null) == release.version) {
                return@launch
            }
            _state.update { it.copy(update = release) }
        }
    }

    fun dismissUpdate() = _state.update { it.copy(update = null) }

    /** Closes the dialog and stops the automatic check from offering this version again. */
    fun skipUpdate() {
        _state.value.update?.let {
            prefs.edit().putString(KEY_SKIPPED_VERSION, it.version).apply()
        }
        dismissUpdate()
    }

    // ---- mutations -----------------------------------------------------

    fun createDir(name: String) = work("正在创建 $name") {
            requireWriteAccess()
        repo.mkdir(Paths.child(_state.value.path, name))
        val warn = commitChange()
        val entries = repo.list(_state.value.path)
        _state.update { it.copy(entries = entries, toast = "已创建 $name$warn") }
    }

    fun chmod(path: String, mode: Int) = work("修改权限") {
            requireWriteAccess()
        repo.chmod(path, mode)
        val warn = commitChange()
        val entries = repo.list(_state.value.path)
        _state.update { it.copy(entries = entries, toast = "权限已修改$warn") }
    }

    fun delete(detail: Detail) = work("正在删除 ${detail.path}") {
            requireWriteAccess()
        repo.delete(detail.entry, detail.path)
        val warn = commitChange()
        val entries = repo.list(_state.value.path)
        _state.update {
            it.copy(entries = entries, toast = "已删除 ${detail.entry.name}$warn").withoutSheet()
        }
    }

    fun sync() = work("正在同步 EFS 日志") {
            requireWriteAccess()
        repo.sync()
        _state.update { it.copy(toast = "EFS 日志已同步") }
    }

    // ---- NV ------------------------------------------------------------

    fun nvRead(item: Int, index: Int?) = work("正在读取 NV 项 $item") {
        try {
            val res = repo.nvRead(item, index)
            _state.update { it.copy(nv = res, nvError = null) }
        } catch (t: Throwable) {
            _state.update { it.copy(nv = null, nvError = describe(t)) }
        }
    }

    fun nvWrite(item: Int, hex: String, index: Int?) = work("正在写入 NV 项 $item") {
            requireWriteAccess()
        repo.nvWrite(item, hex, index)
        // NV items live in EFS on this generation of modem, so an NV write is
        // journaled like any other and needs the same commit.
        val warn = commitChange()
        _state.update { it.copy(toast = "NV 项 $item 已写入$warn") }
    }

    fun spcUnlock(spc: String) = work("正在发送 SPC") {
            requireWriteAccess()
        val msg = try {
            if (repo.spcUnlock(spc)) "SPC 验证成功，已解锁 NV 写入"
            else "SPC 验证失败，基带未接受该编程码"
        } catch (t: Throwable) {
            describe(t)
        }
        // The dialog covers the snackbar, so the SPC test's feedback goes into
        // the dialog itself while it is open.
        routeDialogNote(msg)
    }

    fun rawSend(hex: String, onResult: (String) -> Unit) = work("正在发送原始 DIAG 数据包") {
            requireWriteAccess()
        onResult(repo.rawExchange(hex))
    }
}

fun errnoName(e: Int): String = when (e) {
    1 -> "操作不被允许，EPERM"
    2 -> "文件或目录不存在，ENOENT"
    5 -> "输入输出错误，EIO"
    6 -> "设备或地址不存在，ENXIO"
    9 -> "文件描述符无效，EBADF"
    12 -> "内存不足，ENOMEM"
    13 -> "权限不足，EACCES"
    17 -> "文件已存在，EEXIST"
    20 -> "目标不是目录，ENOTDIR"
    21 -> "目标是目录，EISDIR"
    22 -> "参数无效，EINVAL"
    23 -> "系统打开文件过多，ENFILE"
    24 -> "进程打开文件过多，EMFILE"
    27 -> "文件过大，EFBIG"
    28 -> "设备空间不足，ENOSPC"
    30 -> "只读文件系统，EROFS"
    36 -> "文件名过长，ENAMETOOLONG"
    39 -> "目录非空，ENOTEMPTY"
    else -> "EFS 错误码 $e"
}
