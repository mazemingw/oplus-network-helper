package com.nvmex.networkhelper.util.menu

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginArchiveTest {
    private fun realSample(name: String) {
        val fixtureDir = System.getenv("NH_PLUGIN_FIXTURE_DIR")
        org.junit.Assume.assumeTrue(fixtureDir != null)
        val archive = File(fixtureDir!!, name)
        assertTrue(archive.isFile)
        val plugin = PluginArchive.prepare(archive, temp.newFile())
        PluginArchive.validate(plugin)
        assertTrue(plugin.length() > 10_000_000)
        assertFalse(File(temp.root, "system_ext").exists())
    }
    @Test fun realNewShareBundle() = realSample("new.zip")
    @Test fun realOldShareBundle() = realSample("old.zip")
    @get:Rule val temp = TemporaryFolder()
    private fun zip(file: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        return file
    }
    private fun plugin() = zip(temp.newFile(), mapOf("config.json" to "{}".toByteArray(), "CellularApp.apk" to byteArrayOf(1, 2, 3)))

    @Test fun extractsPluginOnlyRegardlessOfShareFilename() {
        for (name in listOf("engineer-plugin-framework-share.zip", "plugin-release-share.zip")) {
            val expected = plugin().readBytes()
            val bundle = zip(temp.newFile(name), mapOf("plugin-release.zip" to expected, "system_ext/framework/test.jar" to byteArrayOf(4, 5)))
            val extracted = temp.newFile()
            assertArrayEquals(expected, PluginArchive.prepare(bundle, extracted).readBytes())
        }
        assertFalse(File(temp.root, "system_ext").exists())
    }
    @Test fun supportsLegacyStandaloneZipWithAnArbitraryDownloadName() {
        val plugin = plugin()
        assertEquals(plugin, PluginArchive.prepare(plugin, temp.newFile()))
    }
    @Test fun rejectsMissingPluginAndCorruptStandalone() {
        for (entries in listOf(mapOf("system_ext/framework/test.jar" to byteArrayOf(1)), mapOf("hello.txt" to byteArrayOf(2)))) {
            val bad = zip(temp.newFile(), entries)
            assertThrows(IllegalArgumentException::class.java) { PluginArchive.prepare(bad, temp.newFile()) }
        }
    }
    @Test fun rejectsTraversalAbsoluteAndDuplicatePluginEntries() {
        for (name in listOf("../outside", "/outside", "C:/outside", "system_ext/../../outside")) {
            val bad = zip(temp.newFile(), mapOf("plugin-release.zip" to plugin().readBytes(), name to byteArrayOf(1)))
            assertThrows(IllegalArgumentException::class.java) { PluginArchive.prepare(bad, temp.newFile()) }
        }
        val ambiguous = zip(temp.newFile(), mapOf("a/plugin-release.zip" to plugin().readBytes(), "b/plugin-release.zip" to plugin().readBytes()))
        assertThrows(IllegalArgumentException::class.java) { PluginArchive.prepare(ambiguous, temp.newFile()) }
    }
}
