package dev.qcom.efs.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureDefsTest {

    private fun disabledBytes(vararg ints: Int) = ints.toList()

    @Test
    fun `table has 14 features with unique ids`() {
        assertEquals(14, ALL_FEATURES.size)
        assertEquals(ALL_FEATURES.size, ALL_FEATURES.map { it.id }.distinct().size)
        assertTrue(ALL_FEATURES.all { it.reads.isNotEmpty() && it.writes.isNotEmpty() })
    }

    @Test
    fun `every read path has a matching write path`() {
        for (f in ALL_FEATURES) {
            assertEquals(
                "feature ${f.id}: reads and writes must cover the same paths",
                f.reads.map { it.substringAfterLast('/') }.sorted(),
                f.writes.map { it.path.substringAfterLast('/') }.sorted(),
            )
        }
    }

    @Test
    fun `r17_2t2t paths and bytes`() {
        val f = ALL_FEATURES.first { it.id == "r17_2t2t" }
        val path = "/nv/item_files/modem/nr5g/RRC/cap_control_nrca_xf_plus_yt_swul_r17_band_combos_v2"
        assertEquals(listOf(path), f.reads)
        assertEquals(listOf(path), f.writes.map { it.path })
        assertEquals(List(24) { 0 }, f.writes.single().bytes)
    }

    @Test
    fun `r16_2t1t six paths and bytes`() {
        val f = ALL_FEATURES.first { it.id == "r16_2t1t" }
        val base = "/nv/item_files/modem/nr5g/RRC/"
        assertEquals(
            listOf(
                base + "cap_control_nrca_xf_plus_yt_swul_band_combos_v2",
                base + "cap_swul_type_control",
                base + "cap_swul_control",
                base + "cap_swul_3x_control",
                base + "cap_swul_4x_control",
                base + "cap_swul_5x_control",
            ),
            f.reads,
        )
        assertEquals(
            listOf(
                List(17) { 0 },
                listOf(0, 0, 0),
                listOf(0),
                listOf(0),
                listOf(0),
                listOf(0),
            ),
            f.writes.map { it.bytes },
        )
        val zero = List(6) { List(1) { 0 } }
        assertTrue(f.isDisabled(zero))
        assertFalse(f.isDisabled(List(6) { listOf(1) }))
        assertFalse(f.isDisabled(List(5) { listOf(0) }))
    }

    @Test
    fun `ul_mimo path bytes and truth table`() {
        val f = ALL_FEATURES.first { it.id == "ul_mimo" }
        assertEquals(
            listOf("/nv/item_files/modem/nr5g/RRC/cap_limit_rf_mimo"),
            f.reads,
        )
        assertEquals(listOf(1, 1) + List(42) { 0 }, f.writes.single().bytes)
        assertTrue(f.isDisabled(listOf(listOf(1, 1, 0))))
        assertFalse(f.isDisabled(listOf(listOf(1, 1, 1))))
        assertFalse(f.isDisabled(listOf(listOf(0, 1, 0))))
        assertFalse(f.isDisabled(listOf(listOf(1, 1))))
    }

    @Test
    fun `fdd_ul_mimo truth table`() {
        val f = ALL_FEATURES.first { it.id == "fdd_ul_mimo" }
        assertEquals("/nv/item_files/modem/nr5g/RRC/cap_control_fdd_ul_mimo", f.reads.single())
        assertEquals(disabledBytes(0, 0), f.writes.single().bytes)
        assertTrue(f.isDisabled(listOf(listOf(0, 0))))
        assertFalse(f.isDisabled(listOf(listOf(0, 1))))
    }

    @Test
    fun `tdd_ul_mimo shares path with ul_mimo and uses the TDD band pattern`() {
        val ul = ALL_FEATURES.first { it.id == "ul_mimo" }
        val f = ALL_FEATURES.first { it.id == "tdd_ul_mimo" }
        assertEquals(ul.reads, f.reads)
        val bandBytes =
            listOf(
                38, 0, 1, 0, 40, 0, 1, 0, 41, 0, 1, 0, 48, 0, 1, 0,
                77, 0, 1, 0, 78, 0, 1, 0, 79, 0, 1, 0, 104, 0, 1, 0,
            )
        assertEquals(listOf(0, 0, 0, 8) + bandBytes + List(8) { 0 }, f.writes.single().bytes)
        assertEquals(44, f.writes.single().bytes.size)
        assertTrue(f.isDisabled(listOf(f.writes.single().bytes)))
        val disabled = listOf(0, 0, 0, 8) + bandBytes
        assertTrue(f.isDisabled(listOf(disabled)))
        assertTrue(f.isDisabled(listOf(disabled + List(20) { 0 })))
        assertFalse(f.isDisabled(listOf(disabled.dropLast(1))))
        assertFalse(f.isDisabled(listOf(listOf(1, 1, 0))))
        assertFalse(f.isDisabled(listOf(listOf(0, 0, 0, 8) + bandBytes.dropLast(1) + listOf(9))))
    }

    @Test
    fun `nr_ulca five paths exact patterns`() {
        val f = ALL_FEATURES.first { it.id == "nr_ulca" }
        val base = "/nv/item_files/modem/nr5g/RRC/"
        assertEquals(
            listOf(
                base + "cap_control_nrca_2x_f_plus_t_band_combos",
                base + "cap_control_nrca_3x_f_plus_t_band_combos",
                base + "cap_control_nrca_4x_f_plus_t_band_combos",
                base + "cap_control_nrca_4x_f_plus_t_band_combos_v2",
                base + "cap_control_nrca_f_plus_f_ulca_band_combos",
            ),
            f.reads,
        )
        assertEquals(
            listOf(
                listOf(0),
                listOf(1, 1, 1, 1, 0, 0),
                listOf(0, 1, 1),
                listOf(0, 1, 1, 0, 1, 1),
                listOf(0, 0),
            ),
            f.writes.map { it.bytes },
        )
        assertTrue(
            f.isDisabled(
                listOf(
                    listOf(0),
                    listOf(1, 1, 1, 1, 0, 0),
                    listOf(0, 1, 1),
                    listOf(0, 1, 1, 0, 1, 1),
                    listOf(0, 0),
                ),
            ),
        )
        assertFalse(f.isDisabled(listOf(listOf(1), listOf(1, 1, 1, 1, 0, 0), listOf(0, 1, 1), listOf(0, 1, 1, 0, 1, 1), listOf(0, 0))))
        assertFalse(f.isDisabled(listOf(listOf(0), listOf(1, 1, 1, 1, 0, 0), listOf(0, 1, 1), listOf(0, 1, 1, 0, 1, 1))))
    }

    @Test
    fun `dl_nrca truth table`() {
        val f = ALL_FEATURES.first { it.id == "dl_nrca" }
        assertEquals("/nv/item_files/modem/nr5g/RRC/cap_nrca_downgrade_1cc", f.reads.single())
        assertEquals(disabledBytes(1), f.writes.single().bytes)
        assertTrue(f.isDisabled(listOf(listOf(1))))
        assertFalse(f.isDisabled(listOf(listOf(0))))
    }

    @Test
    fun `lowband_4rx shares path with ul_mimo and uses exact 24-byte pattern`() {
        val ul = ALL_FEATURES.first { it.id == "ul_mimo" }
        val f = ALL_FEATURES.first { it.id == "lowband_4rx" }
        assertEquals(ul.reads, f.reads)
        val bandBytes = listOf(8, 0, 0, 2, 20, 0, 0, 2, 26, 0, 0, 2, 28, 0, 0, 2, 71, 0, 0, 2)
        assertEquals(listOf(0, 0, 0, 5) + bandBytes + List(20) { 0 }, f.writes.single().bytes)
        val disabled = listOf(0, 0, 0, 5) + bandBytes
        assertTrue(f.isDisabled(listOf(disabled)))
        assertTrue(f.isDisabled(listOf(disabled + List(20) { 0 })))
        assertFalse(f.isDisabled(listOf(disabled.dropLast(1))))
        assertFalse(f.isDisabled(listOf(listOf(1, 1, 0))))
        assertFalse(f.isDisabled(listOf(listOf(0, 0, 0, 5) + bandBytes.dropLast(1) + listOf(9))))
    }

    @Test
    fun `nsa_tf_nrca truth table`() {
        val f = ALL_FEATURES.first { it.id == "nsa_tf_nrca" }
        val base = "/nv/item_files/modem/nr5g/RRC/"
        assertEquals(listOf(base + "cap_control_mrdc_f_plus_t_band_combos", base + "cap_control_t_plus_f_band_combos"), f.reads)
        assertEquals(listOf(listOf(0), listOf(7)), f.writes.map { it.bytes })
        assertTrue(f.isDisabled(listOf(listOf(0), listOf(7))))
        assertFalse(f.isDisabled(listOf(listOf(1), listOf(7))))
        assertFalse(f.isDisabled(listOf(listOf(0), listOf(8))))
        assertFalse(f.isDisabled(listOf(listOf(0))))
    }

    @Test
    fun `nsa_ff_nrca truth table`() {
        val f = ALL_FEATURES.first { it.id == "nsa_ff_nrca" }
        assertEquals("/nv/item_files/modem/nr5g/RRC/cap_control_mrdc_2x_f_plus_f_band_combos", f.reads.single())
        assertEquals(disabledBytes(0), f.writes.single().bytes)
        assertTrue(f.isDisabled(listOf(listOf(0))))
        assertFalse(f.isDisabled(listOf(listOf(1))))
    }

    @Test
    fun `nsa_tt_nrca truth table`() {
        val f = ALL_FEATURES.first { it.id == "nsa_tt_nrca" }
        val base = "/nv/item_files/modem/nr5g/RRC/"
        assertEquals(listOf(base + "cap_control_mrdc_t_plus_t_band_combos", base + "cap_control_nr_t_plus_t_band_combos"), f.reads)
        assertEquals(listOf(listOf(0, 0, 0), listOf(0, 0)), f.writes.map { it.bytes })
        assertTrue(f.isDisabled(listOf(listOf(0, 0, 0), listOf(0, 0))))
        assertFalse(f.isDisabled(listOf(listOf(0, 0, 1), listOf(0, 0))))
        assertFalse(f.isDisabled(listOf(listOf(0, 0, 0))))
    }

    @Test
    fun `segmentation truth table`() {
        val f = ALL_FEATURES.first { it.id == "segmentation" }
        assertEquals("/nv/item_files/modem/nr5g/RRC/cap_msg_segmentation", f.reads.single())
        assertEquals(disabledBytes(0), f.writes.single().bytes)
        assertTrue(f.isDisabled(listOf(listOf(0))))
        assertFalse(f.isDisabled(listOf(listOf(1))))
    }

    @Test
    fun `dss truth table`() {
        val f = ALL_FEATURES.first { it.id == "dss" }
        assertEquals("/nv/item_files/modem/nr5g/RRC/cap_dss_control", f.reads.single())
        assertEquals(disabledBytes(0, 0), f.writes.single().bytes)
        assertTrue(f.isDisabled(listOf(listOf(0, 0))))
        assertFalse(f.isDisabled(listOf(listOf(0, 1))))
    }

    @Test
    fun `slotPath appends _Subscription01 for sim1 and is idempotent`() {
        val p = "/nv/item_files/modem/nr5g/RRC/cap_limit_rf_mimo"
        assertEquals(p, slotPath(p, 0))
        assertEquals(p + "_Subscription01", slotPath(p, 1))
        assertEquals(p + "_Subscription01", slotPath(p + "_Subscription01", 1))
    }

    @Test
    fun `nr_ulca tolerates trailing bytes after the pattern`() {
        val f = ALL_FEATURES.first { it.id == "nr_ulca" }
        assertTrue(
            f.isDisabled(
                listOf(
                    listOf(0),
                    listOf(1, 1, 1, 1, 0, 0, 9),
                    listOf(0, 1, 1),
                    listOf(0, 1, 1, 0, 1, 1),
                    listOf(0, 0),
                ),
            ),
        )
    }

    @Test
    fun `nsa_tt_nrca rejects short all-zero arrays`() {
        val f = ALL_FEATURES.first { it.id == "nsa_tt_nrca" }
        assertFalse(f.isDisabled(listOf(listOf(0), listOf(0, 0))))
    }

    @Test
    fun `r17_2t2t empty inner array is disabled without throwing`() {
        val f = ALL_FEATURES.first { it.id == "r17_2t2t" }
        assertTrue(f.isDisabled(listOf(emptyList<Int>())))
        assertTrue(f.isDisabled(listOf(List(24) { 0 })))
        assertFalse(f.isDisabled(listOf(List(23) { 0 } + listOf(1))))
    }

    @Test
    fun `r16_2t1t with only five arrays is not disabled`() {
        val f = ALL_FEATURES.first { it.id == "r16_2t1t" }
        assertFalse(f.isDisabled(List(5) { listOf(0) }))
    }

    @Test
    fun `nr_1024qam_tdd path, payload and merge`() {
        val f = ALL_FEATURES.first { it.id == "nr_1024qam_tdd" }
        val path = "/nv/item_files/modem/nr5g/RRC/cap_feature_band_nr_v2"
        assertEquals(listOf(path), f.reads)
        assertEquals(listOf(path), f.writes.map { it.path })

        // A new item: every feature on (FF) except bits 233-240.
        val fresh = List(29) { 0xFF } + 0x00 + listOf(0xFF, 0xFF)
        assertEquals(32, fresh.size)
        assertEquals(fresh, f.writes.single().bytes)

        val merge = requireNotNull(f.writes.single().merge)
        // Absent item (null) still produces that payload.
        assertEquals(fresh, merge(null))
        // An existing item keeps every other bit, including ones already cleared.
        val existing = listOf(0xFF, 0xFE, 0x00) + List(27) { 0xFF } + listOf(0xFF, 0x00)
        val merged = merge(existing)
        assertEquals(existing.size, merged.size)
        assertEquals(0, merged[29])
        assertEquals(0xFE, merged[1])
        assertEquals(0x00, merged[2])
        assertEquals(0x00, merged[31])
        // A short item is padded with FF before the byte is cleared, keeping
        // whatever prefix it did carry.
        assertEquals(fresh, merge(List(4) { 0xFF }))
        assertEquals(listOf(0x00, 0xFF) + List(27) { 0xFF } + listOf(0x00) + List(2) { 0xFF }, merge(listOf(0x00, 0xFF)))
        assertEquals(List(29) { 0xFF } + 0x00 + listOf(0xFF, 0xFF), merge(emptyList()))
    }

    @Test
    fun `nr_1024qam_tdd disabled detection follows byte 30`() {
        val f = ALL_FEATURES.first { it.id == "nr_1024qam_tdd" }
        assertEquals("/nv/item_files/modem/nr5g/RRC/cap_feature_band_nr_v2", f.reads.single())
        assertTrue(f.isDisabled(listOf(List(29) { 0xFF } + 0x00 + listOf(0xFF, 0xFF))))
        // Trailing bytes are tolerated, as with the other bitmaps.
        assertTrue(f.isDisabled(listOf(List(29) { 0xFF } + 0x00 + List(20) { 0xFF })))
        assertFalse(f.isDisabled(listOf(List(32) { 0xFF })))
        assertFalse(f.isDisabled(listOf(List(29) { 0xFF })))
        assertFalse(f.isDisabled(listOf(listOf(0x00))))
    }
}
