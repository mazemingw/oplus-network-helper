package dev.qcom.efs

import dev.qcom.efs.features.sha256
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PartitionDiscoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun part(name: String, number: Int, uuid: String = "uuid-$number") =
        RawPartition(name, "/dev/block/sdf$number", "/dev/block/sdf$number", 512, uuid)

    private class Source(var parts: List<RawPartition>, var opened: Set<String>) : PartitionSource {
        var changed: List<RawPartition>? = null
        private var reads = 0
        override fun checkRoot() = Unit
        override fun inventory(): List<RawPartition> = if (++reads > 1) changed ?: parts else parts
        override fun remoteStorageDevices() = opened
        override fun sha256(partition: RawPartition) = sha256(ByteArray(512) { partition.name.hashCode().toByte() })
        override fun copyTo(partition: RawPartition, target: File) { target.writeBytes(ByteArray(512) { partition.name.hashCode().toByte() }) }
    }

    @Test fun `kernel inventory merges multiple alias directories without duplicate images`() {
        val inventory = parsePartitionInventory("P\t/dev/block/sdf7\t8\tVendorStore_a\tABC-123\n" +
            "A\t/dev/block/bootdevice/by-name/old_store_a\t/dev/block/sdf7\n" +
            "A\t/dev/block/platform/soc/storage/by-name/old_store_a\t/dev/block/sdf7\n")
        assertEquals(1, inventory.size)
        assertEquals("VendorStore_a", inventory.single().name)
        assertEquals(4096L, inventory.single().size)
        assertEquals("abc-123", inventory.single().uuid)
        assertEquals(setOf("VendorStore_a", "old_store_a"), inventory.single().aliases)
    }

    @Test fun `unknown open partitions and inactive peers are included without selecting unrelated storage`() {
        val active = part("future_radio_a", 1)
        val peer = part("future_radio_b", 2)
        val unused = part("userdata", 3).copy(size = 512L * 1024 * 1024 * 1024)
        val hinted = part("vendor_nvdata", 4)
        val source = Source(listOf(active, peer, unused, hinted), setOf(active.realDevice))
        val plan = discoverPartitionPlan(source)
        assertEquals(setOf(active.name, peer.name, hinted.name), plan.partitions.map { it.name }.toSet())
        assertTrue("remote_storage" in plan.reasons.getValue(active.name))
        assertTrue("slot_peer" in plan.reasons.getValue(peer.name))
        val root = temp.newFolder()
        val raw = RawPartitionBackup(source).capture(root, "device", "model", "firmware") {}
        assertTrue(isRawBackupValid(raw.manifest, root, "device"))
        assertTrue(rawBackupCoversPlan(JSONObject(raw.manifest.readText()), plan))
        val expanded = discoverPartitionPlan(Source(source.parts + part("next_storage", 5), source.opened + "/dev/block/sdf5"))
        assertFalse(rawBackupCoversPlan(JSONObject(raw.manifest.readText()), expanded))
    }

    @Test fun `unmapped or ambiguous backing devices still fail closed`() {
        val active = part("new_store", 1)
        assertThrows(IllegalStateException::class.java) { discoverPartitionPlan(Source(listOf(active), setOf("/dev/block/sdf99"))) }
        assertThrows(IllegalStateException::class.java) { discoverPartitionPlan(Source(listOf(active, active.copy(name = "alias")), setOf(active.realDevice))) }
    }

    @Test fun `topology changing during acquisition prevents a complete backup`() {
        val active = part("new_store", 1)
        val source = Source(listOf(active), setOf(active.realDevice)).apply { changed = listOf(active.copy(uuid = "changed")) }
        val root = temp.newFolder()
        assertThrows(IllegalStateException::class.java) { RawPartitionBackup(source).capture(root, "device", "model", "firmware") {} }
        val manifest = root.walkTopDown().first { it.name == "manifest.json" }
        assertFalse(isRawBackupValid(manifest, root, "device"))
    }

    @Test fun `recovery resolves UUID after kernel device renumbering and rejects a different partition`() {
        val active = part("new_store", 1)
        val root = temp.newFolder()
        val raw = RawPartitionBackup(Source(listOf(active), setOf(active.realDevice))).capture(root, "device", "model", "firmware") {}
        val manifest = JSONObject(raw.manifest.readText())
        val moved = active.copy(source = "/dev/block/mmcblk0p7", realDevice = "/dev/block/mmcblk0p7")
        assertEquals(moved, recoveryTargets(manifest, listOf(moved)).getValue(active.name))
        assertThrows(IllegalStateException::class.java) { recoveryTargets(manifest, listOf(moved.copy(uuid = "different"))) }
        manifest.getJSONObject("partitions").put("../escape", manifest.getJSONObject("partitions").getJSONObject(active.name))
        assertFalse(validManifestPartitionMetadata(manifest))
    }
}
