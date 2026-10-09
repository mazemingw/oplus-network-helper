package com.nvmex.networkhelper.util.wifi

import org.junit.Assert.*
import org.junit.Test
import com.nvmex.networkhelper.model.wifi.WifiChannelSample

class WifiChannelAxisTest {
    private val channels = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
    @Test fun compressesUnusedMiddleWithoutDroppingOccupiedChannels() {
        val axis = WifiChannelAxis.build(channels, listOf(36 to 20, 161 to 20))
        assertTrue(axis.omissions.isNotEmpty())
        assertTrue(axis.position(144f) - axis.position(52f) < 0.3f)
        assertTrue(axis.position(40f) - axis.position(36f) > axis.position(112f) - axis.position(108f))
        assertEquals(1f, axis.position(165f), 0f)
    }
    @Test fun keepsWideOccupiedSpectrumUncompressed() {
        val axis = WifiChannelAxis.build(channels, listOf(114 to 160))
        assertEquals(axis.position(112f) - axis.position(108f), axis.position(120f) - axis.position(116f), 0.0001f)
    }
    @Test fun emptyAxisRemainsUsableAndInterpolationIsMonotonic() {
        val axis = WifiChannelAxis.build((1..14).toList(), emptyList())
        assertTrue(axis.omissions.isEmpty())
        assertTrue(axis.position(6.5f) > axis.position(6f))
        assertTrue(axis.position(6.5f) < axis.position(7f))
        assertEquals(0f, axis.position(-1f), 0f)
    }
    @Test fun wideChannelsUseTheirRealCentersAndSplit80Plus80() {
        val wide = WifiChannelSample("test", frequencyMhz = 5180, channel = 36, levelDbm = -50,
            channelWidthMhz = 160, centerFrequency0Mhz = 5250)
        assertEquals(listOf(50 to 160), wide.occupiedChannelSegments())
        val split = wide.copy(centerFrequency0Mhz = 5210, centerFrequency1Mhz = 5775)
        assertEquals(listOf(42 to 80, 155 to 80), split.occupiedChannelSegments())
        val fallback = wide.copy(centerFrequency0Mhz = null, centerFrequency1Mhz = null)
        assertEquals(listOf(36 to 160), fallback.occupiedChannelSegments())
    }
}
