package dev.qcom.efs

import dev.qcom.efs.features.sha256
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupBundleTest {
    @get:Rule val temp = TemporaryFolder()
    private val identity = BackupIdentity("test-model", "123456789012345", "test-serial", "test-firmware")

    private fun bundle(names: List<String>): File {
        val source = object : PartitionSource {
            val parts = names.mapIndexed { index, name ->
                name to RawPartition(name, "/dev/block/sda${index + 1}", "/dev/block/sda${index + 1}", 512)
            }.toMap()
            fun bytes(partition: RawPartition) = ByteArray(512) { (it + partition.name.hashCode()).toByte() }
            override fun checkRoot() = Unit
            override fun inventory() = parts.values.toList()
            override fun remoteStorageDevices() = parts.values.map { it.realDevice }.toSet()
            override fun sha256(partition: RawPartition) = sha256(bytes(partition))
            override fun copyTo(partition: RawPartition, target: File) { target.writeBytes(bytes(partition)) }
        }
        val raw = RawPartitionBackup(source).capture(temp.newFolder(), "test-device", identity.model, identity.firmware) {}
        return BackupBundle.pack(raw, identity, File(temp.newFolder(), "backup.zip"))
    }

    @Test fun `all supported partition images survive packing and import beyond the old entry limit`() {
        val archive = bundle(EFS_PARTITION_NAMES)
        ZipFile(archive).use { zip ->
            assertTrue(zip.size() > 14)
            assertEquals(EFS_PARTITION_NAMES.size + 2, zip.size())
        }
        val root = temp.newFolder()
        val imported = BackupBundle.extract(archive, root, identity)
        val parts = JSONObject(imported.raw.manifest.readText()).getJSONObject("partitions")
        assertEquals(EFS_PARTITION_NAMES.toSet(), parts.keys().asSequence().toSet())
        assertTrue(isRawBackupValid(imported.raw.manifest, root, "test-device"))
        EFS_PARTITION_NAMES.forEach { assertTrue(File(imported.raw.directory, "$it.bin").isFile) }
    }

    @Test fun `a bundle with only required partitions remains compatible`() {
        val root = temp.newFolder()
        val imported = BackupBundle.extract(bundle(REQUIRED_EFS_PARTITIONS), root, identity)
        assertTrue(isRawBackupValid(imported.raw.manifest, root, "test-device"))
        assertEquals(REQUIRED_EFS_PARTITIONS.size,
            JSONObject(imported.raw.manifest.readText()).getJSONObject("partitions").length())
    }

    @Test fun `future daemon-backed partition names round trip without extending a whitelist`() {
        val names = (1..20).map { "future_store_$it" }
        val root = temp.newFolder()
        val imported = BackupBundle.extract(bundle(names), root, identity)
        assertTrue(isRawBackupValid(imported.raw.manifest, root, "test-device"))
        assertEquals(names.toSet(), JSONObject(imported.raw.manifest.readText()).getJSONObject("partitions").keys().asSequence().toSet())
    }

    @Test fun `an extra file within the entry count range is still rejected`() {
        val original = bundle(REQUIRED_EFS_PARTITIONS)
        val modified = File(temp.newFolder(), "extra-file.zip")
        ZipOutputStream(modified.outputStream()).use { output ->
            ZipFile(original).use { input ->
                input.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            output.putNextEntry(ZipEntry("raw/unlisted.bin"))
            output.write(ByteArray(512))
            output.closeEntry()
        }
        val root = temp.newFolder()
        val failure = assertThrows(IllegalArgumentException::class.java) { BackupBundle.extract(modified, root, identity) }
        assertEquals("备份包包含额外文件或缺少镜像", failure.message)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
}
