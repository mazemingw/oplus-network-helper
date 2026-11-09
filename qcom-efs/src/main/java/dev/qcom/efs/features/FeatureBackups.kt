package dev.qcom.efs.features

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** null means the item did not exist; an empty string means an existing empty item. */
data class OriginalItem(val path: String, val hex: String?)

data class FeatureBackup(
    val featureId: String,
    val slot: Int,
    val deviceId: String,
    val createdAt: Long,
    val items: List<OriginalItem>,
    val restored: Boolean = false,
) {
    fun validate(feature: FeatureDef, selectedSlot: Int, currentDevice: String) {
        require(featureId == feature.id && slot == selectedSlot && deviceId == currentDevice) {
            "原值备份与当前设备、SIM 卡槽或功能不匹配"
        }
        require(items.map { it.path } == feature.writes.map { slotPath(it.path, selectedSlot) }) {
            "原值备份中的 EFS 路径不匹配"
        }
        require(createdAt > 0) { "原值备份的时间无效" }
        for (item in items) item.hex?.let { hex ->
            require(hex.length <= 1024 * 1024 && hex.length % 2 == 0 && hex.all { it in "0123456789abcdef" }) {
                "原值备份中的字节数据无效：${item.path}"
            }
        }
    }

    private fun checksum(): String = sha256(buildString {
        append("$featureId\n$slot\n$deviceId\n$createdAt\n$restored\n")
        items.forEach { append(it.path).append('\t').append(it.hex ?: "ABSENT").append('\n') }
    }.toByteArray(Charsets.UTF_8))

    fun encode(): String = JSONObject().apply {
        put("schema", 1)
        put("feature", featureId)
        put("sim_slot", slot)
        put("device_id", deviceId)
        put("created_at", createdAt)
        put("restored", restored)
        put("items", JSONArray().apply {
            items.forEach { item -> put(JSONObject().apply {
                put("path", item.path)
                put("existed", item.hex != null)
                put("hex", item.hex ?: JSONObject.NULL)
            }) }
        })
        put("sha256", checksum())
    }.toString(2)

    companion object {
        fun decode(text: String): FeatureBackup {
            val obj = JSONObject(text)
            require(obj.getInt("schema") == 1) { "不支持的原值备份格式" }
            val array = obj.getJSONArray("items")
            val backup = FeatureBackup(
                obj.getString("feature"), obj.getInt("sim_slot"), obj.getString("device_id"),
                obj.getLong("created_at"),
                List(array.length()) { i ->
                    val item = array.getJSONObject(i)
                    OriginalItem(item.getString("path"), if (item.getBoolean("existed")) item.getString("hex") else null)
                },
                obj.getBoolean("restored"),
            )
            require(backup.checksum() == obj.getString("sha256")) { "原值备份校验失败，文件可能损坏" }
            return backup
        }
    }
}

/** Private copy wins. A missing or damaged private copy falls back to the public copy. */
class FeatureBackupStore(
    private val privateDir: File,
    private val publicDir: File,
    private val deviceId: String,
    private val log: (String) -> Unit = {},
) {
    private fun name(feature: FeatureDef, slot: Int) = "${feature.id}-SIM$slot.json"

    fun load(feature: FeatureDef, slot: Int): FeatureBackup? {
        val local = File(privateDir, name(feature, slot))
        val external = File(publicDir, name(feature, slot))
        var failure: Throwable? = null
        for (file in listOf(local, external)) {
            if (!file.exists()) continue
            try {
                require(file.length() <= 8L * 1024 * 1024) { "原值备份文件过大" }
                val snapshot = FeatureBackup.decode(file.readText(Charsets.UTF_8))
                snapshot.validate(feature, slot, deviceId)
                log("读取功能原值备份：${file.absolutePath}")
                if (file == external) atomicWrite(local, snapshot.encode().toByteArray(Charsets.UTF_8))
                return snapshot
            } catch (t: Exception) {
                failure = t
                log("原值备份读取失败：${file.absolutePath}：${t.message}")
            }
        }
        if (failure != null) throw IllegalStateException("现有原值备份不可用，已阻止覆盖：${failure.message}", failure)
        return null
    }

    /** Both copies must be safely persisted before the first modem write is allowed. */
    fun save(backup: FeatureBackup, feature: FeatureDef) {
        backup.validate(feature, backup.slot, deviceId)
        val bytes = backup.encode().toByteArray(Charsets.UTF_8)
        for (directory in listOf(privateDir, publicDir)) {
            val file = File(directory, name(feature, backup.slot))
            if (file.isFile) {
                val previous = runCatching {
                    require(file.length() <= 8L * 1024 * 1024)
                    FeatureBackup.decode(file.readText(Charsets.UTF_8)).also { it.validate(feature, backup.slot, deviceId) }
                }.getOrElse {
                    val quarantine = File(directory, "history/${file.name}.${System.currentTimeMillis()}.corrupt")
                    check(quarantine.parentFile!!.isDirectory || quarantine.parentFile!!.mkdirs())
                    Files.move(file.toPath(), quarantine.toPath())
                    log("损坏副本已保留为 $quarantine，正在用有效原值副本修复")
                    null
                }
                if (previous != null && previous.createdAt != backup.createdAt) {
                    atomicWrite(File(directory, "history/${feature.id}-SIM${backup.slot}-${previous.createdAt}.json"),
                        previous.encode().toByteArray(Charsets.UTF_8))
                }
            }
            atomicWrite(file, bytes)
            log("原值备份已保存：${file.absolutePath}")
        }
    }
}

class FeatureRestorer(
    private val access: FeatureItemAccess,
    private val delete: suspend (String) -> Unit,
    private val sync: suspend () -> Unit,
) {
    suspend fun capture(feature: FeatureDef, slot: Int, deviceId: String): FeatureBackup = FeatureBackup(
        feature.id, slot, deviceId, System.currentTimeMillis(),
        feature.writes.map { write ->
            val path = slotPath(write.path, slot)
            OriginalItem(path, access.read(path)?.joinToString("") { it.toString(16).padStart(2, '0') })
        },
    )

    suspend fun restore(backup: FeatureBackup, feature: FeatureDef, deviceId: String, progress: (String) -> Unit = {}) {
        backup.validate(feature, backup.slot, deviceId)
        for (item in backup.items) {
            progress(item.path)
            if (item.hex == null) delete(item.path)
            else access.write(item.path, item.hex.chunked(2).map { it.toInt(16) })
        }
        sync()
        for (item in backup.items) {
            val actual = access.read(item.path)?.joinToString("") { it.toString(16).padStart(2, '0') }
            check(actual == item.hex) { "恢复后回读校验失败：${item.path}；原值备份已保留，可重试" }
        }
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

fun atomicWrite(target: File, bytes: ByteArray) {
    val directory = target.absoluteFile.parentFile!!
    check(directory.isDirectory || directory.mkdirs()) { "无法创建备份目录：$directory" }
    val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}
