package com.nvmex.networkhelper.util.map

import org.junit.Assert.*
import org.junit.Test

class DriveLocationPolicyTest {
    @Test fun sdkNetworkFixWithoutMonotonicTimestampUsesItsUtcTime() {
        assertEquals(99_000L, driveFixElapsedMillis(0, 1_699_999_999_000L, 100_000, 1_700_000_000_000L))
        assertTrue(driveValidPosition(31.0, 121.0, 150f))
    }
    @Test fun gpsDisplayDoesNotDependOnInertialAccuracyOrCalibration() {
        assertTrue(driveValidPosition(31.0, 121.0, 80f))
    }
    @Test fun successfulNetworkFixRemainsUsableBeforeInertialCalibration() {
        assertTrue(driveValidPosition(31.0, 121.0, 1000f))
        assertEquals(100_000L, driveFixElapsedMillis(0, 0, 100_000, 1_700_000_000_000L))
    }
    @Test fun measurementClockTakesPriorityAndOldCachesDoNotLookFresh() {
        assertEquals(99_000L, driveFixElapsedMillis(99_000_000_000L, 1, 100_000, 1_700_000_000_000L))
        assertNull(driveFixElapsedMillis(1_000_000_000L, 1_700_000_000_000L, 100_000, 1_700_000_000_000L))
        assertNull(driveFixElapsedMillis(0, 1_699_999_900_000L, 100_000, 1_700_000_000_000L))
        assertEquals(100_000L, driveFixElapsedMillis(0, 0, 100_000, 1_700_000_000_000L))
    }
    @Test fun malformedPositionsAndFutureMeasurementsAreRejected() {
        assertFalse(driveValidPosition(Double.NaN, 121.0, 5f))
        assertFalse(driveValidPosition(31.0, 181.0, 5f))
        assertFalse(driveValidPosition(31.0, 121.0, -1f))
        assertNull(driveFixElapsedMillis(200_000_000_000L, 0, 100_000, 1))
    }
}
