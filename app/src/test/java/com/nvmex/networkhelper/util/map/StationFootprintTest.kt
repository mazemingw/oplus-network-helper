package com.nvmex.networkhelper.util.map

import org.junit.Assert.*
import org.junit.Test

class StationFootprintTest {
    @Test fun explicitIndoorAndMicroSitesUseCirclesRegardlessOfBandOrPlaceholderAngle() {
        for (type in listOf("室分", "室内分布系统", "微站", "微蜂窝", "Indoor", "Pico", "small-cell")) {
            assertTrue(type, isOmnidirectionalStation(type, null, 120, 1650))
        }
    }
    @Test fun onlyTheTwoIndoorBandsHaveAMissingOrZeroDirectionFallback() {
        for (channel in listOf(36200,36349,38650,39649)) {
            assertTrue(isOmnidirectionalStation(null, null, null, channel))
            assertTrue(isOmnidirectionalStation(null, null, 0, channel))
            assertFalse(isOmnidirectionalStation(null, null, 90, channel))
        }
        for (channel in listOf(1650,36199,36350,38649,39650,40650)) {
            assertFalse(isOmnidirectionalStation(null, null, 0, channel))
            assertFalse(isOmnidirectionalStation(null, null, null, channel))
        }
    }
    @Test fun explicitNorthFacingMacrosNeverBecomeIndoorBecauseOfBandOrName() {
        for (channel in listOf(36200,38650,40650)) {
            assertFalse(isOmnidirectionalStation("宏站", "某大楼室分覆盖", 0, channel))
        }
        assertTrue(isOmnidirectionalStation(null, "酒店室分", 0, 1650))
    }
}
