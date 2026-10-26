package com.nvmex.networkhelper.util.map

import org.junit.Assert.*
import org.junit.Test

class DrivePresentationTest {
    @Test fun regularFixAnimationsOverlapTheNextExpectedFix() {
        assertTrue(drivePresentationDuration(1000, false) > 1000)
        assertTrue(drivePresentationDuration(500, true) > 500)
    }

    @Test fun missingOrStaleIntervalsUseBoundedDefaults() {
        assertEquals(1200L, drivePresentationDuration(null, false))
        assertEquals(600L, drivePresentationDuration(null, true))
        assertEquals(1200L, drivePresentationDuration(10000, false))
        assertEquals(250L, drivePresentationDuration(100, false))
        assertEquals(1800L, drivePresentationDuration(2500, false))
    }
}
