package dev.qcom.efs

import dev.qcom.efs.features.sha256
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RawPartitionBackupTest {
    @get:Rule val temp = TemporaryFolder()

    private class Source : PartitionSource {
        val parts = (REQUIRED_EFS_PARTITIONS + "fs_bkup").mapIndexed { index, name ->
            name to RawPartition(name, "/dev/block/sda${index + 1}", "/dev/block/sda${index + 1}", 512)
        }.toMap().toMutableMap()
        var actualDevices: Set<String> = parts.values.map { it.realDevice }.toSet()
        var shortRead = false
        var changedAfter = false
        private val hashes = mutableMapOf<String, Int>()
        override fun checkRoot() = Unit
        override fun inventory() = parts.values.toList()
        override fun remoteStorageDevices() = actualDevices
        override fun sha256(partition: RawPartition): String {
            val count = hashes.getOrDefault(partition.name, 0) + 1
            hashes[partition.name] = count
            return sha256(bytes(partition, if (changedAfter && count > 1) 1 else 0))
        }
        override fun copyTo(partition: RawPartition, target: File) {
            target.writeBytes(bytes(partition).let { if (shortRead) it.copyOf(100) else it })
        }
        private fun bytes(part: RawPartition, extra: Int = 0) = ByteArray(512) { (it + part.name.hashCode() + extra).toByte() }
    }

    private fun capture(root: File, source: Source = Source()) =
        RawPartitionBackup(source).capture(root, "device", "model", "firmware") {}

    @Test fun `every saved byte must match stable source and complete live device coverage`() {
        val root = temp.newFolder()
        val backup = capture(root)
        assertTrue(isRawBackupValid(backup.manifest, root, "device"))
        assertFalse(isRawBackupValid(backup.manifest, root, "different-device"))
        val json = JSONObject(backup.manifest.readText())
        assertEquals(6, json.getJSONObject("partitions").length())
        assertEquals(3072L, json.getLong("verified_bytes"))
        assertFalse(json.getBoolean("all_logical_efs_paths_verified"))
        assertFalse(json.getBoolean("restore_tested"))
    }

    @Test fun `missing mandatory partition never yields complete marker`() {
        val root = temp.newFolder()
        val source = Source().apply { parts.remove("modemst2") }
        assertThrows(IllegalStateException::class.java) { capture(root, source) }
        assertIncomplete(root)
    }

    @Test fun `uncovered modem backing device prevents success`() {
        val root = temp.newFolder()
        val source = Source().apply { actualDevices = actualDevices + "/dev/block/sdf99" }
        assertThrows(IllegalStateException::class.java) { capture(root, source) }
        assertIncomplete(root)
    }

    @Test fun `oplus dynamic and both static slots are backed up for either active slot`() {
        for (activeSlot in listOf("oplusstanvbk_a", "oplusstanvbk_b")) {
            val root = temp.newFolder()
            val source = Source().apply {
                listOf("oplusdycnvbk", "oplusstanvbk_a", "oplusstanvbk_b").forEachIndexed { index, name ->
                    parts[name] = RawPartition(name, "/dev/block/sdf${index + 6}", "/dev/block/sdf${index + 6}", 512)
                }
                actualDevices = actualDevices + parts.getValue("oplusdycnvbk").realDevice + parts.getValue(activeSlot).realDevice
            }
            val backup = capture(root, source)
            val parts = JSONObject(backup.manifest.readText()).getJSONObject("partitions")
            listOf("oplusdycnvbk", "oplusstanvbk_a", "oplusstanvbk_b").forEach { name ->
                assertTrue(parts.has(name)); assertTrue(File(backup.directory, "$name.bin").isFile)
            }
            assertTrue(isRawBackupValid(backup.manifest, root, "device"))
            File(backup.directory, "$activeSlot.bin").writeBytes(ByteArray(512))
            assertFalse(isRawBackupValid(backup.manifest, root, "device"))
        }
    }

    @Test fun `short read preserves diagnostic manifest and never succeeds`() {
        val root = temp.newFolder()
        assertThrows(IllegalStateException::class.java) { capture(root, Source().apply { shortRead = true }) }
        assertIncomplete(root)
    }

    @Test fun `source changing after the group invalidates all images as a complete set`() {
        val root = temp.newFolder()
        assertThrows(IllegalStateException::class.java) { capture(root, Source().apply { changedAfter = true }) }
        assertIncomplete(root)
    }

    @Test fun `damaged or removed image invalidates the gate even with a complete manifest`() {
        val root = temp.newFolder()
        val backup = capture(root)
        val image = File(backup.directory, "modemst1.bin")
        image.writeBytes(ByteArray(512))
        assertFalse(isRawBackupValid(backup.manifest, root, "device"))
        image.delete()
        assertFalse(isRawBackupValid(backup.manifest, root, "device"))
    }

    @Test fun `raw manifest cannot redirect a required image outside the backup folder`() {
        val root = temp.newFolder()
        val backup = capture(root)
        val json = JSONObject(backup.manifest.readText())
        json.getJSONObject("partitions").getJSONObject("modemst1").put("file", "../modemst1.bin")
        backup.manifest.writeText(json.toString())
        assertFalse(isRawBackupValid(backup.manifest, root, "device"))
    }

    private fun assertIncomplete(root: File) {
        val manifest = root.walkTopDown().first { it.name == "manifest.json" }
        assertFalse(JSONObject(manifest.readText()).getBoolean("complete_for_listed_partitions"))
        assertFalse(isRawBackupValid(manifest, root, "device"))
    }

    companion object {
        fun validRawManifest(root: File): File {
            val folder = File(root, "raw-efs-test").apply { mkdirs() }
            val parts = JSONObject()
            val paths = org.json.JSONArray()
            REQUIRED_EFS_PARTITIONS.forEachIndexed { index, name ->
                val image = File(folder, "$name.bin").apply { writeBytes(ByteArray(512) { index.toByte() }) }
                val hash = sha256(image.readBytes())
                val path = "/dev/block/sda${index + 1}"
                paths.put(path)
                parts.put(name, JSONObject().put("source", "/dev/block/by-name/$name").put("real_device", path)
                    .put("size", 512).put("file", image.name).put("sha256", hash)
                    .put("source_sha256_before", hash).put("source_sha256_after", hash).put("stable_and_verified", true))
            }
            return File(folder, "manifest.json").apply {
                writeText(JSONObject().put("schema", 1).put("device_id", "device")
                    .put("complete_for_listed_partitions", true).put("partitions", parts)
                    .put("rmt_storage_devices_before", paths).put("rmt_storage_devices_after", paths).toString())
            }
        }
    }
}
