package dev.qcom.efs

import dev.qcom.efs.features.sha256
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private val summary = PullSummary(1, 1, 0, 8, 0, emptyList())

    private fun archive(): File = temp.newFile("efs-${System.nanoTime()}.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("empty/")); zip.closeEntry()
            val data = "payload-for-crc-check".toByteArray()
            zip.putNextEntry(ZipEntry("nv/item").apply {
                method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(data) }.value
            })
            zip.write(data); zip.closeEntry()
        }
    }

    @Test fun `FS IMAGE bad command selects fallback but security errors do not`() {
        assertTrue(isUnsupportedTar("fs-image open: the target rejected the command: bad command (0x13)"))
        assertTrue(isUnsupportedTar("FS_IMAGE is not supported"))
        assertFalse(isUnsupportedTar("DIAG_BAD_SEC_MODE_F (0x42)"))
        assertFalse(isUnsupportedTar("lost the connection to the helper"))
    }

    @Test fun `empty directories do not hide missing files in archive validation`() {
        verifyBackupZip(archive(), summary)
        assertThrows(IllegalArgumentException::class.java) { verifyBackupZip(archive(), summary.copy(files = 2)) }
        assertThrows(IllegalArgumentException::class.java) { verifyBackupZip(archive(), summary.copy(files = 0)) }
    }

    @Test fun `damaged ZIP payload is rejected by CRC verification`() {
        val zip = archive()
        val bytes = zip.readBytes()
        val payload = "payload-for-crc-check".toByteArray().toList()
        val offset = bytes.toList().windowed(payload.size).indexOf(payload)
        assertTrue(offset >= 0)
        bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
        zip.writeBytes(bytes)
        assertThrows(java.util.zip.ZipException::class.java) { verifyBackupZip(zip, summary) }
    }

    @Test fun `initial write gate needs verified partition images and does not depend on file export`() {
        val root = temp.newFolder()
        val marker = temp.newFile("marker.json")
        val raw = RawPartitionBackupTest.validRawManifest(root)
        val data = JSONObject().put("schema", 3).put("backup_kind", "raw-partitions").put("device_id", "device")
            .put("complete", true)
            .put("raw_manifest", raw.absolutePath).put("raw_manifest_sha256", sha256(raw.readBytes()))
        marker.writeText(data.toString())
        assertTrue(isInitialBackupValid(marker, root, "device"))
        assertFalse(isInitialBackupValid(marker, root, "other-device"))
        data.put("schema", 1); marker.writeText(data.toString())
        assertFalse(isInitialBackupValid(marker, root, "device"))
        data.put("schema", 3).put("complete", false); marker.writeText(data.toString())
        assertFalse(isInitialBackupValid(marker, root, "device"))
        data.put("complete", true).put("backup_kind", "efs-file-export"); marker.writeText(data.toString())
        assertFalse(isInitialBackupValid(marker, root, "device"))
        // Restricted logical files and missing ZIP are irrelevant to a complete block-device acquisition.
        data.put("backup_kind", "raw-partitions").put("file_export_errors", 6)
            .put("archive", "missing.zip"); marker.writeText(data.toString())
        assertTrue(isInitialBackupValid(marker, root, "device"))
        raw.appendBytes(byteArrayOf(32))
        assertFalse(isInitialBackupValid(marker, root, "device"))
    }

    @Test fun `initial backup gate rejects damaged raw image even when manifests match`() {
        val root = temp.newFolder()
        val raw = RawPartitionBackupTest.validRawManifest(root)
        val marker = temp.newFile("raw-gate.json")
        marker.writeText(JSONObject().put("schema", 3).put("backup_kind", "raw-partitions").put("device_id", "device")
            .put("complete", true).put("raw_manifest", raw.absolutePath)
            .put("raw_manifest_sha256", sha256(raw.readBytes())).toString())
        assertTrue(isInitialBackupValid(marker, root, "device"))
        File(raw.parentFile, "fsg.bin").appendBytes(byteArrayOf(0))
        assertFalse(isInitialBackupValid(marker, root, "device"))
    }

    @Test fun `a damaged coverage supplement cannot reuse the valid original baseline gate`() {
        val root = temp.newFolder()
        val original = RawPartitionBackupTest.validRawManifest(root)
        val supplement = File(root, "raw-efs-supplement")
        assertTrue(original.parentFile!!.copyRecursively(supplement))
        val coverage = File(supplement, "manifest.json")
        val marker = temp.newFile("supplement-gate.json")
        marker.writeText(JSONObject().put("schema", 3).put("backup_kind", "raw-partitions").put("device_id", "device")
            .put("complete", true).put("raw_manifest", original.absolutePath).put("raw_manifest_sha256", sha256(original.readBytes()))
            .put("coverage_manifest", coverage.absolutePath).put("coverage_manifest_sha256", sha256(coverage.readBytes())).toString())
        assertTrue(isInitialBackupValid(marker, root, "device"))
        File(supplement, "fsg.bin").delete()
        assertFalse(isInitialBackupValid(marker, root, "device"))
        assertTrue(isInitialBackupValid(marker, root, "device", checkCoverage = false))
        assertTrue(isRawBackupValid(original, root, "device"))
    }
}
