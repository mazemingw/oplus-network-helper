package com.nvmex.networkhelper.util.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SocVendorDetectorTest {
    @Test fun detectsQualcommNamesAndBoardCodes() {
        assertEquals(SocVendor.QUALCOMM, SocVendorDetector.classify(listOf("Qualcomm Technologies, Inc", "SM8750")))
        assertEquals(SocVendor.QUALCOMM, SocVendorDetector.classify(listOf("pineapple")))
    }

    @Test fun detectsMediaTek() {
        assertEquals(SocVendor.MEDIATEK, SocVendorDetector.classify(listOf("MediaTek", "MT6991")))
    }

    @Test fun doesNotTreatUnknownAsQualcomm() {
        assertEquals(SocVendor.OTHER, SocVendorDetector.classify(listOf("samsung exynos")))
        assertEquals(SocVendor.UNKNOWN, SocVendorDetector.classify(emptyList()))
    }
}
