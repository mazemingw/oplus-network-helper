package com.nvmex.networkhelper.viewmodel.menu

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.model.PluginDownloadCandidateItem
import com.nvmex.networkhelper.network.model.PluginDownloadCandidatesReq
import com.nvmex.networkhelper.network.model.PluginOssSignReq
import com.nvmex.networkhelper.network.model.PluginOssSignResp
import com.nvmex.networkhelper.network.model.PluginUploadReportReq
import com.nvmex.networkhelper.util.shell.SuShellRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlin.math.max

@HiltViewModel
class PluginShareViewModel @Inject constructor(
    private val api: ApiService,
    private val okHttp: OkHttpClient,
    @param:ApplicationContext private val appContext: Context
) : ViewModel() {

    private val maxSharePackageBytes = 200L * 1024L * 1024L
    private val su = SuShellRunner()
    private val engineerFilesDir = "/data/data/com.oplus.engineernetwork/files"
    private val targetPluginPath = "$engineerFilesDir/plugin-release.zip"

    private val _ui = MutableStateFlow(PluginShareUiState())
    val ui: StateFlow<PluginShareUiState> = _ui.asStateFlow()

    fun startShare() {
        val current = _ui.value
        if (current.isBusy || current.isUploading || current.isDownloading) return

        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = PluginShareUiState(isBusy = true, stage = "检查 SU...")

            if (!su.hasSu(timeoutMs = 2500)) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        hasSu = false,
                        error = "SU 不可用，无法读取工程模式插件目录"
                    )
                }
                return@launch
            }

            val model = readProp("ro.product.model")
            val brand = readProp("ro.product.brand")
            val buildId = readProp("ro.build.display.id")

            _ui.update {
                it.copy(
                    hasSu = true,
                    model = model,
                    brand = brand,
                    buildDisplayId = buildId,
                    stage = "定位插件文件..."
                )
            }

            val plugin = locatePluginFile()
            if (plugin == null || plugin.sizeBytes <= 0L) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        pluginExists = false,
                        pluginSizeBytes = 0L,
                        pluginSourcePath = "",
                        error = "未找到 plugin-release.zip（root 探测失败）"
                    )
                }
                return@launch
            }

            _ui.update {
                it.copy(
                    pluginExists = true,
                    pluginSizeBytes = plugin.sizeBytes,
                    pluginSourcePath = plugin.path,
                    stage = "准备本地副本..."
                )
            }

            val localCopy = File(appContext.cacheDir, "plugin-release-share.zip")
            val copyOk = copyPluginToCache(plugin.path, localCopy)
            if (!copyOk || !localCopy.exists()) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        error = "无法从 ${plugin.path} 复制插件文件"
                    )
                }
                return@launch
            }

            _ui.update { it.copy(stage = "打包 system_ext/framework...") }
            val packageZip = File(appContext.cacheDir, "engineer-plugin-framework-share.zip")
            val packageResult = buildPluginSharePackage(localCopy, packageZip)
            if (!packageResult.ok || !packageZip.exists() || packageZip.length() <= 0L) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        packageSizeBytes = packageResult.fileSizeBytes,
                        error = packageResult.error ?: "打包失败"
                    )
                }
                return@launch
            }
            _ui.update {
                it.copy(packageSizeBytes = packageZip.length())
            }

            if (packageZip.length() > maxSharePackageBytes) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        error = "最终上传包过大：${packageZip.length().toReadableSize()}，当前上限 ${maxSharePackageBytes.toReadableSize()}"
                    )
                }
                return@launch
            }

            val md5 = md5Hex(packageZip)
            _ui.update { it.copy(stage = "请求 OSS 签名...") }

            val signReq = PluginOssSignReq(
                brand = brand.ifBlank { "unknown_brand" },
                model = model.ifBlank { "unknown_model" },
                buildDisplayId = buildId.ifBlank { "unknown_build" },
                fileName = packageZip.name,
                fileSizeBytes = packageZip.length(),
                fileMd5 = md5
            )

            val signResp = runCatching { api.getPluginOssSign(signReq) }.getOrElse { e ->
                _ui.update {
                    it.copy(
                        isBusy = false,
                        error = "签名请求失败：${e.message ?: "network_error"}"
                    )
                }
                return@launch
            }
            val signBody = signResp.body()
            if (!signResp.isSuccessful || signBody == null) {
                val detail = parseHttpError(signResp.code(), signResp.errorBody()?.string())
                _ui.update {
                    it.copy(
                        isBusy = false,
                        error = "签名请求失败：$detail"
                    )
                }
                return@launch
            }

            _ui.update {
                it.copy(
                    isUploading = true,
                    stage = "上传中...",
                    progress = 0f,
                    speedBps = 0.0,
                    requestId = signBody.requestId,
                    ossObjectKey = signBody.key,
                    error = null
                )
            }

            val upload = uploadToOss(signBody, packageZip)
            reportResult(signBody.requestId, signBody.key, upload)

            if (upload.ok) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        isUploading = false,
                        progress = 1f,
                        speedBps = upload.avgSpeedBps,
                        success = true,
                        stage = "上传完成",
                        error = null
                    )
                }
            } else {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        isUploading = false,
                        success = false,
                        error = upload.error ?: "上传失败",
                        stage = "上传失败"
                    )
                }
            }
        }
    }

    fun queryDownloadCandidates() {
        val current = _ui.value
        if (current.isBusy || current.isUploading || current.isDownloading) return

        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    isBusy = true,
                    error = null,
                    success = false,
                    progress = 0f,
                    speedBps = 0.0,
                    stage = "检查 SU...",
                    showDownloadPicker = false,
                    selectedDownloadCandidate = null
                )
            }

            if (!su.hasSu(timeoutMs = 2500)) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        hasSu = false,
                        error = "SU 不可用，无法写入工程模式插件目录"
                    )
                }
                return@launch
            }

            val model = readProp("ro.product.model")
            val brand = readProp("ro.product.brand")
            val buildId = readProp("ro.build.display.id")
            _ui.update {
                it.copy(
                    hasSu = true,
                    brand = brand,
                    model = model,
                    buildDisplayId = buildId,
                    stage = "查询可下载版本..."
                )
            }

            if (model.isBlank()) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        error = "读取机型失败，无法查询可下载版本"
                    )
                }
                return@launch
            }

            val req = PluginDownloadCandidatesReq(
                brand = brand.ifBlank { "unknown_brand" },
                model = model,
                buildDisplayId = buildId.ifBlank { null }
            )
            val resp = runCatching { api.getPluginDownloadCandidates(req) }.getOrElse { e ->
                _ui.update {
                    it.copy(
                        isBusy = false,
                        error = "查询可下载版本失败：${e.message ?: "network_error"}"
                    )
                }
                return@launch
            }
            val body = resp.body()
            if (!resp.isSuccessful || body == null) {
                val detail = parseHttpError(resp.code(), resp.errorBody()?.string())
                _ui.update {
                    it.copy(
                        isBusy = false,
                        error = "查询可下载版本失败：$detail"
                    )
                }
                return@launch
            }

            val items = body.items.filter { it.downloadUrl.isNotBlank() && it.buildDisplayId.isNotBlank() }
            if (items.isEmpty()) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        downloadCandidates = emptyList(),
                        stage = "未找到可下载版本",
                        error = "服务器未找到该机型可用插件"
                    )
                }
                return@launch
            }

            _ui.update {
                it.copy(
                    isBusy = false,
                    downloadCandidates = items,
                    showDownloadPicker = true,
                    stage = if (body.fallbackUsed) "已匹配同机型其他版本，请选择" else "请选择要下载的版本",
                    error = null
                )
            }
        }
    }

    fun selectDownloadCandidate(item: PluginDownloadCandidateItem) {
        val current = _ui.value
        if (current.isBusy || current.isUploading || current.isDownloading) return
        _ui.update {
            it.copy(
                showDownloadPicker = false,
                selectedDownloadCandidate = item
            )
        }
    }

    fun dismissDownloadPicker() {
        _ui.update { it.copy(showDownloadPicker = false) }
    }

    fun dismissDownloadConfirm() {
        _ui.update { it.copy(selectedDownloadCandidate = null) }
    }

    fun clearEngineerModeFiles() {
        val current = _ui.value
        if (current.isBusy || current.isUploading || current.isDownloading) return

        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    isBusy = true,
                    success = false,
                    error = null,
                    stage = "正在清除工程模式..."
                )
            }

            if (!su.hasSu(timeoutMs = 2500)) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        hasSu = false,
                        stage = "清除失败",
                        error = "SU 不可用，无法清理工程模式目录"
                    )
                }
                return@launch
            }

            // Keep the app-owned files directory and its ownership; remove only its children.
            val clearCommand =
                "if [ ! -e '$engineerFilesDir' ]; then exit 0; fi; " +
                        "if [ ! -d '$engineerFilesDir' ]; then echo 'target is not a directory' >&2; exit 2; fi; " +
                        "find '$engineerFilesDir' -mindepth 1 -maxdepth 1 -exec rm -rf {} \\;; " +
                        "if [ -n \"\$(ls -A '$engineerFilesDir' 2>/dev/null)\" ]; then " +
                        "echo 'directory is not empty after cleanup' >&2; exit 3; fi"
            val result = su.execSu(clearCommand, timeoutMs = 30000)

            if (result.code == 0) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        hasSu = true,
                        success = true,
                        stage = "工程模式已清除",
                        pluginExists = false,
                        pluginSizeBytes = 0L,
                        pluginSourcePath = ""
                    )
                }
            } else {
                val detail = result.err.trim().ifBlank { result.out.trim() }.ifBlank {
                    "exit=${result.code}"
                }
                _ui.update {
                    it.copy(
                        isBusy = false,
                        hasSu = true,
                        stage = "清除失败",
                        error = "工程模式目录清理失败：$detail"
                    )
                }
            }
        }
    }

    fun downloadSelectedPlugin() {
        val current = _ui.value
        val selected = current.selectedDownloadCandidate ?: return
        if (current.isBusy || current.isUploading || current.isDownloading) return

        viewModelScope.launch(Dispatchers.IO) {
            _ui.update {
                it.copy(
                    isBusy = true,
                    isDownloading = true,
                    selectedDownloadCandidate = null,
                    showDownloadPicker = false,
                    error = null,
                    success = false,
                    progress = 0f,
                    speedBps = 0.0,
                    stage = "下载插件中..."
                )
            }

            val localFile = File(appContext.cacheDir, "plugin-release-download.zip")
            val download = downloadFileWithProgress(selected.downloadUrl, localFile)
            if (!download.ok) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        isDownloading = false,
                        error = download.error ?: "下载失败",
                        stage = "下载失败"
                    )
                }
                return@launch
            }

            _ui.update { it.copy(stage = "写入工程模式目录...") }
            val pluginFile = prepareDownloadedPluginForWrite(localFile)
            if (pluginFile == null) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        isDownloading = false,
                        error = "下载包中未找到 plugin-release.zip",
                        stage = "写入失败"
                    )
                }
                return@launch
            }

            val writeOk = writeDownloadedPlugin(pluginFile)
            if (!writeOk) {
                _ui.update {
                    it.copy(
                        isBusy = false,
                        isDownloading = false,
                        error = "写入插件目录失败：$targetPluginPath",
                        stage = "写入失败"
                    )
                }
                return@launch
            }

            val size = queryFileSize(targetPluginPath)
            _ui.update {
                it.copy(
                    isBusy = false,
                    isDownloading = false,
                    success = true,
                    stage = "下载并写入完成",
                    progress = 1f,
                    speedBps = download.avgSpeedBps,
                    pluginExists = size > 0L,
                    pluginSizeBytes = if (size > 0L) size else pluginFile.length(),
                    pluginSourcePath = targetPluginPath
                )
            }
        }
    }

    private fun readProp(key: String): String {
        val result = su.execSu("getprop $key", timeoutMs = 1800)
        if (result.code != 0) return ""
        return result.out.trim().lineSequence().firstOrNull().orEmpty()
    }

    private fun locatePluginFile(): PluginFileMeta? {
        val candidates = listOf(
            "/data/data/com.oplus.engineernetwork/files/plugin-release.zip",
            "/data/user/0/com.oplus.engineernetwork/files/plugin-release.zip",
            "/data/user_de/0/com.oplus.engineernetwork/files/plugin-release.zip"
        )

        candidates.forEach { p ->
            val size = queryFileSize(p)
            if (size > 0L) return PluginFileMeta(path = p, sizeBytes = size)
        }

        val found = findPluginPathByRoot()
        if (found.isNullOrBlank()) return null
        val size = queryFileSize(found)
        if (size <= 0L) return null
        return PluginFileMeta(path = found, sizeBytes = size)
    }

    private fun findPluginPathByRoot(): String? {
        val cmd = "(find /data/user /data/data /data_mirror/data_ce  -maxdepth 8 -type f -name 'plugin-release.zip' 2>/dev/null | head -n 1)"
        val result = su.execSu(cmd, timeoutMs = 12000)
        if (result.code != 0) return null
        return result.out.trim().lineSequence().firstOrNull()?.trim().takeUnless { it.isNullOrBlank() }
    }

    private fun queryFileSize(path: String): Long {
        val cmd = "if [ -f '$path' ]; then (stat -c %s '$path' 2>/dev/null || ls -nl '$path' 2>/dev/null | awk '{print \\$5}' || wc -c < '$path'); else echo -1; fi"
        val result = su.execSu(cmd, timeoutMs = 6000)
        if (result.code != 0) return -1L
        val first = result.out.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        return first.toLongOrNull() ?: -1L
    }

    private fun copyPluginToCache(srcPath: String, target: File): Boolean {
        runCatching { if (target.exists()) target.delete() }

        val cpCmd = "cp '$srcPath' '${target.absolutePath}' && chmod 644 '${target.absolutePath}'"
        val cpResult = su.execSu(cpCmd, timeoutMs = 20000)
        if (cpResult.code == 0 && target.exists() && target.length() > 0L) return true

        val catCmd = "cat '$srcPath' > '${target.absolutePath}' && chmod 644 '${target.absolutePath}'"
        val catResult = su.execSu(catCmd, timeoutMs = 25000)
        return catResult.code == 0 && target.exists() && target.length() > 0L
    }

    private fun buildPluginSharePackage(pluginZip: File, targetZip: File): PackageMetrics {
        return runCatching {
            if (targetZip.exists()) targetZip.delete()
            val payloadRoot = File(appContext.cacheDir, "plugin_share_payload")
            if (payloadRoot.exists()) payloadRoot.deleteRecursively()
            payloadRoot.mkdirs()

            val frameworkParent = File(payloadRoot, "system_ext").apply { mkdirs() }
            val copyFrameworkCmd =
                "cp -a /system_ext/framework '${frameworkParent.absolutePath}/' && chmod -R u+rwX '${payloadRoot.absolutePath}'"
            val frameworkCopy = su.execSu(copyFrameworkCmd, timeoutMs = 45000)
            val frameworkDir = File(frameworkParent, "framework")
            if (frameworkCopy.code != 0 || !frameworkDir.exists()) {
                return PackageMetrics(
                    ok = false,
                    fileSizeBytes = 0L,
                    error = "无法复制 /system_ext/framework：${frameworkCopy.out.take(240)}"
                )
            }

            ZipOutputStream(targetZip.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("plugin-release.zip"))
                pluginZip.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                frameworkDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val rel = frameworkDir.toPath().relativize(file.toPath()).toString()
                            .replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry("system_ext/framework/$rel"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }

            PackageMetrics(ok = targetZip.length() > 0L, fileSizeBytes = targetZip.length(), error = null)
        }.getOrElse { e ->
            PackageMetrics(ok = false, fileSizeBytes = 0L, error = e.message ?: "package_exception")
        }
    }

    private fun downloadFileWithProgress(url: String, target: File): DownloadMetrics {
        runCatching { if (target.exists()) target.delete() }

        val request = Request.Builder().url(url).get().build()
        val startAt = System.currentTimeMillis()
        var downloaded = 0L
        var speed = 0.0
        var lastUiAt = 0L

        return runCatching {
            okHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(300)
                    return DownloadMetrics(
                        ok = false,
                        downloadedBytes = 0L,
                        durationMs = max(1L, System.currentTimeMillis() - startAt),
                        avgSpeedBps = 0.0,
                        error = "下载 HTTP ${resp.code}: $body"
                    )
                }
                val body = resp.body ?: throw IOException("empty_response_body")
                val contentLen = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            val elapsedMs = max(1L, now - startAt)
                            speed = downloaded * 1000.0 / elapsedMs.toDouble()
                            if (now - lastUiAt >= 200L || (contentLen > 0 && downloaded >= contentLen)) {
                                lastUiAt = now
                                _ui.update {
                                    it.copy(
                                        progress = if (contentLen > 0L) {
                                            (downloaded.toDouble() / contentLen.toDouble()).toFloat().coerceIn(0f, 1f)
                                        } else 0f,
                                        speedBps = speed,
                                        stage = "下载插件中..."
                                    )
                                }
                            }
                        }
                        out.flush()
                    }
                }

                val duration = max(1L, System.currentTimeMillis() - startAt)
                val avgSpeed = downloaded * 1000.0 / duration.toDouble()
                DownloadMetrics(
                    ok = downloaded > 0L && target.exists() && target.length() > 0L,
                    downloadedBytes = downloaded,
                    durationMs = duration,
                    avgSpeedBps = avgSpeed,
                    error = null
                )
            }
        }.getOrElse { e ->
            val duration = max(1L, System.currentTimeMillis() - startAt)
            val avgSpeed = downloaded * 1000.0 / duration.toDouble()
            DownloadMetrics(
                ok = false,
                downloadedBytes = downloaded,
                durationMs = duration,
                avgSpeedBps = avgSpeed,
                error = e.message ?: "download_exception"
            )
        }
    }

    private fun writeDownloadedPlugin(srcFile: File): Boolean {
        if (!srcFile.exists() || srcFile.length() <= 0L) return false
        val targetDir = File(targetPluginPath).parent ?: "/data/data/com.oplus.engineernetwork/files"

        val cpCmd = "mkdir -p '$targetDir' && cp '${srcFile.absolutePath}' '$targetPluginPath' && chmod 644 '$targetPluginPath'"
        val cpResult = su.execSu(cpCmd, timeoutMs = 30000)
        if (cpResult.code == 0 && queryFileSize(targetPluginPath) > 0L) return true

        val catCmd = "mkdir -p '$targetDir' && cat '${srcFile.absolutePath}' > '$targetPluginPath' && chmod 644 '$targetPluginPath'"
        val catResult = su.execSu(catCmd, timeoutMs = 30000)
        return catResult.code == 0 && queryFileSize(targetPluginPath) > 0L
    }

    private fun prepareDownloadedPluginForWrite(downloadedFile: File): File? {
        if (!downloadedFile.exists() || downloadedFile.length() <= 0L) return null

        val extracted = File(appContext.cacheDir, "plugin-release-download-extracted.zip")
        runCatching { if (extracted.exists()) extracted.delete() }

        val extractedOk = runCatching {
            ZipInputStream(downloadedFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val normalized = entry.name.replace('\\', '/').trim('/')
                    val isPluginEntry = !entry.isDirectory &&
                            normalized.substringAfterLast('/') == "plugin-release.zip"
                    if (isPluginEntry) {
                        extracted.outputStream().use { out ->
                            zip.copyTo(out)
                        }
                        zip.closeEntry()
                        return@runCatching extracted.exists() && extracted.length() > 0L
                    }
                    zip.closeEntry()
                }
                false
            }
        }.getOrDefault(false)

        return when {
            extractedOk -> extracted
            downloadedFile.name == "plugin-release.zip" -> downloadedFile
            else -> downloadedFile.takeIf { looksLikeStandalonePluginZip(it) }
        }
    }

    private fun looksLikeStandalonePluginZip(file: File): Boolean {
        return runCatching {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                var sawEntry = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    sawEntry = true
                    val normalized = entry.name.replace('\\', '/').trim('/')
                    if (normalized.startsWith("system_ext/")) return@runCatching false
                    if (normalized.substringAfterLast('/') == "plugin-release.zip") return@runCatching false
                    zip.closeEntry()
                }
                sawEntry
            }
        }.getOrDefault(false)
    }

    private fun md5Hex(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun uploadToOss(sign: PluginOssSignResp, file: File): UploadMetrics {
        val startAt = System.currentTimeMillis()
        var latestUploaded = 0L
        var latestSpeed = 0.0
        var lastUiAt = 0L

        val fileBody = file.asRequestBody("application/zip".toMediaType())
        val progressBody = ProgressRequestBody(fileBody) { bytesWritten, contentLen ->
            latestUploaded = bytesWritten
            val now = System.currentTimeMillis()
            val elapsedMs = max(1L, now - startAt)
            latestSpeed = bytesWritten * 1000.0 / elapsedMs.toDouble()
            if (now - lastUiAt >= 200L || bytesWritten >= contentLen) {
                lastUiAt = now
                _ui.update {
                    it.copy(
                        progress = if (contentLen > 0) (bytesWritten.toDouble() / contentLen.toDouble()).toFloat() else 0f,
                        speedBps = latestSpeed,
                        stage = "上传中..."
                    )
                }
            }
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("key", sign.key)
            .addFormDataPart("policy", sign.policy)
            .addFormDataPart("OSSAccessKeyId", sign.OSSAccessKeyId)
            .addFormDataPart("signature", sign.signature)
            .addFormDataPart("success_action_status", "200")
            .apply {
                val token = sign.securityToken?.trim().orEmpty()
                if (token.isNotBlank()) {
                    addFormDataPart("x-oss-security-token", token)
                }
            }
            .addFormDataPart("file", file.name, progressBody)
            .build()

        val request = Request.Builder()
            .url(sign.host)
            .post(multipart)
            .build()

        return runCatching {
            okHttp.newCall(request).execute().use { resp ->
                val duration = max(1L, System.currentTimeMillis() - startAt)
                val avgSpeed = latestUploaded * 1000.0 / duration.toDouble()
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(300)
                    return UploadMetrics(
                        ok = false,
                        uploadedBytes = latestUploaded,
                        durationMs = duration,
                        avgSpeedBps = avgSpeed,
                        etag = null,
                        error = "OSS HTTP ${resp.code}: $body"
                    )
                }
                val etag = resp.header("ETag")?.trim('"')
                UploadMetrics(
                    ok = true,
                    uploadedBytes = latestUploaded,
                    durationMs = duration,
                    avgSpeedBps = avgSpeed,
                    etag = etag,
                    error = null
                )
            }
        }.getOrElse { e ->
            val duration = max(1L, System.currentTimeMillis() - startAt)
            val avgSpeed = latestUploaded * 1000.0 / duration.toDouble()
            UploadMetrics(
                ok = false,
                uploadedBytes = latestUploaded,
                durationMs = duration,
                avgSpeedBps = avgSpeed,
                etag = null,
                error = e.message ?: "upload_exception"
            )
        }
    }

    private suspend fun reportResult(requestId: String, objectKey: String, upload: UploadMetrics) {
        runCatching {
            api.reportPluginUpload(
                PluginUploadReportReq(
                    requestId = requestId,
                    status = if (upload.ok) "UPLOADED" else "FAILED",
                    uploadedBytes = upload.uploadedBytes,
                    durationMs = upload.durationMs,
                    avgSpeedBps = upload.avgSpeedBps,
                    ossEtag = upload.etag,
                    ossObjectKey = objectKey,
                    errorMessage = upload.error
                )
            )
        }
    }

    private fun parseHttpError(code: Int, body: String?): String {
        val raw = body?.trim().orEmpty()
        if (raw.isBlank()) return "HTTP $code"
        val jsonText = runCatching {
            val obj = JSONObject(raw)
            val err = obj.optString("error").trim()
            val msg = obj.optString("message").trim()
            listOfNotNull(
                err.takeIf { it.isNotBlank() },
                msg.takeIf { it.isNotBlank() }
            ).joinToString(": ").ifBlank { raw }
        }.getOrElse { raw }
        return "HTTP $code - ${jsonText.take(320)}"
    }
}

