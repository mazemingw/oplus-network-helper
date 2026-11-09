package dev.qcom.efs

import dev.qcom.efs.platform.PhoneAccess
import dev.qcom.efs.platform.toggleNrBit
import dev.qcom.efs.platform.setNrAllowed
import dev.qcom.efs.features.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EmbeddedEfsTest {
    @get:Rule val temp = TemporaryFolder()
    private val identity = BackupIdentity("PLK110", "123456789012345", "TEST1234", "test-firmware")

    @Test fun `NR allow and deny are idempotent and retain every other RAT bit`() {
        val other = 0xffffL
        assertEquals(other or PhoneAccess.NR_BIT, setNrAllowed(other, true))
        assertEquals(other or PhoneAccess.NR_BIT, setNrAllowed(other or PhoneAccess.NR_BIT, true))
        assertEquals(other, setNrAllowed(other or PhoneAccess.NR_BIT, false))
        assertEquals(other, setNrAllowed(other, false))
    }

    @Test fun `NSA refusals move to unsupported while transport errors remain in the original section`() {
        val nsa = ALL_FEATURES.first { it.id == "nsa_tf_nrca" }
        val common = ALL_FEATURES.first { it.id == "dl_nrca" }
        assertEquals(FeatureSection.NSA, featureSection(nsa, FeatureStatus.CanDisable))
        assertEquals(FeatureSection.UNSUPPORTED, featureSection(nsa, FeatureStatus.ReadError("the target rejected the command: bad command (0x13)")))
        assertEquals(FeatureSection.COMMON, featureSection(common, FeatureStatus.ReadError("lost the connection to the helper")))
        assertEquals(FeatureSection.UNSUPPORTED, featureSection(common, FeatureStatus.WriteError("efs errno=13")))
        assertEquals(FeatureSection.COMMON, featureSection(common, FeatureStatus.ReadError("efs errno=130")))
    }

    @Test fun `read-only NV identity decoding rejects absent invalid or malformed data`() {
        assertEquals("123456789012345", decodeNvImei("081a32547698103254" + "00".repeat(119)))
        assertNull(decodeNvImei("000000000000000000"))
        assertNull(decodeNvImei("081a3254769810325f"))
        assertNull(decodeNvImei("081a"))
    }

    @Test fun `only positively identified Qualcomm platforms are allowed`() {
        assertTrue(QualcommPlatform.confirmed("QTI", "qcom", "SM8850"))
        assertTrue(QualcommPlatform.confirmed("Qualcomm", "unknown", "unknown"))
        assertTrue(QualcommPlatform.confirmed("unknown", "unknown", "MSM8998"))
        assertFalse(QualcommPlatform.confirmed("OnePlus", "unknown", "unknown"))
        assertFalse(QualcommPlatform.confirmed("Qualcomm", "mt6989", "MT6989"))
        for (vendor in listOf("MediaTek", "MTK", "Samsung Exynos", "Unisoc", "Kirin", "Tensor", "unknown"))
            assertFalse(QualcommPlatform.confirmed(vendor, "unknown", "unknown"))
    }

    @Test fun `5G tile changes exactly the NR bit and refuses to remove the only allowed RAT`() {
        val lteAndOthers = 0x0000ffffL
        assertEquals(lteAndOthers or PhoneAccess.NR_BIT, toggleNrBit(lteAndOthers))
        assertEquals(lteAndOthers, toggleNrBit(lteAndOthers or PhoneAccess.NR_BIT))
        assertThrows(IllegalArgumentException::class.java) { toggleNrBit(PhoneAccess.NR_BIT) }
    }

    private fun raw(): RawBackup {
        val root = temp.newFolder()
        val manifest = RawPartitionBackupTest.validRawManifest(root)
        val json = JSONObject(manifest.readText()).put("model", identity.model).put("firmware", identity.firmware)
            .put("kind", "qualcomm-efs-nv-raw-partition-acquisition")
        manifest.writeText(json.toString())
        return RawBackup(manifest.parentFile!!, manifest)
    }
    private fun pack(raw: RawBackup = raw()): File = BackupBundle.pack(raw, identity, File(temp.root, "${System.nanoTime()}.zip"))

    @Test fun `cloud archive survives a lost IMEI and contains every original partition byte`() {
        val source = raw()
        val archive = pack(source)
        val extracted = BackupBundle.extract(archive, temp.newFolder(), identity.copy(imei = ""))
        for (part in REQUIRED_EFS_PARTITIONS)
            assertArrayEquals(File(source.directory, "$part.bin").readBytes(), File(extracted.raw.directory, "$part.bin").readBytes())
    }
    @Test fun `other devices and models cannot import the archive`() {
        val archive = pack()
        assertThrows(IllegalArgumentException::class.java) { BackupBundle.extract(archive, temp.newFolder(), identity.copy(serial = "OTHER123")) }
        assertThrows(IllegalArgumentException::class.java) { BackupBundle.extract(archive, temp.newFolder(), identity.copy(model = "OTHER")) }
    }
    @Test fun `same length corruption with valid ZIP CRC is caught by the partition SHA`() {
        val archive = pack()
        val corrupt = File(temp.root, "corrupt.zip")
        rewriteZip(archive, corrupt) { name, body -> if (name == "raw/modemst1.bin") body.copyOf().apply { this[0] = 99 } else body }
        val root = temp.newFolder()
        assertThrows(IllegalArgumentException::class.java) { BackupBundle.extract(corrupt, root, identity) }
        assertEquals(0, root.listFiles()!!.size)
    }
    @Test fun `additional files and ZIP traversal are rejected before extraction`() {
        val archive = pack(); val corrupt = File(temp.root, "traversal.zip")
        ZipOutputStream(corrupt.outputStream()).use { output ->
            ZipFile(archive).use { input -> input.entries().asSequence().forEach { entry ->
                output.putNextEntry(ZipEntry(entry.name)); input.getInputStream(entry).use { it.copyTo(output) }; output.closeEntry()
            } }
            output.putNextEntry(ZipEntry("../escape.bin")); output.write(1); output.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { BackupBundle.extract(corrupt, temp.newFolder(), identity) }
        assertFalse(File(temp.root, "escape.bin").exists())
    }
    @Test fun `offline recovery script parses and requires offline device checks before block writes`() {
        val source = raw()
        val info = JSONObject().put("hardware_serial", identity.serial)
        val script = BackupBundle.recoveryScript(info, JSONObject(source.manifest.readText()))
        assertTrue(script.contains("\nEFS_PLAN\n"))
        assertTrue(script.indexOf("Cannot verify offline modem state") < script.indexOf("of=\"${'$'}dev\""))
        assertTrue(script.contains("resolve_partition"))
        assertTrue(script.contains("pre-restore-"))
        val file = temp.newFile("restore.sh").apply { writeText(script) }
        val bash = File("C:/Program Files/Git/bin/bash.exe")
        if (bash.isFile) {
            val check = ProcessBuilder(bash.absolutePath, "-n", file.absolutePath).redirectErrorStream(true).start()
            val output = check.inputStream.bufferedReader().readText()
            assertEquals(output, 0, check.waitFor()) // parse only, NEVER executes the script
        }
    }

    private fun rewriteZip(source: File, target: File, change: (String, ByteArray) -> ByteArray) {
        ZipFile(source).use { input -> ZipOutputStream(target.outputStream()).use { output ->
            input.entries().asSequence().forEach { entry ->
                output.putNextEntry(ZipEntry(entry.name))
                output.write(change(entry.name, input.getInputStream(entry).use { it.readBytes() })); output.closeEntry()
            }
        } }
    }
}
