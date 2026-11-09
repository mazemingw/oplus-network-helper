package com.nvmex.networkhelper.util.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import com.nvmex.networkhelper.BuildConfig
import com.nvmex.networkhelper.network.base.NetworkConfig
import dev.qcom.efs.DiagnosticEvents
import dev.qcom.efs.RootDaemon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Global app diagnostics. No collector starts a modem session or performs an EFS write. */
object DiagnosticReporter {
    internal const val JOB_ID = 0x4E4844
    private const val PREFS = "diagnostic-reporting"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadMutex = Mutex()
    private val lock = Any()
    private val breadcrumbs = ArrayDeque<String>()
    private val rateLimiter = DiagnosticRateLimiter()
    private val logcatCollector = DiagnosticLogcatCollector()
    private val console = DiagnosticLogBuffer()
    private val _logs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logs = _logs.asStateFlow()
    private val _dropped = MutableStateFlow(0L)
    val dropped = _dropped.asStateFlow()
    private var generation = 0L
    private val _enabled = MutableStateFlow(true)
    val enabled = _enabled.asStateFlow()
    @Volatile private var application: Application? = null
    @Volatile private var device = JSONObject()
    @Volatile private var currentCall: okhttp3.Call? = null
    private var currentCallAutomatic = true
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).build()
    private val manualHttp = http.newBuilder().callTimeout(3, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build()
    private val prefs get() = application!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root get() = File(application!!.noBackupFilesDir, "diagnostics").apply { mkdirs() }
    private val events get() = File(root, "events").apply { mkdirs() }
    private val pending get() = File(root, "pending").apply { mkdirs() }
    private val endpoint get() = NetworkConfig.BASE_URL + "networkhelp/diagnostics/"

    @Synchronized fun initialize(app: Application) {
        if (application != null) return
        application = app
        _enabled.value = prefs.getBoolean("automatic_upload", true)
        runCatching {
            val saved = JSONArray(prefs.getString("automatic_admissions", "[]"))
            rateLimiter.restore((0 until minOf(saved.length(), 20)).map { i ->
                val item = saved.getJSONObject(i); DiagnosticAdmission(item.getString("key"), item.getLong("at"))
            })
        }
        device = JSONObject().put("model", Build.MODEL).put("hardware", Build.HARDWARE)
            .put("socModel", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.BOARD)
            .put("socManufacturer", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.MANUFACTURER)
            .put("osVersion", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Persist synchronously: the process may be terminated as soon as the old handler runs.
            runCatching {
                recordConsole(DiagnosticLevel.ERROR, "uncaught_exception", error.message ?: error.javaClass.name, error.stackTraceToString())
                synchronized(lock) { saveConsole(console.snapshot()) }
                persist("uncaught_exception", error.message ?: error.javaClass.name,
                    error.stackTraceToString(), "fatal", mapOf("thread" to thread.name))
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
        DiagnosticEvents.listener = { source, message, error ->
            if (error != null) report(source, error, mapOf("operation" to message))
            else if (Regex("失败|错误|异常|未纳入|(?i:error|failed|exception)").containsMatchIn(message)) report(source, message)
            else info(source, message)
        }
        scope.launch {
            loadConsole()
            runCatching { device = clean(DiagnosticDevice.snapshot(app)) as JSONObject }
            capturePreviousExit(app)
            if (_enabled.value && pending.listFiles().orEmpty().isNotEmpty()) schedule()
        }
        scope.launch {
            var saved = emptyList<DiagnosticLogEntry>()
            var lastSave = 0L
            while (true) {
                delay(500)
                synchronized(lock) {
                    logcatCollector.flush().forEach(::handleCapturedLog)
                    val snapshot = console.snapshot()
                    _logs.value = snapshot; _dropped.value = console.dropped
                    if (snapshot != saved && System.currentTimeMillis() - lastSave >= 5000) {
                        runCatching { saveConsole(snapshot) }
                        saved = snapshot; lastSave = System.currentTimeMillis()
                    }
                }
            }
        }
        // Unprivileged logcat is restricted to our UID and --pid restricts it to this process.
        scope.launch { collectLogcat() }
    }

    fun setEnabled(value: Boolean) {
        if (application == null) return
        synchronized(lock) {
            prefs.edit().putBoolean("automatic_upload", value).commit()
            _enabled.value = value
            if (!value) {
                generation++
                currentCall?.cancel()
                http.dispatcher.cancelAll()
                application!!.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
                pending.listFiles().orEmpty().forEach { it.delete() }
            }
        }
        if (value) schedule()
    }

    /** Use from handled exceptions in any feature. Caller metadata is scrubbed before storage. */
    fun report(source: String, error: Throwable, details: Map<String, String> = emptyMap()) {
        if (error is CancellationException) return
        enqueue(source, error.message ?: error.javaClass.name, error.stackTraceToString(), details)
    }
    fun report(source: String, message: String, details: Map<String, String> = emptyMap()) =
        enqueue(source, message, null, details)

    fun breadcrumb(message: String) {
        info("app", message)
    }

    fun info(source: String, message: String) { recordConsole(DiagnosticLevel.INFO, source, message) }
    fun warning(source: String, message: String) { recordConsole(DiagnosticLevel.WARNING, source, message) }

    private fun recordConsole(level: DiagnosticLevel, source: String, message: String, detail: String = ""): Boolean {
        if (!console.append(level, source, message, detail)) return false
        synchronized(lock) {
            val line = DiagnosticRedactor.redact("$source: ${message.take(1200)}")
            if (breadcrumbs.lastOrNull() == line) return true
            breadcrumbs.addLast(line)
            while (breadcrumbs.size > 100) breadcrumbs.removeFirst()
        }
        return true
    }

    private fun enqueue(source: String, message: String, stack: String?, details: Map<String, String>) {
        if (application == null) return
        val expected = DiagnosticLogPolicy.expectedModemRefusal(source, message)
        val level = if (expected) DiagnosticLevel.WARNING else DiagnosticLevel.ERROR
        if (!recordConsole(level, source, message, stack.orEmpty())) return
        if (expected || !_enabled.value) return
        val signature = source + DiagnosticLogPolicy.signature(message.take(1024))
        val key = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        val epoch: Long
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!rateLimiter.admit(key, now)) return
            val saved = JSONArray().apply { rateLimiter.snapshot().forEach { put(JSONObject().put("key", it.key).put("at", it.at)) } }
            prefs.edit().putString("automatic_admissions", saved.toString()).commit()
            epoch = generation
        }
        scope.launch {
            // Allow logcat's following stack lines to arrive before taking the context snapshot.
            runCatching {
                val context = details.toMutableMap()
                if (source == "efs") {
                    context["efsLog"] = efsLogTail()
                    context["nativeLog"] = tail(RootDaemon.logFile(application!!), 16000)
                    if (message.contains("备份") || message.contains("存储")) context["storageTopology"] = DiagnosticDevice.storageTopology()
                }
                persist(source, message, stack, "error", context, epoch)
            }
        }
    }

    private fun persist(source: String, message: String, stack: String?, severity: String, details: Map<String, String>, epoch: Long? = null) {
        val id = UUID.randomUUID().toString()
        val report = JSONObject().put("schema", 1).put("id", id).put("occurredAt", System.currentTimeMillis())
            .put("source", source.take(100)).put("severity", severity).put("message", message.take(4000))
            .put("stack", stack.orEmpty().take(16000)).put("device", JSONObject(device.toString()))
            .put("context", JSONObject(details.entries.take(12).associate { it.key.take(100) to it.value.take(12000) }))
        synchronized(lock) {
            if (epoch != null && epoch != generation) return
            report.put("breadcrumbs", JSONArray(breadcrumbs.toList().takeLast(60)))
            val target = File(events, "$id.json")
            val temporary = File(events, "$id.tmp")
            temporary.writeText(clean(report).toString(), Charsets.UTF_8)
            check(temporary.renameTo(target))
            if (_enabled.value) File(pending, "$id.json").writeText("")
            // Bounded private spool: retain at most 40 reports / 8 MiB, including offline retries.
            val ordered = events.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.lastModified() }
            var bytes = ordered.sumOf { it.length() }; var count = ordered.size
            for (file in ordered) {
                if (count <= 40 && bytes <= 8 * 1024 * 1024) break
                bytes -= file.length(); count--; File(pending, file.name).delete(); file.delete()
            }
        }
        if (_enabled.value) schedule()
    }

    private fun schedule() {
        if (!_enabled.value) return
        val app = application ?: return
        runCatching {
            val scheduler = app.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) == null && pending.listFiles().orEmpty().isNotEmpty())
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(app, DiagnosticUploadJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(10_000)
                    .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }

    internal fun resumePendingAfterJob() {
        // A new report can arrive between the worker's empty check and jobFinished().
        scope.launch { delay(1000); if (_enabled.value) schedule() }
    }

    /** Called by JobScheduler, never by the crashing thread. false requests an OS-backed retry. */
    internal suspend fun uploadPending(): Boolean = uploadMutex.withLock {
        if (!_enabled.value) return@withLock true
        val app = application ?: return@withLock false
        val uploadEpoch = synchronized(lock) { generation }
        var identity: Pair<String?, String>? = null
        for (marker in pending.listFiles().orEmpty().sortedBy { it.lastModified() }.take(8)) {
            currentCoroutineContext().ensureActive()
            if (!_enabled.value) return@withLock true
            val file = File(events, marker.name)
            if (!file.isFile) { marker.delete(); continue }
            try {
                val report = JSONObject(file.readText())
                if (storedLevel(report.optString("source"), report.optString("message")) != DiagnosticLevel.ERROR) {
                    marker.delete(); continue
                }
                val uploadIdentity = identity ?: DiagnosticDevice.imei(app).also { identity = it }
                report.getJSONObject("device").put("imei1", uploadIdentity.first ?: JSONObject.NULL)
                    .put("imeiSlot", 0).put("imeiSource", uploadIdentity.second)
                val (id, secret) = credentials()
                suspend fun send(): JSONObject = withContext(Dispatchers.IO) {
                    post("reports", report, "$id.$secret", expectedEpoch = uploadEpoch)
                }
                if (!prefs.getBoolean("registered", false)) register(id, secret)
                val receipt = try { send() } catch (e: HttpFailure) {
                    if (e.status != 401 && e.status != 403) throw e
                    register(id, secret); send()
                }
                check(receipt.optBoolean("ok") && receipt.optString("id") == report.getString("id"))
                synchronized(lock) { marker.delete(); prefs.edit().putLong("last_success", System.currentTimeMillis()).apply() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // Never log transport failures here: that would recursively report the reporter itself.
                return@withLock false
            }
        }
        pending.listFiles().orEmpty().isEmpty()
    }

    private fun credentials(): Pair<String, String> = synchronized(lock) {
        val id = prefs.getString("installation_id", null)
        val secret = prefs.getString("installation_secret", null)
        if (id != null && secret != null) id to secret else {
            val newId = UUID.randomUUID().toString()
            val newSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            check(prefs.edit().putString("installation_id", newId).putString("installation_secret", newSecret).commit())
            newId to newSecret
        }
    }
    private fun register(id: String, secret: String, manual: Boolean = false) {
        check(post("register", JSONObject().put("installationId", id).put("secret", secret), manual = manual).optBoolean("ok"))
        prefs.edit().putBoolean("registered", true).apply()
    }
    private class HttpFailure(val status: Int) : Exception("diagnostic_http_$status")
    internal fun cancelUpload(automaticOnly: Boolean = false) {
        synchronized(lock) { if (!automaticOnly || currentCallAutomatic) currentCall?.cancel() }
    }
    private fun post(route: String, body: JSONObject, credentials: String? = null, manual: Boolean = false, expectedEpoch: Long? = null): JSONObject {
        check(endpoint.startsWith("https://"))
        val request = Request.Builder().url(endpoint + route)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        credentials?.let { request.header("Authorization", "Bearer $it") }
        val call = synchronized(lock) {
            check(manual || _enabled.value)
            check(expectedEpoch == null || expectedEpoch == generation)
            (if (manual) manualHttp else http).newCall(request.build()).also { currentCall = it; currentCallAutomatic = !manual }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw HttpFailure(response.code)
                return JSONObject(response.body?.string().orEmpty())
            }
        } finally { synchronized(lock) { if (currentCall === call) currentCall = null } }
    }

    /** Sharing never calls the identity provider; only scrubbed copies can leave via FileProvider. */
    suspend fun exportRedacted(context: Context, selected: List<DiagnosticLogEntry> = console.snapshot()): File = withContext(Dispatchers.IO) {
        val output = JSONObject().put("schema", 1).put("exportedAt", System.currentTimeMillis())
            .put("device", clean(JSONObject(device.toString())).let { (it as JSONObject).put("imei1", "[已隐藏]") })
        output.put("kind", "diagnostic-console").put("entries", entriesJson(selected.take(DiagnosticLogLimits.MAX_ENTRIES)))
        val folder = File(context.cacheDir, "diagnostic-share").apply { mkdirs() }
        folder.listFiles().orEmpty().filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }.forEach { it.delete() }
        File(folder, "networkhelper-diagnostics-${System.currentTimeMillis()}.json").also {
            it.writeText((clean(output) as JSONObject).toString(), Charsets.UTF_8)
            check(it.length() <= DiagnosticLogLimits.MAX_BYTES) { "日志超出 16MB 限制" }
            val ordered = folder.listFiles().orEmpty().sortedBy { file -> file.lastModified() }
            var bytes = ordered.sumOf { file -> file.length() }; var count = ordered.size
            for (file in ordered) {
                if (count <= 5 && bytes <= DiagnosticLogLimits.MAX_BYTES) break
                if (file == it) continue
                bytes -= file.length(); count--; file.delete()
            }
        }
    }

    fun formatLogs(selected: List<DiagnosticLogEntry>): String {
        val formatter = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT)
        return selected.joinToString("\n\n") { row ->
        "${formatter.format(java.util.Date(row.lastAt))} " +
            "[${row.level.name}] ${row.source} · ${row.message}" +
            (if (row.repeats > 1) " ×${row.repeats}" else "") + (if (row.detail.isNotBlank()) "\n${row.detail}" else "")
        }
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            generation++; cancelUpload()
            application?.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
            console.clear(); breadcrumbs.clear(); logcatCollector.clear()
            _logs.value = emptyList(); _dropped.value = 0
            for (folder in listOf(events, pending, File(application!!.cacheDir, "diagnostic-share")))
                folder.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
            saveConsole(emptyList())
        }
    }

    suspend fun uploadConsole(selected: List<DiagnosticLogEntry>): String = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty()) { "没有可上传的日志" }
        uploadMutex.withLock {
            val app = checkNotNull(application)
            val reportDevice = clean(JSONObject(device.toString())) as JSONObject
            val identity = DiagnosticDevice.imei(app)
            reportDevice.put("imei1", identity.first ?: JSONObject.NULL).put("imeiSlot", 0).put("imeiSource", identity.second)
            val id = UUID.randomUUID().toString()
            val body = JSONObject().put("schema", 1).put("id", id).put("occurredAt", System.currentTimeMillis())
                .put("source", "console/manual").put("severity", "info").put("message", "用户手动上传日志监控台")
                .put("device", reportDevice).put("entries", entriesJson(selected.take(DiagnosticLogLimits.MAX_ENTRIES)))
            check(body.toString().toByteArray(Charsets.UTF_8).size <= DiagnosticLogLimits.MAX_BYTES) { "日志超出 16MB 限制" }
            val (installationId, secret) = credentials()
            if (!prefs.getBoolean("registered", false)) register(installationId, secret, manual = true)
            val receipt = try { post("console", body, "$installationId.$secret", manual = true) }
                catch (error: HttpFailure) {
                    if (error.status == 404) throw IllegalStateException("后台尚未部署日志监控台接口，请更新 diagnostics.js")
                    if (error.status != 401 && error.status != 403) throw error
                    register(installationId, secret, manual = true)
                    post("console", body, "$installationId.$secret", manual = true)
                }
            check(receipt.optBoolean("ok") && receipt.optString("id") == id) { "后台没有确认日志收件" }
            receipt.optString("receiptCode").also {
                check(it.matches(Regex("NH-[0-9]{8}-[A-F0-9]{16}"))) { "后台未返回有效日志回执" }
                prefs.edit().putString("last_console_receipt", it).apply()
            }
        }
    }

    fun lastReceiptCode(): String = if (application == null) "" else prefs.getString("last_console_receipt", "").orEmpty()

    private fun entriesJson(selected: List<DiagnosticLogEntry>) = JSONArray().apply {
        selected.forEach { row -> put(JSONObject().put("id", row.id).put("firstAt", row.firstAt).put("lastAt", row.lastAt)
            .put("level", row.level.name).put("source", row.source).put("message", row.message)
            .put("detail", row.detail).put("repeats", row.repeats)) }
    }

    private fun saveConsole(selected: List<DiagnosticLogEntry>) {
        val temporary = File(root, "console.tmp")
        temporary.writeText(entriesJson(selected).toString(), Charsets.UTF_8)
        check(temporary.length() <= DiagnosticLogLimits.MAX_BYTES) { "日志超出容量限制" }
        check(temporary.renameTo(File(root, "console.json")))
    }

    private fun loadConsole() {
        synchronized(lock) {
            runCatching {
                val file = File(root, "console.json")
                if (file.isFile && file.length() <= DiagnosticLogLimits.MAX_BYTES) {
                    val values = JSONArray(file.readText())
                    val restored = (0 until minOf(values.length(), DiagnosticLogLimits.MAX_ENTRIES)).mapNotNull { index -> runCatching {
                        val row = values.getJSONObject(index)
                        DiagnosticLogEntry(index.toLong(), row.getLong("firstAt"), row.getLong("lastAt"),
                            DiagnosticLevel.valueOf(row.getString("level")), row.getString("source"), row.getString("message"),
                            row.optString("detail"), row.optInt("repeats", 1))
                    }.getOrNull() }
                    console.restore(restored)
                } else {
                    // Migrate the previous error-only store once; do not reimport bulky per-report contexts.
                    events.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.lastModified() }.takeLast(40).forEach { event ->
                        runCatching {
                            val row = JSONObject(event.readText())
                            val source = row.optString("source"); val message = row.optString("message")
                            console.append(storedLevel(source, message), source, message, row.optString("stack"), row.optLong("occurredAt"))
                        }
                    }
                }
                _logs.value = console.snapshot()
            }
        }
    }

    private fun storedLevel(source: String, message: String) = when {
        source.startsWith("logcat/") -> DiagnosticLogPolicy.level(source.removePrefix("logcat/"), "E", message)
        DiagnosticLogPolicy.expectedModemRefusal(source, message) -> DiagnosticLevel.WARNING
        else -> DiagnosticLevel.ERROR
    }

    private fun clean(value: Any?): Any = when (value) {
        is JSONObject -> JSONObject().apply {
            value.keys().forEach { key ->
                val item = value.opt(key)
                put(key, when {
                    key == "id" && item is String && item.matches(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) -> item
                    key == "apkSha256" && item is String && item.matches(Regex("[a-f0-9]{64}")) -> item
                    DiagnosticRedactor.isSensitiveKey(key) -> "[已隐藏]"
                    else -> clean(item)
                })
            }
        }
        is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(clean(value.opt(i))) }
        is String -> DiagnosticRedactor.redact(value)
        else -> value ?: JSONObject.NULL
    }

    private fun efsLogTail(): String = runCatching {
        val file = File(application!!.filesDir, "logs").listFiles().orEmpty().filter { it.name.startsWith("efs-") }.maxByOrNull { it.lastModified() }
        if (file == null) "" else tail(file, 16000)
    }.getOrDefault("")
    private fun tail(file: File, limit: Int): String = runCatching {
        RandomAccessFile(file, "r").use { input ->
            input.seek((input.length() - limit).coerceAtLeast(0))
            ByteArray((input.length() - input.filePointer).toInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
        }
    }.getOrDefault("")

    private suspend fun collectLogcat() {
        val linePattern = Regex("^\\S+\\s+\\S+\\s+\\d+\\s+(\\d+)\\s+([WEF])\\s+([^:]+):\\s?(.*)$")
        var attempt = 0
        while (attempt++ < 3) {
            try {
                val process = ProcessBuilder("logcat", "--pid=${Process.myPid()}", "-T", "1", "-v", "threadtime", "*:W").start()
                try {
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                        val match = linePattern.matchEntire(line) ?: return@forEach
                        val tag = match.groupValues[3].trim(); val message = match.groupValues[4]
                        if (tag == "DiagnosticReporter" || tag == "QcomEfs") return@forEach
                        logcatCollector.accept(tag, match.groupValues[1], match.groupValues[2], message).forEach(::handleCapturedLog)
                    } }
                } finally { process.destroyForcibly() }
            } catch (_: Exception) { /* Devices may restrict logcat; the public API and crash hook still work. */ }
            delay(30_000)
        }
    }

    private fun handleCapturedLog(log: CapturedDiagnostic) {
        if (log.level == DiagnosticLevel.ERROR) enqueue("logcat/${log.tag}", log.message, log.detail, emptyMap())
        else recordConsole(log.level, "logcat/${log.tag}", log.message, log.detail)
    }

    private fun capturePreviousExit(app: Application) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val exits = app.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(app.packageName, 0, 4).orEmpty()
            val last = prefs.getLong("last_exit_timestamp", 0)
            for (exit in exits.filter { it.timestamp > last }.sortedBy { it.timestamp }) {
                if (exit.reason !in setOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_CRASH)) continue
                val trace = runCatching { exit.traceInputStream?.use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(2048)
                    while (bytes.size() < 24000) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 24000 - bytes.size()))
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                } }.getOrNull().orEmpty()
                report("process_exit", "reason=${exit.reason}; ${exit.description.orEmpty()}", mapOf("trace" to trace,
                    "exitTimestamp" to exit.timestamp.toString(), "status" to exit.status.toString()))
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit_timestamp", it).apply() }
        }
    }
}
