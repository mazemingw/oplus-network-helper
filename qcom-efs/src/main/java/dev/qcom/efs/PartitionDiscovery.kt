package dev.qcom.efs

import org.json.JSONObject
import java.util.Locale

internal const val MAX_RAW_PARTITIONS = 128
internal const val MAX_RAW_IMAGE_BYTES = 2L * 1024 * 1024 * 1024
internal const val MAX_RAW_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
internal const val RUNTIME_DISCOVERY = "sysfs-rmt-storage-v1"
internal fun validPartitionName(name: String) = name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) && name != "." && name != ".."
internal fun validBlockDevice(path: String) = path.matches(Regex("/dev/block/[A-Za-z0-9_-]{1,96}"))
internal fun validPartitionUuid(uuid: String) = uuid.isEmpty() || uuid.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,63}"))

/** Physical partitions only. No partition data, IMEI or user files are read by this script. */
internal val partitionInventoryScript = """
    for node in /sys/class/block/*; do
      [ -f "${'$'}node/partition" ] || continue
      dev="/dev/block/${'$'}{node##*/}"
      [ -b "${'$'}dev" ] || continue
      sectors=${'$'}(cat "${'$'}node/size") || exit 1
      label=${'$'}(sed -n 's/^PARTNAME=//p' "${'$'}node/uevent") || exit 1
      uuid=${'$'}(sed -n 's/^PARTUUID=//p' "${'$'}node/uevent") || exit 1
      printf 'P\t%s\t%s\t%s\t%s\n' "${'$'}dev" "${'$'}sectors" "${'$'}label" "${'$'}uuid"
    done
    for dir in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name; do
      [ -d "${'$'}dir" ] || continue
      for link in "${'$'}dir"/*; do
        [ -b "${'$'}link" ] || continue
        target=${'$'}(readlink -f "${'$'}link") || exit 1
        printf 'A\t%s\t%s\n' "${'$'}link" "${'$'}target"
      done
    done
""".trimIndent()

/** by-name aliases are hints; sysfs proves that every source is a physical partition. */
internal fun parsePartitionInventory(text: String): List<RawPartition> {
    val rows = text.lineSequence().filter { it.isNotBlank() }.map { it.split('\t') }.toList()
    require(rows.size <= 8192) { "分区映射条目过多" }
    val aliases = mutableMapOf<String, MutableSet<String>>()
    for (row in rows.filter { it.firstOrNull() == "A" }) {
        require(row.size == 3 && validBlockDevice(row[2])) { "分区别名映射异常" }
        val name = row[1].substringAfterLast('/')
        if (validPartitionName(name)) aliases.getOrPut(row[2]) { mutableSetOf() }.add(name)
    }
    val partitions = rows.filter { it.firstOrNull() == "P" }.map { row ->
        require(row.size == 5 && validBlockDevice(row[1]) && validPartitionUuid(row[4])) { "内核分区信息异常" }
        val sectors = row[2].toLong()
        require(sectors in 1..(Long.MAX_VALUE / 512)) { "内核分区大小异常" }
        val uuid = row[4].lowercase(Locale.ROOT)
        val names = aliases[row[1]].orEmpty()
        val name = row[3].takeIf(::validPartitionName) ?: names.sorted().firstOrNull()
            ?: uuid.takeIf { it.isNotEmpty() }?.let { "partition_${it.replace('-', '_')}" }
            ?: error("分区缺少可靠名称和 UUID：${row[1]}")
        RawPartition(name, row[1], row[1], sectors * 512, uuid, names + name)
    }
    require(partitions.isNotEmpty() && partitions.size <= 2048 && partitions.map { it.realDevice }.distinct().size == partitions.size) { "物理分区清单为空或有歧义" }
    return partitions
}

internal data class PartitionPlan(val partitions: List<RawPartition>, val remoteDevices: Set<String>, val reasons: Map<String, Set<String>>)

