package com.nvmex.networkhelper.util.map

import org.junit.Assert.*
import org.junit.Test

class MapChannelFrequencyTest {
    @Test fun lteFddAppendsDownlinkAndUplinkFrequencies() {
        assertEquals("DL=1850 MHz  UL=1755 MHz", mapChannelFrequency("LTE", 1650))
        assertEquals("DL=758 MHz  UL=703 MHz", mapChannelFrequency("LTE", 9210))
    }
    @Test fun tddAndDownlinkOnlyBandsDoNotInventAnUplink() {
        assertEquals("频率=2596 MHz (TDD)", mapChannelFrequency("LTE", 40650))
        assertEquals("DL=717 MHz", mapChannelFrequency("LTE", 9660))
    }
    @Test fun nrGlobalRasterIncludesBothFr1RangesAndFr2() {
        assertEquals("频率=700 MHz", mapChannelFrequency("NR", 140000))
        assertEquals("频率=3500.01 MHz", mapChannelFrequency("NR", 633334))
        assertEquals("频率=24250.08 MHz", mapChannelFrequency("NR", 2016667))
    }
    @Test fun unknownRatAndOutOfRangeChannelStayUnknown() {
        assertEquals("频率=—", mapChannelFrequency("LTE", 999999))
        assertEquals("频率=—", mapChannelFrequency("NR", 3279166))
        assertEquals("频率=—", mapChannelFrequency("NR", -1))
        assertEquals("频率=—", mapChannelFrequency("WCDMA", 1650))
        assertEquals("频率=—", mapChannelFrequency("LTE", null))
    }
}
