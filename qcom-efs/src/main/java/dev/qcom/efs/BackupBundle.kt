package dev.qcom.efs

import dev.qcom.efs.features.atomicWrite
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class BackupIdentity(val model: String, val imei: String, val serial: String, val firmware: String) {
    fun validate(requireImei: Boolean) {
        require(model.isNotBlank() && serial.matches(Regex("[A-Za-z0-9_.-]{4,128}"))) { "无法取得可靠的机型和设备序列号" }
        if (requireImei) require(imei.matches(Regex("[0-9]{15}")) && imei.any { it != '0' }) { "无法读取 IMEI；不能建立新的云备份，请检查电话权限。恢复旧备份可手动选择压缩包" }
    }
}
internal data class ExtractedBackup(val raw: RawBackup, val info: JSONObject)

/** Strict, bounded archive format used for cloud transfers and manual import. */
internal object BackupBundle {
    private const val MAX_IMAGE_TOTAL = MAX_RAW_TOTAL_BYTES

    fun pack(raw: RawBackup, identity: BackupIdentity, target: File): File {
        identity.validate(requireImei = true)
        val manifest = JSONObject(raw.manifest.readText())
        require(manifest.getString("model") == identity.model && manifest.getString("firmware") == identity.firmware) { "分区清单与当前设备或固件不一致" }
        check(isRawBackupValid(raw.manifest, raw.directory.parentFile!!, manifest.getString("device_id"))) { "镜像损坏，无法打包" }
        val info = JSONObject().put("schema", 1).put("kind", "networkhelper-efs-backup")
            .put("model", identity.model).put("imei", identity.imei).put("imei_slot", 0).put("hardware_serial", identity.serial)
            .put("firmware", identity.firmware).put("device_id", manifest.getString("device_id"))
            .put("manifest_sha256", fileSha256(raw.manifest))
        atomicWrite(File(raw.directory, "backup-info.json"), info.toString(2).toByteArray())
        check(target.createNewFile()) { "打包目标已经存在" }
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                zip.setLevel(1)
                zip.putNextEntry(ZipEntry("backup-info.json")); zip.write(info.toString(2).toByteArray()); zip.closeEntry()
                val files = listOf(raw.manifest) + manifest.getJSONObject("partitions").keys().asSequence().sorted().map { File(raw.directory, "$it.bin") }.toList()
                for (file in files) {
                    zip.putNextEntry(ZipEntry("raw/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            return target
        } catch (t: Throwable) { target.delete(); throw t }
    }

    fun extract(archive: File, root: File, identity: BackupIdentity): ExtractedBackup {
        identity.validate(requireImei = false)
        check(archive.length() in 1..(2L * 1024 * 1024 * 1024)) { "备份包大小不合法" }
        val folder = File(root, "raw-efs-import-${UUID.randomUUID()}")
        check(folder.mkdirs()) { "无法创建恢复校验目录" }
        try { ZipFile(archive).use { zip ->
            // Runtime-discovered partitions are bounded by format limits, not a model/name whitelist.
            require(zip.size() in 3..(MAX_RAW_PARTITIONS + 2)) { "备份包文件数量异常" }
            val entries = zip.entries().asSequence().toList()
            require(entries.map { it.name }.distinct().size == entries.size) { "备份包有重复路径" }
            fun json(name: String): JSONObject {
                val entry = zip.getEntry(name) ?: error("备份包缺少 $name")
                require(entry.size in 1..(1024 * 1024)) { "备份清单过大" }
                return zip.getInputStream(entry).use { input ->
                    val bytes = input.readBytesLimited(1024 * 1024)
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
            }
            val info = json("backup-info.json")
            require(info.getInt("schema") == 1 && info.getString("kind") == "networkhelper-efs-backup") { "不支持此备份格式" }
            require(info.getString("model") == identity.model && info.getString("hardware_serial") == identity.serial) { "备份不属于这部手机，拒绝生成恢复包" }
            val manifest = json("raw/manifest.json")
            require(validManifestPartitionMetadata(manifest)) { "分区清单格式不合法" }
            val parts = manifest.getJSONObject("partitions")
            val allowed = setOf("backup-info.json", "raw/manifest.json") + parts.keys().asSequence().map { "raw/$it.bin" }.toSet()
            require(entries.map { it.name }.toSet() == allowed) { "备份包包含额外文件或缺少镜像" }
            var total = 0L
            for (entry in entries.filter { it.name != "backup-info.json" }) {
                require(entry.name == "raw/manifest.json" || (entry.name.startsWith("raw/") && entry.name.endsWith(".bin") &&
                    validPartitionName(entry.name.removePrefix("raw/").removeSuffix(".bin")))) { "不合法的备份路径" }
                require(!entry.isDirectory && entry.size in 0..MAX_IMAGE_TOTAL) { "镜像大小异常" }
                total += entry.size
                require(total <= MAX_IMAGE_TOTAL) { "备份解压大小超限" }
                val destination = File(folder, entry.name.removePrefix("raw/"))
                require(destination.canonicalFile.parentFile == folder.canonicalFile) { "拒绝越界路径" }
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var written = 0L
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            written += count; require(written <= entry.size) { "解压内容超出声明长度" }
                            output.write(buffer, 0, count)
                        }
                        require(written == entry.size) { "镜像内容不完整" }
                    }
                }
            }
            val saved = File(folder, "manifest.json")
            require(fileSha256(saved) == info.getString("manifest_sha256") &&
                manifest.getString("model") == identity.model &&
                manifest.getString("firmware") == info.getString("firmware") &&
                isRawBackupValid(saved, root, info.getString("device_id"))) { "备份清单或分区摘要校验失败" }
            atomicWrite(File(folder, "backup-info.json"), info.toString(2).toByteArray())
            return ExtractedBackup(RawBackup(folder, saved), info)
        }
        } catch (t: Throwable) {
            // Only the unique folder created by this import is eligible for cleanup.
            if (folder.canonicalFile.parentFile == root.canonicalFile && folder.name.startsWith("raw-efs-import-")) folder.deleteRecursively()
            throw t
        }
    }