/** Names are discovery hints, never a whitelist. An unfamiliar daemon-backed partition is included. */
internal fun discoverPartitionPlan(source: PartitionSource): PartitionPlan {
    val inventory = source.inventory()
    val actual = source.remoteStorageDevices()
    check(actual.isNotEmpty() && actual.all(::validBlockDevice)) { "无法核实基带实际使用的分区" }
    val byDevice = inventory.groupBy { it.realDevice }
    val selected = linkedMapOf<String, RawPartition>()
    val reasons = mutableMapOf<String, MutableSet<String>>()
    fun add(part: RawPartition, reason: String) {
        check(validPartitionName(part.name) && validBlockDevice(part.realDevice) &&
            part.source == part.realDevice && validPartitionUuid(part.uuid) &&
            part.size in 512..MAX_RAW_IMAGE_BYTES && part.size % 512 == 0L) { "不能可靠备份分区：${part.name}" }
        check(byDevice[part.realDevice]?.size == 1) { "分区设备映射有歧义：${part.realDevice}" }
        selected[part.realDevice] = part
        reasons.getOrPut(part.name) { mutableSetOf() }.add(reason)
    }
    val nvHint = Regex("(?i)(efs|nvram|nvdata|nvbk|(?:^|[_-])nv(?:[_-]|$)|modemst[0-9]+)")
    for (part in inventory) if (part.aliases.any { it.lowercase(Locale.ROOT) in EFS_PARTITION_NAMES || nvHint.containsMatchIn(it) }) add(part, "name_hint")
    for (device in actual) {
        val part = byDevice[device]?.singleOrNull()
            ?: error("无法将基带存储设备映射到物理分区：$device")
        add(part, "remote_storage")
    }
    // The inactive slot may never be opened by rmt_storage. Include physical siblings explicitly.
    val slotNames = selected.values.flatMap { it.aliases }.mapNotNull { name ->
        if (!name.matches(Regex("(?i).+[_-][ab]"))) null
        else name.dropLast(1) + when (name.last()) { 'a' -> 'b'; 'b' -> 'a'; 'A' -> 'B'; else -> 'A' }
    }.toSet()
    for (part in inventory) if (part.aliases.any { it in slotNames }) add(part, "slot_peer")
    check(selected.size in 1..MAX_RAW_PARTITIONS && selected.values.sumOf { it.size } <= MAX_RAW_TOTAL_BYTES) { "基带分区数量或总大小超限" }
    check(selected.values.map { it.name }.distinct().size == selected.size) { "不同设备的分区名称相同，无法生成可靠恢复映射" }
    val uuids = selected.values.map { it.uuid }.filter { it.isNotEmpty() }
    check(uuids.distinct().size == uuids.size) { "分区 UUID 重复，无法确认恢复目标" }
    return PartitionPlan(selected.values.sortedBy { it.name }, actual, reasons)
}

/** Shared by import and recovery. Version 1 archives keep their original strict name rules. */
internal fun validManifestPartitionMetadata(manifest: JSONObject): Boolean = runCatching {
    val schema = manifest.getInt("schema")
    if (schema !in 1..2) return false
    val parts = manifest.getJSONObject("partitions")
    val names = parts.keys().asSequence().toSet()
    if (names.size !in 1..MAX_RAW_PARTITIONS || names.any { !validPartitionName(it) }) return false
    if (schema == 1 && (REQUIRED_EFS_PARTITIONS.any { it !in names } || names.any { it !in EFS_PARTITION_NAMES })) return false
    if (schema == 2 && manifest.getString("discovery_method") != RUNTIME_DISCOVERY) return false
    val paths = mutableSetOf<String>()
    val uuids = mutableSetOf<String>()
    var total = 0L
    for (name in names) {
        val part = parts.getJSONObject(name)
        val device = part.getString("real_device")
        val size = part.getLong("size")
        if (!validBlockDevice(device) || !paths.add(device) || size !in 512..MAX_RAW_IMAGE_BYTES || size % 512 != 0L) return false
        if (schema == 1 && part.getString("source") != "/dev/block/by-name/$name") return false
        if (schema == 2) {
            val uuid = part.getString("part_uuid")
            if (part.getString("source") != device || !validPartitionUuid(uuid) ||
                uuid != uuid.lowercase(Locale.ROOT) || (uuid.isNotEmpty() && !uuids.add(uuid))) return false
        }
        total += size
        if (total > MAX_RAW_TOTAL_BYTES) return false
    }
    listOf("rmt_storage_devices_before", "rmt_storage_devices_after").all { key ->
        val devices = manifest.getJSONArray(key)
        devices.length() > 0 && devices.length() <= MAX_RAW_PARTITIONS && (0 until devices.length()).all { devices.getString(it) in paths }
    }
}.getOrDefault(false)

internal fun rawBackupCoversPlan(manifest: JSONObject, plan: PartitionPlan): Boolean = runCatching {
    if (!validManifestPartitionMetadata(manifest)) return false
    val saved = manifest.getJSONObject("partitions")
    plan.partitions.all { part ->
        val old = saved.optJSONObject(part.name) ?: return false
        old.getLong("size") == part.size && old.getString("real_device") == part.realDevice &&
            (manifest.getInt("schema") == 1 || old.getString("part_uuid") == part.uuid)
    }
}.getOrDefault(false)

internal fun recoveryTargets(manifest: JSONObject, inventory: List<RawPartition>): Map<String, RawPartition> {
    require(validManifestPartitionMetadata(manifest)) { "分区清单格式不合法" }
    val parts = manifest.getJSONObject("partitions")
    return parts.keys().asSequence().associateWith { name ->
        val saved = parts.getJSONObject(name)
        val uuid = saved.optString("part_uuid")
        val matches = inventory.filter { current ->
            if (uuid.isNotEmpty()) current.uuid == uuid && (current.name == name || name in current.aliases)
            else (current.name == name || name in current.aliases)
        }
        val current = matches.singleOrNull() ?: error("当前分区不存在或映射有歧义：$name")
        require(validBlockDevice(current.realDevice) && current.size == saved.getLong("size")) { "目标分区大小或设备路径不匹配：$name" }
        current
    }.also { targets -> require(targets.values.map { it.realDevice }.distinct().size == targets.size) { "恢复目标指向重复块设备" } }
}
