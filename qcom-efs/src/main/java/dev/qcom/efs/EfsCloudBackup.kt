package dev.qcom.efs

import android.content.Context
import dev.qcom.efs.features.atomicWrite
import dev.qcom.efs.platform.RootPhone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class EfsCloudBackup(private val context: Context, private val storage: BackupStorage,
    private val readNvImei: suspend () -> String? = { null }) {
    var endpoint: String = ""
    private val prefs = context.getSharedPreferences("efs-vault", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "efs-cloud").apply { mkdirs() }
    private val receipts = File(directory, "receipts").apply { mkdirs() }
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.MINUTES).build()

    private fun credentials(): Pair<String, String> {
        val existingId = prefs.getString("vault_id", null)
        val existingSecret = prefs.getString("vault_secret", null)
        if (existingId != null && existingSecret != null) return existingId to existingSecret
        val id = UUID.randomUUID().toString()
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        check(prefs.edit().putString("vault_id", id).putString("vault_secret", secret).commit()) { "无法保存云备份访问凭据" }
        return id to secret
    }

    suspend fun identity(): BackupIdentity {
        check(withContext(Dispatchers.IO) { QualcommPlatform.isQualcomm() }) { "仅高通设备支持 EFS 备份及恢复" }
        val phone = RootPhone.read(context, "identity")
        val serial = phone.getString("serial")
        val cachedSerial = prefs.getString("hardware_serial", null)
        check(cachedSerial == null || cachedSerial == serial) { "备份访问凭据不属于这部手机" }
        // A lost IMEI must not destroy access to an already registered vault.
        val imei = phone.optString("imei").takeIf { it.matches(Regex("[0-9]{15}")) && it.any { c -> c != '0' } }
            ?: runCatching { readNvImei() }.getOrNull()
            ?: prefs.getString("imei", "").orEmpty()
        return BackupIdentity(phone.getString("model"), imei, serial, phone.getString("firmware"))
            .also { identity ->
                identity.validate(false)
                if (identity.imei.matches(Regex("[0-9]{15}")) && identity.imei.any { it != '0' }) {
                    check(prefs.edit().putString("hardware_serial", serial).putString("imei", identity.imei).commit())
                }
            }
    }

    private fun request(path: String, body: JSONObject, registration: Boolean = false): JSONObject {
        require(endpoint.startsWith("https://") && endpoint.endsWith('/')) { "尚未配置安全的 EFS 云备份服务" }
        val (id, secret) = credentials()
        val request = Request.Builder().url(endpoint + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (!registration) request.header("Authorization", "Bearer $id.$secret")
        http.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { "云备份服务请求失败（HTTP ${response.code}），未解除写入门槛" }
            return JSONObject(text).also { check(it.optBoolean("ok")) { "云备份服务没有确认成功" } }
        }
    }

    private fun receiptFile(raw: RawBackup) = File(receipts, "${fileSha256(raw.manifest)}.json")

    fun hasReceipt(raw: RawBackup): Boolean = runCatching {
        val receipt = JSONObject(receiptFile(raw).readText())
        receipt.getBoolean("verified") && receipt.getString("raw_manifest_sha256") == fileSha256(raw.manifest) &&
            receipt.getString("vault_id") == prefs.getString("vault_id", null)
    }.getOrDefault(false)

    suspend fun ensure(raw: RawBackup, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val device = identity().also { it.validate(true) }
        val (id, secret) = credentials()
        progress("正在验证设备云备份访问权限…")
        request("register", JSONObject().put("vaultId", id).put("secret", secret)
            .put("model", device.model).put("imei", device.imei), registration = true)
        check(prefs.edit().putString("hardware_serial", device.serial).putString("imei", device.imei).commit())
        val receiptFile = receiptFile(raw)
        if (hasReceipt(raw)) {
            val cached = JSONObject(receiptFile.readText())
            val confirmed = request("download", identityJson(device).put("backupId", cached.getString("backupId")))
            check(confirmed.getString("sha256") == cached.getString("sha256") && confirmed.getBoolean("verified"))
            progress("本地镜像及云端备份均已验证")
            return@withContext
        }
        val rawHash = fileSha256(raw.manifest)
        val archive = File(directory, "$rawHash.zip")
        if (archive.exists()) archive.delete()
        progress("正在打包完整分区镜像…")
        BackupBundle.pack(raw, device, archive)
        val publicDir = File(storage.directory, "cloud").apply { mkdirs() }
        val publicArchive = File(publicDir, "efs-$rawHash.zip")
        archive.copyTo(publicArchive, overwrite = true)
        check(fileSha256(publicArchive) == fileSha256(archive)) { "公共归档副本校验失败" }
        val archiveHash = fileSha256(archive)
        progress("正在申请私有 OSS 上传地址…")
        val prepared = request("prepare", identityJson(device).put("size", archive.length()).put("sha256", archiveHash))
        val host = prepared.getString("host").also(::requireOssUrl)
        val fields = prepared.getJSONObject("fields")
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        for (key in fields.keys()) multipart.addFormDataPart(key, fields.getString(key))
        multipart.addFormDataPart("file", "efs-backup.zip", archive.asRequestBody("application/zip".toMediaType()))
        progress("正在上传私有云备份（${archive.length()} 字节）…")
        http.newCall(Request.Builder().url(host).post(multipart.build()).build()).execute().use {
            check(it.isSuccessful) { "OSS 上传失败（HTTP ${it.code}）" }
        }
        progress("后台正在完整读取 OSS 对象并验证 SHA-256…")
        val receipt = request("complete", JSONObject().put("backupId", prepared.getString("backupId")))
        check(receipt.getBoolean("verified") && receipt.getString("sha256") == archiveHash && receipt.getLong("size") == archive.length()) { "云端归档校验失败" }
        receipt.put("raw_manifest_sha256", rawHash).put("vault_id", id)
            .put("model", device.model).put("imei", device.imei).put("hardware_serial", device.serial)
        atomicWrite(receiptFile, receipt.toString(2).toByteArray())
        atomicWrite(File(publicDir, "receipt-$rawHash.json"), receipt.toString(2).toByteArray())
        progress("本地镜像、双份归档和 OSS 云端备份全部校验通过")
    }

    /** Local corruption falls through to authenticated cloud download; this never writes EFS. */
    suspend fun resolveForRecovery(progress: (String) -> Unit): Pair<ExtractedBackup, BackupIdentity> = withContext(Dispatchers.IO) {
        val device = identity()
        val publicCloud = File(storage.directory, "cloud")
        // A supplement can cover newly discovered partitions while the original baseline is kept.
        val preferredHash = storage.recordedCoverageDigest()
        fun preferred(file: File) = preferredHash != null && file.name.contains(preferredHash)
        val currentNames = runCatching { discoverPartitionPlan(SuPartitionSource()).partitions.map { it.name }.toSet() }.getOrDefault(emptySet())
        val requiredNames = storage.recordedCoverageNames() + currentNames
        fun coversCurrent(raw: RawBackup) = JSONObject(raw.manifest.readText()).getJSONObject("partitions")
            .keys().asSequence().toSet().containsAll(requiredNames)
        val candidates = (directory.listFiles().orEmpty().filter { it.extension == "zip" } +
            publicCloud.listFiles().orEmpty().filter { it.extension == "zip" }).sortedWith(compareBy({ !preferred(it) }, { it.lastModified() }))
        for (archive in candidates) {
            val extracted = runCatching { BackupBundle.extract(archive, storage.directory, device) }.getOrNull()
            if (extracted != null) {
                if (coversCurrent(extracted.raw)) { progress("找到通过完整校验且覆盖当前分区的本地归档"); return@withContext extracted to device }
                // This directory was uniquely created by extract(), never an existing user backup.
                val folder = extracted.raw.directory.canonicalFile
                if (folder.parentFile == storage.directory.canonicalFile && folder.name.startsWith("raw-efs-import-")) folder.deleteRecursively()
            }
        }
        // Also use intact raw images when both compressed copies have been removed or damaged.
        val preferredRaw = storage.recordedCoverage()?.directory
        for (folder in storage.directory.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("raw-efs-") }
            .sortedWith(compareBy({ it != preferredRaw }, { it.name }))) {
            val manifest = File(folder, "manifest.json")
            val info = File(folder, "backup-info.json")
            if (!info.isFile) continue
            val metadata = runCatching { JSONObject(info.readText()) }.getOrNull() ?: continue
            if (metadata.optString("model") != device.model || metadata.optString("hardware_serial") != device.serial) continue
            if (!isRawBackupValid(manifest, storage.directory, metadata.optString("device_id"))) continue
            if (fileSha256(manifest) != metadata.optString("manifest_sha256")) continue
            if (!coversCurrent(RawBackup(folder, manifest))) continue
            return@withContext ExtractedBackup(RawBackup(folder, manifest), metadata) to device
        }
        progress("所有本地副本均不可用，正在从私有云下载完整备份…")
        device.validate(true)
        val baseline = receipts.listFiles().orEmpty().sortedWith(compareBy({ !preferred(it) }, { it.lastModified() }))
            .firstNotNullOfOrNull { runCatching { JSONObject(it.readText()).getString("backupId") }.getOrNull() }
        val result = request("download", identityJson(device).apply { baseline?.let { put("backupId", it) } })
        require(result.getBoolean("verified"))
        val expected = result.getString("sha256")
        val bytes = result.getLong("size")
        require(expected.matches(Regex("[0-9a-f]{64}")) && bytes in 1..(2L * 1024 * 1024 * 1024))
        val archive = File(directory, "download-${UUID.randomUUID()}.zip")
        val url = result.getString("downloadUrl").also(::requireOssUrl)
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            check(response.isSuccessful) { "云备份下载失败（HTTP ${response.code}）" }
            val input = response.body?.byteStream() ?: error("云端响应为空")
            archive.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                input.use {
                    while (true) { val n = it.read(buffer); if (n < 0) break; total += n
                        check(total <= bytes); output.write(buffer, 0, n) }
                }
                check(total == bytes) { "云端归档下载不完整" }
            }
        }
        check(fileSha256(archive) == expected) { "云端归档下载校验失败" }
        val extracted = BackupBundle.extract(archive, storage.directory, device)
        check(coversCurrent(extracted.raw)) { "云端备份未覆盖当前分区，请手动导入包含全部分区的备份包" }
        extracted to device
    }

    private fun identityJson(device: BackupIdentity) = JSONObject().put("model", device.model).put("imei", device.imei)
    private fun requireOssUrl(url: String) {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.host?.endsWith(".aliyuncs.com") == true) { "服务端返回了不合法的 OSS 地址" }
    }
}
