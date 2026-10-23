package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import org.junit.Assert.*
import org.junit.Test

class NrcaBandwidthTest {
    @Test fun keepsLegacySystemBandwidthsIncludingReal200Mhz() {
        val legacy = NrcaBandwidthEncoding.LEGACY
        val expected = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 90, 100, 200, 400)
        assertEquals(expected, (0..16).map(legacy::mhzOrNull))
        assertEquals("41 · 100M", NrcaTranslator.toChipText(40, 14, 14, legacy))
        assertEquals("NR5G_BW_200MHZ", NrcaTranslator.bandwidth(15, legacy))
        assertEquals("NR5G_BW_INVALID", NrcaTranslator.bandwidth(18, legacy))
    }

    @Test fun decodesPyb110Shifted100MhzAndNew3MhzOnBothLinks() {
        val extended = NrcaBandwidthEncoding.WITH_3_MHZ
        assertEquals(3, extended.mhzOrNull(0))
        assertEquals(5, extended.mhzOrNull(1))
        assertEquals(90, extended.mhzOrNull(14))
        assertEquals(100, extended.mhzOrNull(15))
        assertEquals(200, extended.mhzOrNull(16))
        assertEquals(400, extended.mhzOrNull(17))
        assertEquals("NR5G_BW_100MHZ", NrcaTranslator.bandwidth(15, extended))
        assertEquals("41 · 100M", NrcaTranslator.toChipText(40, 15, 15, extended))
        assertEquals("41 · 100M", NrcaTranslator.toChipText(40, 19, 15, extended))
        assertEquals("3M", NrcaTranslator.bandwidthShort(0, extended))
        assertEquals("NR5G_BW_INVALID", NrcaTranslator.bandwidth(19, extended))
    }

    @Test fun switchesOnlyForTheSameCapabilitiesAsTheVendorFramework() {
        val legacy = NrcaBandwidthEncoding.LEGACY
        val extended = NrcaBandwidthEncoding.WITH_3_MHZ
        assertEquals(extended, NrcaBandwidthEncoding.forProperties("SM8975", "0"))
        assertEquals(extended, NrcaBandwidthEncoding.forProperties("SM8750", "1"))
        assertEquals(extended, NrcaBandwidthEncoding.forProperties(null, "1"))
        assertEquals(legacy, NrcaBandwidthEncoding.forProperties("SM8750", "0"))
        assertEquals(legacy, NrcaBandwidthEncoding.forProperties(null, null))
        assertEquals(legacy, NrcaBandwidthEncoding.forProperties("", ""))
        assertEquals(legacy, NrcaBandwidthEncoding.forProperties("SM8850", "true"))
        assertEquals(legacy, NrcaBandwidthEncoding.forProperties("SM8850", "2"))
    }

    @Test fun rejectsReservedInvalidAndMalformedValuesInsteadOfGuessingMhz() {
        for (encoding in NrcaBandwidthEncoding.entries) {
            for (raw in listOf(Int.MIN_VALUE, -1, 18, 19, 20, 100, Int.MAX_VALUE)) {
                assertNull("$encoding raw=$raw", encoding.mhzOrNull(raw))
                assertEquals("-", NrcaTranslator.bandwidthShort(raw, encoding))
            }
        }
        assertNull(NrcaBandwidthEncoding.LEGACY.mhzOrNull(17))
        assertEquals("NR5G_BW_UNKNOWN(18)", NrcaTranslator.bandwidth(18, NrcaBandwidthEncoding.WITH_3_MHZ))
    }
}
