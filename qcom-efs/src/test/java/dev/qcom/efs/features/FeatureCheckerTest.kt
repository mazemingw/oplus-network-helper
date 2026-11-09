package dev.qcom.efs.features

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCheckerTest {

    /** In-memory item store; absent key = item absent. */
    private class FakeAccess(
        vararg initial: Pair<String, List<Int>>,
    ) : FeatureItemAccess {
        val items = initial.toMap().toMutableMap()
        val writes = mutableListOf<Pair<String, List<Int>>>()
        var failWriteOn: String? = null
        var failReadOn: String? = null

        override suspend fun read(path: String): List<Int>? {
            if (path == failReadOn) throw RuntimeException("read boom")
            return items[path]
        }

        override suspend fun write(path: String, bytes: List<Int>) {
            if (path == failWriteOn) throw RuntimeException("write boom")
            writes += path to bytes
            items[path] = bytes
        }
    }

    private val fdd = ALL_FEATURES.first { it.id == "fdd_ul_mimo" }
    private val dl = ALL_FEATURES.first { it.id == "dl_nrca" }
    private val path = "/nv/item_files/modem/nr5g/RRC/cap_control_fdd_ul_mimo"
    private val dlPath = "/nv/item_files/modem/nr5g/RRC/cap_nrca_downgrade_1cc"
    private val nrBase = "/nv/item_files/modem/nr5g/RRC/"

    @Test
    fun `absent item means feature can be disabled`() = runBlocking {
        val c = FeatureChecker(FakeAccess())
        val r = c.check(listOf(fdd), slot = 0)
        assertEquals(FeatureStatus.CanDisable, r.statuses[fdd.id])
    }

    @Test
    fun `present enabled item means can disable`() = runBlocking {
        val c = FeatureChecker(FakeAccess(path to listOf(1, 1)))
        val r = c.check(listOf(fdd), slot = 0)
        assertEquals(FeatureStatus.CanDisable, r.statuses[fdd.id])
    }

    @Test
    fun `present disabled item means already disabled`() = runBlocking {
        val c = FeatureChecker(FakeAccess(path to listOf(0, 0)))
        val r = c.check(listOf(fdd), slot = 0)
        assertEquals(FeatureStatus.AlreadyDisabled, r.statuses[fdd.id])
    }

    @Test
    fun `partially absent multi-read feature means can disable`() = runBlocking {
        val c = FeatureChecker(FakeAccess(dlPath to listOf(0)))
        val r = c.check(listOf(dl), slot = 0)
        assertEquals(FeatureStatus.CanDisable, r.statuses[dl.id])
    }

    @Test
    fun `read error maps to ReadError and check continues with other features`() = runBlocking {
        val c = FeatureChecker(FakeAccess().apply { failReadOn = path })
        val r = c.check(listOf(fdd, dl), slot = 0)
        assertTrue(r.statuses[fdd.id] is FeatureStatus.ReadError)
        assertEquals(FeatureStatus.CanDisable, r.statuses[dl.id])
    }

    @Test
    fun `slot 1 reads the suffixed path`() = runBlocking {
        val access = FakeAccess(path + "_Subscription01" to listOf(0, 0))
        val c = FeatureChecker(access)
        val r = c.check(listOf(fdd), slot = 1)
        assertEquals(FeatureStatus.AlreadyDisabled, r.statuses[fdd.id])
    }

    @Test
    fun `disable writes every write entry to the slot-mapped path`() = runBlocking {
        val access = FakeAccess()
        val c = FeatureChecker(access)
        assertNull(c.disable(fdd, slot = 1))
        assertEquals(listOf(path + "_Subscription01" to listOf(0, 0)), access.writes)
    }

    @Test
    fun `disable stops at first failure with the mtbtool message`() = runBlocking {
        val access = FakeAccess().apply { failWriteOn = path }
        val c = FeatureChecker(access)
        assertEquals("Write failed for cap_control_fdd_ul_mimo", c.disable(fdd, slot = 0))
    }

    @Test
    fun `disable of a multi-write feature stops at the first failure`() = runBlocking {
        val r16 = ALL_FEATURES.first { it.id == "r16_2t1t" }
        val access = FakeAccess().apply { failWriteOn = nrBase + "cap_swul_type_control" }
        val c = FeatureChecker(access)
        assertEquals("Write failed for cap_swul_type_control", c.disable(r16, slot = 0))
        assertEquals(1, access.writes.size)
        assertEquals(nrBase + "cap_control_nrca_xf_plus_yt_swul_band_combos_v2", access.writes.single().first)
    }

    private val qam = ALL_FEATURES.first { it.id == "nr_1024qam_tdd" }
    private val qamPath = nrBase + "cap_feature_band_nr_v2"

    @Test
    fun `merge write creates the item with the default payload when it is absent`() = runBlocking {
        val access = FakeAccess()
        val c = FeatureChecker(access)
        assertNull(c.disable(qam, slot = 0))
        val written = access.writes.single()
        assertEquals(qamPath, written.first)
        assertEquals(List(29) { 0xFF } + 0x00 + listOf(0xFF, 0xFF), written.second)
    }

    @Test
    fun `merge write keeps the other capability bits of an existing item`() = runBlocking {
        val existing = listOf(0xFF, 0xFE, 0x00) + List(27) { 0xFF } + listOf(0xFF, 0xFF)
        val access = FakeAccess(qamPath to existing)
        val c = FeatureChecker(access)
        assertNull(c.disable(qam, slot = 0))
        val written = access.writes.single().second
        assertEquals(0, written[29])
        assertEquals(0xFE, written[1])
        assertEquals(0x00, written[2])
    }

    @Test
    fun `merge write pads a short existing item with FF`() = runBlocking {
        val access = FakeAccess(qamPath to listOf(0x00, 0xFF))
        val c = FeatureChecker(access)
        assertNull(c.disable(qam, slot = 0))
        val written = access.writes.single().second
        assertEquals(32, written.size)
        assertEquals(0x00, written[0])
        assertEquals(0, written[29])
        assertEquals(listOf(0x00, 0xFF) + List(27) { 0xFF } + listOf(0x00) + List(2) { 0xFF }, written)
    }

    @Test
    fun `merge write reports a failed read as a write failure`() = runBlocking {
        val access = FakeAccess().apply { failReadOn = qamPath }
        val c = FeatureChecker(access)
        assertEquals("Write failed for cap_feature_band_nr_v2", c.disable(qam, slot = 0))
        assertEquals(0, access.writes.size)
    }

    @Test
    fun `merge write targets the slot-mapped path`() = runBlocking {
        val access = FakeAccess((qamPath + "_Subscription01") to listOf(0xFF, 0xFF))
        val c = FeatureChecker(access)
        assertNull(c.disable(qam, slot = 1))
        assertEquals(qamPath + "_Subscription01", access.writes.single().first)
        assertEquals(0, access.writes.single().second[29])
    }
}
