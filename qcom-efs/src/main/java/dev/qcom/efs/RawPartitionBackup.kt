package dev.qcom.efs

import dev.qcom.efs.features.atomicWrite
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Version-1 compatibility and discovery hints only; runtime discovery may add any physical store.
internal val REQUIRED_EFS_PARTITIONS = listOf("modemst1", "modemst2", "fsg", "fsc", "persist")
internal val OPTIONAL_EFS_PARTITIONS = listOf(
    "fs_bkup", "mdm1oemnvbktmp", "oem_stanvbk", "oem_dycnvbk", "study", "tunning",
    "oplusdycnvbk", "oplusstanvbk_a", "oplusstanvbk_b",
)
internal val EFS_PARTITION_NAMES = REQUIRED_EFS_PARTITIONS + OPTIONAL_EFS_PARTITIONS
private val digestPattern = Regex("[0-9a-f]{64}")

internal data class RawPartition(val name: String, val source: String, val realDevice: String, val size: Long,
    val uuid: String = "", val aliases: Set<String> = setOf(name))
internal data class RawBackup(val directory: File, val manifest: File)

/** Only physical partition metadata and reads are exposed to acquisition. */
internal interface PartitionSource {
    fun checkRoot()
    fun inventory(): List<RawPartition>
    fun discover(name: String): RawPartition? = inventory().filter { it.name == name || name in it.aliases }.singleOrNull()
    fun remoteStorageDevices(): Set<String>
    fun sha256(partition: RawPartition): String
    fun copyTo(partition: RawPartition, target: File)
}

internal class SuPartitionSource : PartitionSource {
    override fun checkRoot() { check(text("id -u") == "0") { "分区备份需要 Root 权限" } }

    override fun inventory(): List<RawPartition> = parsePartitionInventory(text(partitionInventoryScript))

    override fun remoteStorageDevices(): Set<String> {
        // Look at the actual open block devices rather than assuming a model's partition names.
        val script = """
            for pid in ${'$'}(pidof rmt_storage rmtfs); do
              for fd in /proc/${'$'}pid/fd/*; do
                [ -b "${'$'}fd" ] || continue
                readlink -f "${'$'}fd" || exit 1
              done
            done
        """.trimIndent()
        return text(script).lines().filter { it.isNotBlank() }.toSet().also { paths ->
            check(paths.isNotEmpty() && paths.all { it.startsWith("/dev/block/") }) {
                "无法核实 rmt_storage/rmtfs 实际使用的分区，写入保持锁定"
            }
        }
    }

    override fun sha256(partition: RawPartition): String =
        text("sha256sum '${partition.source}'").substringBefore(' ').also {
            check(digestPattern.matches(it)) { "源分区校验失败：${partition.name}" }
        }