    fun recoveryPackage(backup: ExtractedBackup, root: File, identity: BackupIdentity): File {
        require(backup.info.getString("hardware_serial") == identity.serial && backup.info.getString("model") == identity.model)
        require(backup.info.getString("firmware") == identity.firmware) { "当前固件与备份不同，请先使用匹配固件后再生成恢复包" }
        val manifest = JSONObject(backup.raw.manifest.readText())
        require(isRawBackupValid(backup.raw.manifest, backup.raw.directory.parentFile!!, backup.info.getString("device_id"))) { "分区镜像或清单校验失败" }
        val parts = manifest.getJSONObject("partitions")
        // Re-resolve physical targets by UUID/name; never replay a saved sdfN device number.
        val source = SuPartitionSource()
        source.checkRoot()
        val targets = recoveryTargets(manifest, source.inventory())
        // If the modem storage service is available, reject an older set missing current stores.
        val plan = runCatching { discoverPartitionPlan(source) }.getOrNull()
        if (plan != null) {
            val devices = targets.values.map { it.realDevice }.toSet()
            require(plan.partitions.all { it.realDevice in devices }) { "备份未覆盖当前发现的全部基带存储分区" }
        }
        val script = recoveryScript(backup.info, manifest)
        val target = File(root, "efs-offline-recovery-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.zip")
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.setLevel(1)
            fun entry(name: String, body: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(body); zip.closeEntry() }
            entry("restore.sh", script.toByteArray())
            entry("backup-info.json", backup.info.toString(2).toByteArray())
            entry("README.txt", ("同一手机离线恢复包。先解压到可写目录，进入有 Root shell 的 Recovery/救援环境。\n" +
                "在该目录执行 sh restore.sh --restore。脚本拒绝 Android、运行中的基带和已挂载目标分区。\n" +
                "先校验全部镜像并另存目标分区当前内容，再写回并逐分区回读。不要混用不同组镜像。\n" +
                "目标固件必须与备份兼容；备份固件：${backup.info.getString("firmware")}\n" +
                "本软件调试未执行分区刷回，运行本脚本会实际覆盖分区。\n").toByteArray())
            val files = listOf(backup.raw.manifest) + parts.keys().asSequence().sorted().map { File(backup.raw.directory, "$it.bin") }.toList()
            for (file in files) {
                zip.putNextEntry(ZipEntry("raw/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        return target
    }

    internal fun recoveryScript(info: JSONObject, manifest: JSONObject): String {
        require(validManifestPartitionMetadata(manifest)) { "分区清单格式不合法" }
        val serial = info.getString("hardware_serial")
        require(serial.matches(Regex("[A-Za-z0-9_.-]{4,128}")))
        val parts = manifest.getJSONObject("partitions")
        val ordered = parts.keys().asSequence().toList().sortedWith(compareBy({ when (it) { "modemst1" -> 8; "modemst2" -> 9; else -> 0 } }, { it }))
        val rows = ordered.joinToString("\n") { name ->
            val p = parts.getJSONObject(name)
            require(validPartitionName(name) && p.getString("sha256").matches(Regex("[0-9a-f]{64}")))
            "$name ${p.getLong("size")} ${p.getString("sha256")} ${p.optString("part_uuid").ifEmpty { "-" }}" }
        return """
            #!/system/bin/sh
            set -eu
            [ "${'$'}{1:-}" = '--restore' ] || { echo 'Usage: sh restore.sh --restore (OFFLINE RECOVERY ONLY)'; exit 2; }
            [ "${'$'}(id -u)" = 0 ] || exit 3
            [ "${'$'}(getprop ro.serialno)" = '$serial' ] || [ "${'$'}(getprop ro.boot.serialno)" = '$serial' ] || { echo 'Wrong device serial'; exit 4; }
            [ "${'$'}(getprop ro.bootmode)" = recovery ] || [ -n "${'$'}(getprop ro.twrp.version)" ] || { echo 'Recovery required'; exit 5; }
            for service in zygote zygote64 rmt_storage rmtfs; do
              [ -z "${'$'}(pidof "${'$'}service" 2>/dev/null || true)" ] || { echo 'Android/modem storage still running'; exit 6; }
            done
            modem_seen=0
            for proc in /sys/class/remoteproc/remoteproc* /sys/bus/msm_subsys/devices/subsys*; do
              [ -r "${'$'}proc/name" ] || continue
              case "${'$'}(cat "${'$'}proc/name")" in *modem*|*mpss*)
                modem_seen=1
                [ "${'$'}(cat "${'$'}proc/state" | tr '[:upper:]' '[:lower:]')" = offline ] || { echo 'Modem must be offline'; exit 7; };; esac
            done
            [ "${'$'}modem_seen" = 1 ] || { echo 'Cannot verify offline modem state'; exit 7; }
            resolve_partition() {
              wanted_name="${'$'}1"; wanted_uuid="${'$'}2"; found=''
              for node in /sys/class/block/*; do
                [ -f "${'$'}node/partition" ] || continue
                candidate="/dev/block/${'$'}{node##*/}"
                [ -b "${'$'}candidate" ] || continue
                if [ "${'$'}wanted_uuid" != '-' ]; then
                  actual_uuid="${'$'}(sed -n 's/^PARTUUID=//p' "${'$'}node/uevent" | tr '[:upper:]' '[:lower:]')"
                  [ "${'$'}actual_uuid" = "${'$'}wanted_uuid" ] || continue
                else
                  actual_name="${'$'}(sed -n 's/^PARTNAME=//p' "${'$'}node/uevent")"
                  matched=0
                  [ "${'$'}actual_name" != "${'$'}wanted_name" ] || matched=1
                  for dir in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name; do
                    [ -b "${'$'}dir/${'$'}wanted_name" ] || continue
                    [ "${'$'}(readlink -f "${'$'}dir/${'$'}wanted_name")" != "${'$'}candidate" ] || matched=1
                  done
                  [ "${'$'}matched" = 1 ] || continue
                fi
                [ -z "${'$'}found" ] || return 1
                found="${'$'}candidate"
              done
              [ -n "${'$'}found" ] || return 1
              printf '%s\n' "${'$'}found"
            }
            cd "${'$'}(dirname "${'$'}0")"
            cat > .restore-plan <<'EFS_PLAN'
            @PARTITION_ROWS@
            EFS_PLAN
            : > .restore-targets
            seen=''
            while read -r name size hash uuid; do
              dev="${'$'}(resolve_partition "${'$'}name" "${'$'}uuid")" || { echo 'Partition identity missing or ambiguous'; exit 8; }
              [ -b "${'$'}dev" ] && [ "${'$'}(blockdev --getsize64 "${'$'}dev")" = "${'$'}size" ] || exit 8
              case " ${'$'}seen " in *" ${'$'}dev "*) echo 'Duplicate target'; exit 8;; esac
              seen="${'$'}seen ${'$'}dev"
              real="${'$'}(readlink -f "${'$'}dev")"
              while read -r mounted rest; do [ "${'$'}(readlink -f "${'$'}mounted" 2>/dev/null || true)" != "${'$'}real" ] || { echo 'Target mounted'; exit 9; }; done < /proc/mounts
              [ "${'$'}(wc -c < "raw/${'$'}name.bin" | tr -d ' ')" = "${'$'}size" ] || exit 10
              echo "${'$'}hash  raw/${'$'}name.bin" | sha256sum -c - || exit 11
              printf '%s %s %s %s\n' "${'$'}name" "${'$'}dev" "${'$'}size" "${'$'}hash" >> .restore-targets
            done < .restore-plan
            rollback="pre-restore-${'$'}(date +%Y%m%d-%H%M%S)"
            mkdir "${'$'}rollback"
            while read -r name dev size hash; do
              original="${'$'}(sha256sum "${'$'}dev" | cut -d ' ' -f 1)"
              dd if="${'$'}dev" of="${'$'}rollback/${'$'}name.bin" bs=4096 || exit 12
              [ "${'$'}(wc -c < "${'$'}rollback/${'$'}name.bin" | tr -d ' ')" = "${'$'}size" ] || exit 13
              [ "${'$'}(sha256sum "${'$'}rollback/${'$'}name.bin" | cut -d ' ' -f 1)" = "${'$'}original" ] || exit 13
              [ "${'$'}(sha256sum "${'$'}dev" | cut -d ' ' -f 1)" = "${'$'}original" ] || exit 13
            done < .restore-targets
            sync
            while read -r name dev size hash; do
              dd if="raw/${'$'}name.bin" of="${'$'}dev" bs=4096 conv=fsync || exit 14
              [ "${'$'}(sha256sum "${'$'}dev" | cut -d ' ' -f 1)" = "${'$'}hash" ] || exit 15
            done < .restore-targets
            sync
            echo 'Restore and readback verification completed. Keep the rollback images.'
        """.trimIndent().replace("@PARTITION_ROWS@", rows) + "\n"
    }
}

private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= max); out.write(buffer, 0, n) }
    return out.toByteArray()
}