data class PluginShareUiState(
    val hasSu: Boolean? = null,
    val brand: String = "",
    val model: String = "",
    val buildDisplayId: String = "",
    val pluginExists: Boolean? = null,
    val pluginSizeBytes: Long = 0L,
    val packageSizeBytes: Long = 0L,
    val pluginSourcePath: String = "",
    val requestId: String? = null,
    val ossObjectKey: String? = null,
    val isBusy: Boolean = false,
    val isUploading: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val speedBps: Double = 0.0,
    val success: Boolean = false,
    val stage: String = "就绪",
    val error: String? = null,
    val downloadCandidates: List<PluginDownloadCandidateItem> = emptyList(),
    val showDownloadPicker: Boolean = false,
    val selectedDownloadCandidate: PluginDownloadCandidateItem? = null
)

private data class PluginFileMeta(
    val path: String,
    val sizeBytes: Long
)

private data class UploadMetrics(
    val ok: Boolean,
    val uploadedBytes: Long,
    val durationMs: Long,
    val avgSpeedBps: Double,
    val etag: String?,
    val error: String?
)

private data class DownloadMetrics(
    val ok: Boolean,
    val downloadedBytes: Long,
    val durationMs: Long,
    val avgSpeedBps: Double,
    val error: String?
)

private data class PackageMetrics(
    val ok: Boolean,
    val fileSizeBytes: Long,
    val error: String?
)

private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesWritten: Long, contentLength: Long) -> Unit
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val contentLen = contentLength()
        val forwarding = object : ForwardingSink(sink) {
            var bytesWritten = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(bytesWritten, contentLen)
            }
        }
        val buffered = forwarding.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

fun Long.toReadableSize(): String {
    if (this <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        this >= gb -> String.format(Locale.US, "%.2f GB", this / gb)
        this >= mb -> String.format(Locale.US, "%.2f MB", this / mb)
        this >= kb -> String.format(Locale.US, "%.2f KB", this / kb)
        else -> "$this B"
    }
}

fun Double.toReadableSpeed(): String {
    if (this <= 0.0) return "0 B/s"
    return "${this.toLong().toReadableSize()}/s"
}