    override fun copyTo(partition: RawPartition, target: File) {
        check(target.createNewFile()) { "备份文件已存在：${target.name}" }
        FileOutputStream(target).use { output ->
            var total = 0L
            runRoot("cat '${partition.source}'") { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= partition.size) { "分区镜像超出预期大小：${partition.name}" }
                    output.write(buffer, 0, count)
                }
            }
            output.fd.sync()
            check(total == partition.size) { "分区镜像读取不完整：${partition.name}（$total/${partition.size}）" }
        }
    }

    private fun text(script: String): String {
        val output = ByteArrayOutputStream()
        runRoot(script) { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 1024 * 1024) { "Root 命令返回数据异常" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString("UTF-8").trimEnd('\r', '\n')
    }

    private fun runRoot(script: String, consume: (java.io.InputStream) -> Unit) {
        val process = ProcessBuilder("su", "-c", script).start()
        process.outputStream.close()
        val errors = ByteArrayOutputStream()
        val errorReader = Thread {
            runCatching {
                process.errorStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (errors.size() < 16 * 1024) errors.write(buffer, 0, count)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val timer = Executors.newSingleThreadScheduledExecutor()
        val timeout = timer.schedule({ process.destroyForcibly() }, 5, TimeUnit.MINUTES)
        try {
            process.inputStream.use(consume)
            check(process.waitFor(10, TimeUnit.SECONDS)) { "分区读取进程未退出" }
            errorReader.join(1000)
            check(process.exitValue() == 0) { "Root 读取失败或超时：${errors.toString("UTF-8").take(1000)}" }
        } finally {
            timeout.cancel(false)
            timer.shutdownNow()
            process.destroyForcibly()
            process.errorStream.close()
            errorReader.join(1000)
        }
    }
}

/** Live, read-only acquisition. It never changes modem mode or writes any block device. */
internal class RawPartitionBackup(private val source: PartitionSource = SuPartitionSource()) {
    fun capture(root: File, deviceId: String, model: String, firmware: String,
        onDiscovered: (List<String>) -> Unit = {}, progress: (String) -> Unit): RawBackup {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val folder = File(root, "raw-efs-$stamp-${UUID.randomUUID().toString().take(8)}")
        check(folder.mkdirs()) { "无法创建分区备份目录" }
        val manifest = File(folder, "manifest.json")
        val json = JSONObject().apply {
            put("schema", 2); put("kind", "qualcomm-efs-nv-raw-partition-acquisition")
            put("discovery_method", RUNTIME_DISCOVERY)
            put("device_id", deviceId); put("model", model); put("firmware", firmware)
            put("created_at", System.currentTimeMillis()); put("capture_mode", "live")
            put("complete_for_listed_partitions", false); put("all_logical_efs_paths_verified", false)
            put("restore_tested", false); put("partitions", JSONObject())
            put("consistency", "Source hashes before and after the entire group must match saved images; not an offline atomic snapshot")
        }
        try {
            source.checkRoot()
            progress("正在发现实际基带存储分区及 A/B 槽位…")
            val plan = discoverPartitionPlan(source)
            val parts = plan.partitions
            json.put("planned_partitions", JSONArray(parts.map { it.name }))
            onDiscovered(parts.map { it.name })
            progress("已发现 ${parts.size} 个分区：${parts.joinToString { it.name }}")
            check(folder.usableSpace >= parts.sumOf { it.size } + 32L * 1024 * 1024) { "存储空间不足，无法保存分区镜像" }
            val selected = parts.map { it.realDevice }.toSet()
            fun coveredDevices(): Set<String> = source.remoteStorageDevices().also { actual ->
                check(selected.containsAll(actual)) { "发现尚未纳入备份的基带存储设备：${actual - selected}" }
            }
            json.put("rmt_storage_devices_before", JSONArray(plan.remoteDevices.sorted()))
            progress("正在校验源分区（采集前）…")
            val before = parts.associate { it.name to source.sha256(it) }
            for ((index, part) in parts.withIndex()) {
                progress("正在只读备份分区 ${index + 1}/${parts.size}：${part.name}（${part.size} 字节）")
                val image = File(folder, "${part.name}.bin")
                source.copyTo(part, image)
                check(image.length() == part.size) { "镜像长度不匹配：${part.name}" }
                val hash = fileSha256(image)
                check(hash == before[part.name]) { "采集时源分区发生变化：${part.name}，请重试" }
                json.getJSONObject("partitions").put(part.name, JSONObject().apply {
                    put("source", part.source); put("real_device", part.realDevice); put("size", part.size)
                    put("part_uuid", part.uuid); put("aliases", JSONArray(part.aliases.sorted()))
                    put("discovery_reasons", JSONArray(plan.reasons[part.name].orEmpty().sorted()))
                    put("file", image.name); put("sha256", hash); put("source_sha256_before", before[part.name])
                    put("stable_and_verified", false)
                })
            }
            progress("正在校验源分区（采集后）及实际覆盖范围…")
            val afterPlan = discoverPartitionPlan(source)
            check(afterPlan.partitions == parts) { "备份期间基带分区清单发生变化，请重试" }
            for (part in parts) {
                val after = source.sha256(part)
                check(after == before[part.name]) { "备份期间源分区发生变化：${part.name}，请重试" }
                json.getJSONObject("partitions").getJSONObject(part.name).apply {
                    put("source_sha256_after", after); put("stable_and_verified", true)
                }
            }
            json.put("rmt_storage_devices_after", JSONArray(coveredDevices().sorted()))
            json.put("verified_bytes", parts.sumOf { it.size })
            json.put("complete_for_listed_partitions", true)
        } catch (t: Throwable) {
            json.put("error", t.message ?: t.javaClass.simpleName)
            throw t
        } finally {
            atomicWrite(manifest, json.toString(2).toByteArray(Charsets.UTF_8))
        }
        check(isRawBackupValid(manifest, root, deviceId)) { "分区备份保存后的复核失败" }
        progress("分区备份已校验：${folder.absolutePath}")
        return RawBackup(folder, manifest)
    }
}

internal fun isRawBackupValid(manifest: File, root: File, deviceId: String): Boolean = runCatching {
    if (!manifest.isFile || manifest.length() > 1024 * 1024) return false
    val folder = manifest.canonicalFile.parentFile!!
    if (folder.parentFile != root.canonicalFile || !folder.name.startsWith("raw-efs-")) return false
    val json = JSONObject(manifest.readText(Charsets.UTF_8))
    if (!validManifestPartitionMetadata(json) || json.getString("device_id") != deviceId ||
        !json.getBoolean("complete_for_listed_partitions")) return false
    val parts = json.getJSONObject("partitions")
    for (name in parts.keys()) {
        val part = parts.getJSONObject(name)
        val image = File(folder, part.getString("file")).canonicalFile
        val hash = part.getString("sha256")
        if (image.parentFile != folder || image.name != "$name.bin" || !image.isFile ||
            image.length() != part.getLong("size") || image.length() <= 0 ||
            !digestPattern.matches(hash) || !part.getBoolean("stable_and_verified") ||
            hash != part.getString("source_sha256_before") || hash != part.getString("source_sha256_after") ||
            fileSha256(image) != hash) return false
    }
    true
}.getOrDefault(false)
