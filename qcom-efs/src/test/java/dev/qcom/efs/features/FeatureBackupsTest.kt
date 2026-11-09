package dev.qcom.efs.features

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FeatureBackupsTest {
    @get:Rule val temp = TemporaryFolder()
    private val feature = ALL_FEATURES.first { it.id == "nsa_tf_nrca" }
    private val device = "test-device"

    private class MemoryAccess : FeatureItemAccess {
        val items = mutableMapOf<String, List<Int>>()
        val writes = mutableListOf<String>()
        var failWrite: String? = null
        override suspend fun read(path: String) = items[path]
        override suspend fun write(path: String, bytes: List<Int>) {
            check(path != failWrite) { "write failed" }
            writes += path
            items[path] = bytes
        }
    }

    private fun snapshot(hex: String = "010f", at: Long = 1000) = FeatureBackup(
        feature.id, 0, device, at, feature.writes.map { OriginalItem(it.path, hex) },
    )

    @Test fun `round trip preserves missing empty and arbitrary original bytes`() {
        val original = snapshot().copy(items = listOf(OriginalItem(feature.writes[0].path, null), OriginalItem(feature.writes[1].path, "")))
        assertEquals(original, FeatureBackup.decode(original.encode()))
        val bytes = snapshot("00ff800123")
        assertEquals(bytes, FeatureBackup.decode(bytes.encode()))
    }

    @Test fun `local original wins over a different valid public copy`() {
        val local = temp.newFolder("local"); val public = temp.newFolder("public")
        val store = FeatureBackupStore(local, public, device)
        store.save(snapshot("010f"), feature)
        File(public, "${feature.id}-SIM0.json").writeText(snapshot("0007").encode())
        assertEquals("010f", store.load(feature, 0)!!.items.first().hex)
    }

    @Test fun `missing local copy falls back to public and repopulates local`() {
        val local = temp.newFolder("local"); val public = temp.newFolder("public")
        val store = FeatureBackupStore(local, public, device)
        store.save(snapshot(), feature)
        File(local, "${feature.id}-SIM0.json").delete()
        assertEquals(snapshot(), store.load(feature, 0))
        assertTrue(File(local, "${feature.id}-SIM0.json").isFile)
    }

    @Test fun `corrupt local copy falls back without losing the original`() {
        val local = temp.newFolder("local"); val public = temp.newFolder("public")
        val store = FeatureBackupStore(local, public, device)
        store.save(snapshot(), feature)
        File(local, "${feature.id}-SIM0.json").writeText("broken")
        assertEquals(snapshot(), store.load(feature, 0))
        assertEquals(snapshot(), FeatureBackup.decode(File(local, "${feature.id}-SIM0.json").readText()))
    }

    @Test fun `corrupt public copy can be repaired from valid local original`() {
        val local = temp.newFolder("local"); val public = temp.newFolder("public")
        val store = FeatureBackupStore(local, public, device)
        store.save(snapshot(), feature)
        File(public, "${feature.id}-SIM0.json").writeText("broken")
        store.save(store.load(feature, 0)!!, feature)
        assertEquals(snapshot(), FeatureBackup.decode(File(public, "${feature.id}-SIM0.json").readText()))
        assertTrue(File(public, "history").listFiles()!!.any { it.extension == "corrupt" })
    }

    @Test fun `corrupt copies cannot silently be treated as no backup`() {
        val local = temp.newFolder("local"); val public = temp.newFolder("public")
        File(local, "${feature.id}-SIM0.json").writeText("broken")
        File(public, "${feature.id}-SIM0.json").writeText("broken")
        assertThrows(IllegalStateException::class.java) { FeatureBackupStore(local, public, device).load(feature, 0) }
    }

    @Test fun `device slot path and checksum mismatches are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { snapshot().validate(feature, 0, "another-device") }
        assertThrows(IllegalArgumentException::class.java) { snapshot().validate(feature, 1, device) }
        assertThrows(IllegalArgumentException::class.java) { snapshot().copy(items = listOf(OriginalItem("/wrong", "01"))).validate(feature, 0, device) }
        assertThrows(IllegalArgumentException::class.java) { FeatureBackup.decode(snapshot().encode().replace("010f", "0007")) }
    }

    @Test fun `restore uses the exact original including absent and empty items`() = runBlocking {
        val access = MemoryAccess()
        access.items[feature.writes[1].path] = emptyList()
        val restorer = FeatureRestorer(access, { access.items.remove(it); Unit }, {})
        val original = restorer.capture(feature, 0, device)
        FeatureChecker(access).disable(feature, 0)
        restorer.restore(original, feature, device)
        assertNull(access.items[feature.writes[0].path])
        assertEquals(emptyList<Int>(), access.items[feature.writes[1].path])
    }

    @Test fun `SIM1 restore never writes the SIM0 path`() = runBlocking {
        val access = MemoryAccess()
        feature.writes.forEach { access.items[it.path] = listOf(99); access.items[slotPath(it.path, 1)] = listOf(1, 15, 255) }
        val restorer = FeatureRestorer(access, { access.items.remove(it); Unit }, {})
        val original = restorer.capture(feature, 1, device)
        FeatureChecker(access).disable(feature, 1)
        restorer.restore(original, feature, device)
        feature.writes.forEach {
            assertEquals(listOf(99), access.items[it.path])
            assertEquals(listOf(1, 15, 255), access.items[slotPath(it.path, 1)])
        }
        assertTrue(access.writes.all { it.endsWith(SUBSCRIPTION_SUFFIX) })
    }

    @Test fun `write or sync failure retains a restorable before image`() = runBlocking {
        val access = MemoryAccess()
        feature.writes.forEach { access.items[it.path] = listOf(1, 15) }
        val local = temp.newFolder("local"); val public = temp.newFolder("public")
        val store = FeatureBackupStore(local, public, device)
        val restorer = FeatureRestorer(access, {}, { error("sync failed") })
        val original = restorer.capture(feature, 0, device)
        store.save(original, feature)
        access.failWrite = feature.writes[1].path
        try { restorer.restore(original, feature, device); fail("expected write failure") } catch (_: IllegalStateException) {}
        assertFalse(store.load(feature, 0)!!.restored)
        access.failWrite = null
        try { restorer.restore(original, feature, device); fail("expected sync failure") } catch (_: IllegalStateException) {}
        assertEquals(original, store.load(feature, 0))
    }

    @Test fun `later disable cycles retain earlier backups in history`() {
        val local = temp.newFolder("local"); val public = temp.newFolder("public")
        val store = FeatureBackupStore(local, public, device)
        store.save(snapshot().copy(restored = true), feature)
        store.save(snapshot(at = 2000), feature)
        assertTrue(File(public, "history/${feature.id}-SIM0-1000.json").isFile)
        assertEquals(2000L, store.load(feature, 0)!!.createdAt)
    }
}
