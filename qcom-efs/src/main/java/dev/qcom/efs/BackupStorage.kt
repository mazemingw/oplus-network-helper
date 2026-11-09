package dev.qcom.efs

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import dev.qcom.efs.features.FeatureBackupStore
import dev.qcom.efs.features.atomicWrite
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

data class SavedBackup(val file: File, val complete: Boolean, val summary: PullSummary?)

class BackupStorage(private val context: Context, directoryOverride: File? = null) {
    val directory: File = directoryOverride ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "EFS-BACKUP")
    val deviceId: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?: error("无法识别当前设备，已阻止使用其他设备的备份")
    private val marker = File(context.filesDir, "initial-efs-backup.json")

    fun featureStore(log: (String) -> Unit) = FeatureBackupStore(
        File(context.filesDir, "feature-backups"), File(directory, "features/$deviceId"), deviceId, log,
    )

    fun hasInitialBackup(): Boolean = hasStorageAccess(context) && isInitialBackupValid(marker, directory, deviceId)

    internal fun recordedBaseline(): RawBackup? = runCatching {
        val json = JSONObject(marker.readText())
        check(json.getInt("schema") == 3 && json.getString("device_id") == deviceId)
        val manifest = File(json.getString("raw_manifest")).canonicalFile
        check(manifest.name == "manifest.json" && manifest.parentFile!!.parentFile == directory.canonicalFile)
        RawBackup(manifest.parentFile!!, manifest)
    }.getOrNull()

    internal fun recordedCoverage(): RawBackup? = runCatching {
        val json = JSONObject(marker.readText())
        val path = json.optString("coverage_manifest").ifEmpty { json.getString("raw_manifest") }
        val manifest = File(path).canonicalFile
        check(manifest.name == "manifest.json" && manifest.parentFile!!.parentFile == directory.canonicalFile)
        RawBackup(manifest.parentFile!!, manifest)
    }.getOrNull()

    internal fun recordedCoverageDigest(): String? = runCatching {
        val json = JSONObject(marker.readText())
        json.optString("coverage_manifest_sha256").ifEmpty { json.getString("raw_manifest_sha256") }
            .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }.getOrNull()

    internal fun recordedCoverageNames(): Set<String> = runCatching {
        val names = JSONObject(marker.readText()).getJSONArray("coverage_partition_names")
        check(names.length() in 1..MAX_RAW_PARTITIONS)
        (0 until names.length()).map { names.getString(it).also { name -> require(validPartitionName(name)) } }.toSet()
    }.getOrDefault(emptySet())

    internal fun acceptRawBaseline(backup: RawBackup) {
        require(hasStorageAccess(context)) { "请先授予所有文件访问权限" }
        check(isRawBackupValid(backup.manifest, directory, deviceId)) { "分区备份校验失败，写入保持锁定" }
        // Preserve the original baseline, while tracking coverage added after a topology change.
        if (isInitialBackupValid(marker, directory, deviceId, checkCoverage = false)) {
            val json = JSONObject(marker.readText()).put("coverage_manifest", backup.manifest.canonicalPath)
                .put("coverage_manifest_sha256", fileSha256(backup.manifest))
                .put("coverage_partition_names", JSONArray(JSONObject(backup.manifest.readText()).getJSONObject("partitions").keys().asSequence().toList()))
            atomicWrite(marker, json.toString(2).toByteArray(Charsets.UTF_8))
            return
        }
        val json = JSONObject().apply {
            put("schema", 3); put("backup_kind", "raw-partitions"); put("device_id", deviceId)
            put("complete", true); put("created_at", System.currentTimeMillis())
            put("raw_manifest", backup.manifest.canonicalPath)
            put("raw_manifest_sha256", fileSha256(backup.manifest))
            put("coverage_partition_names", JSONArray(JSONObject(backup.manifest.readText()).getJSONObject("partitions").keys().asSequence().toList()))
        }
        atomicWrite(marker, json.toString(2).toByteArray(Charsets.UTF_8))
    }

    internal fun saveZip(source: File, path: String, summary: PullSummary): SavedBackup {
        require(hasStorageAccess(context)) { "请先授予所有文件访问权限" }
        verifyBackupZip(source, summary)
        val complete = summary.errors == 0
        val stamp = backupStamp()
        val name = if (path == "/") "efs-full" else "efs-folder"
        val target = File(directory, "$name-$stamp${if (complete) "" else "-partial"}.zip")
        copyAtomic(source, target)
        val json = JSONObject().apply {
            put("schema", 2); put("device_id", deviceId); put("model", Build.MODEL)
            put("firmware", Build.FINGERPRINT); put("created_at", System.currentTimeMillis())
            put("efs_path", path); put("archive", target.canonicalPath)
            put("format", "zip"); put("complete", complete); put("backup_kind", "efs-file-export")
            put("files", summary.files); put("directories", summary.dirs); put("links", summary.links)
            put("bytes", summary.bytes); put("errors", summary.errors)
            put("error_list", JSONArray(summary.errorList)); put("sha256", fileSha256(target))
            put("file_export_complete", summary.errors == 0)
            put("scope", "EFS file export; not a raw partition image or an atomic modem snapshot")
        }
        val manifest = File(directory, "${target.nameWithoutExtension}.json")
        atomicWrite(manifest, json.toString(2).toByteArray(Charsets.UTF_8))
        return SavedBackup(target, complete, summary)
    }

    fun saveTar(source: File): SavedBackup {
        require(hasStorageAccess(context)) { "请先授予所有文件访问权限" }
        require(source.isFile && source.length() > 0) { "基带返回的 TAR 备份为空" }
        val target = File(directory, "efs-modem-${backupStamp()}.tar")
        copyAtomic(source, target)
        return SavedBackup(target, true, null)
    }
}

fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun isUnsupportedTar(message: String): Boolean =
    message.contains("bad command", ignoreCase = true) ||
        message.contains("not supported", ignoreCase = true) ||
        message.contains("unsupported", ignoreCase = true) ||
        message.contains("ENOSYS") || message.contains("EOPNOTSUPP")

internal fun isInitialBackupValid(marker: File, directory: File, deviceId: String, checkCoverage: Boolean = true): Boolean = runCatching {
    if (!marker.isFile || marker.length() > 1024 * 1024) return false
    val json = JSONObject(marker.readText(Charsets.UTF_8))
    json.getInt("schema") == 3 && json.getString("backup_kind") == "raw-partitions" &&
        json.getString("device_id") == deviceId && json.getBoolean("complete") &&
        File(json.getString("raw_manifest")).let { raw ->
            fileSha256(raw) == json.getString("raw_manifest_sha256") && isRawBackupValid(raw, directory, deviceId)
        } && (!checkCoverage || !json.has("coverage_manifest") || File(json.getString("coverage_manifest")).let { raw ->
            fileSha256(raw) == json.getString("coverage_manifest_sha256") && isRawBackupValid(raw, directory, deviceId)
        })
}.getOrDefault(false)

internal fun verifyBackupZip(source: File, summary: PullSummary) {
    // ZipInputStream checks payload CRCs; ZipFile also checks the central directory.
    ZipFile(source).use { zip ->
        require(zip.entries().asSequence().count { !it.isDirectory } == summary.files + summary.links) {
            "备份压缩包的中央目录与文件数量不匹配"
        }
    }
    var entries = 0
    ZipInputStream(source.inputStream().buffered()).use { zip ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val entry = zip.nextEntry ?: break
            while (zip.read(buffer) != -1) Unit
            zip.closeEntry()
            if (!entry.isDirectory) entries++
        }
    }
    require(entries == summary.files + summary.links && summary.files > 0) { "备份压缩包为空或文件数量不完整" }
}

private fun backupStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())

internal fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private fun copyAtomic(source: File, target: File) {
    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建公共备份目录" }
    val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temp).use { output ->
            source.inputStream().buffered().use { it.copyTo(output) }
            output.fd.sync()
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath())
        }
    } finally { temp.delete() }
}

/** Always-on app log, plus a verbatim native log for diagnosing TAR/DIAG failures. */
class EfsLogger(private val context: Context, private val publicRoot: File) {
    private val session = backupStamp()
    private val local = File(context.filesDir, "logs/efs-$session.log")
    private val public = File(publicRoot, "logs/efs-$session.log")
    private var nativeIndex = 0
    private val lastMessages = LinkedHashMap<String, Long>()
    private val native: File get() = File(publicRoot, "logs/daemon-$session-$nativeIndex.log")

    @Synchronized fun beginNativeSession() {
        captureNative()
        nativeIndex++
    }

    @Synchronized fun record(message: String) {
        DiagnosticEvents.record("efs", message)
        val now = System.currentTimeMillis()
        if (now - (lastMessages[message] ?: 0L) < 10_000) return
        lastMessages[message] = now
        while (lastMessages.size > 128) lastMessages.remove(lastMessages.keys.first())
        Log.i("QcomEfs", message)
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())} $message\n"
        runCatching {
            local.parentFile!!.mkdirs()
            if (local.length() >= 256 * 1024) writeTail(local, local, 128 * 1024)
            local.appendText(line, Charsets.UTF_8)
            if (hasStorageAccess(context)) {
                public.parentFile!!.mkdirs()
                // Includes early startup lines written before storage permission was granted.
                local.copyTo(public, overwrite = true)
                prune(public.parentFile!!)
            }
            prune(local.parentFile!!)
        }.onFailure { Log.w("QcomEfs", "日志文件写入失败", it) }
    }

    @Synchronized fun captureNative() {
        if (!hasStorageAccess(context)) return
        runCatching {
            val source = RootDaemon.logFile(context)
            if (source.isFile) {
                native.parentFile!!.mkdirs()
                writeTail(source, native, 256 * 1024)
                prune(native.parentFile!!)
            }
        }.onFailure { Log.w("QcomEfs", "后台日志保存失败", it) }
    }

    fun tail(): List<String> = runCatching { local.readLines(Charsets.UTF_8).takeLast(100) }.getOrDefault(emptyList())

    private fun writeTail(source: File, target: File, maximum: Int) {
        val bytes = java.io.RandomAccessFile(source, "r").use { input ->
            input.seek((input.length() - maximum).coerceAtLeast(0))
            ByteArray((input.length() - input.filePointer).toInt()).also { input.readFully(it) }
        }
        target.writeBytes(bytes)
    }

    private fun prune(directory: File) {
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "log" &&
            (it.name.startsWith("efs-") || it.name.startsWith("daemon-")) }.sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }; var count = files.size
        for (file in files) {
            if (count <= 16 && bytes <= 4 * 1024 * 1024) break
            bytes -= file.length(); count--; file.delete()
        }
    }
}
