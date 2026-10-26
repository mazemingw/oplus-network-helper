package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import org.junit.Assert.*
import org.junit.Test

class StationExclusionTest {
    @Test fun everySelectableBandIncludesBothChannelBoundaries() {
        val ranges = listOf(1 to 0..599, 3 to 1200..1949, 5 to 2400..2649, 8 to 3450..3799,
            34 to 36200..36349, 38 to 37750..38249, 39 to 38250..38649,
            40 to 38650..39649, 41 to 39650..41589)
        assertEquals(StationExclusion.selectableBands, ranges.map { it.first })
        ranges.forEach { (band, range) ->
            val exclusion = StationExclusion(bands = setOf(band))
            assertTrue("B$band start", exclusion.excludes(range.first))
            assertTrue("B$band end", exclusion.excludes(range.last))
            assertFalse("B$band next band", exclusion.excludes(range.last + 1))
        }
    }

    @Test fun exactChannelDoesNotExcludeOtherChannelsOrNrOnTheSameBand() {
        val exclusion = StationExclusion(earfcns = setOf(1650))
        assertTrue(exclusion.excludes(1650))
        assertFalse(exclusion.excludes(1651))
        assertFalse(StationExclusion(bands = setOf(41)).excludes(
            NetworkPanelUiState(cellType = "NR", arfcn = "520000", band = "41")))
        assertTrue(StationExclusion(bands = setOf(3)).excludes(
            NetworkPanelUiState(cellType = "LTE", arfcn = "-", band = "B3")))
        assertFalse(StationExclusion(bands = setOf(3)).excludes(null as Int?))
    }

    @Test fun channelInputAcceptsChineseCommaAndDeduplicatesButRejectsMalformedValues() {
        assertEquals(setOf(0, 1650, 38950), StationExclusion.parseChannels("0,1650， 38950,1650,"))
        assertTrue(StationExclusion.parseChannels(" ").isEmpty())
        listOf("-1", "B3", "1650.0", "2147483648", ",，").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { StationExclusion.parseChannels(input) }
        }
    }

    @Test fun servingLinkIsHiddenOnlyWithPositiveEvidenceOfExcludedLte() {
        val empty = DriveStationSnapshot(rat = "LTE", latitude = 31.0, longitude = 121.0,
            name = "site", sectors = emptyList())
        val exclusion = StationExclusion(bands = setOf(3))
        val lte = NetworkPanelUiState(cellType = "LTE", arfcn = "1650", band = "3")
        assertFalse(StationExclusion().excludes(empty, lte))
        assertFalse(exclusion.excludes(empty, null))
        assertTrue(exclusion.excludes(empty, lte))
        val b3 = DriveStationSector.from(com.nvmex.networkhelper.network.map.LteCellParam(earfcn = 1650))
        val b40 = DriveStationSector.from(com.nvmex.networkhelper.network.map.LteCellParam(earfcn = 38950))
        assertTrue(exclusion.excludes(empty.copy(sectors = listOf(b3)), null))
        assertFalse(exclusion.excludes(empty.copy(sectors = listOf(b3, b40)), null))
        assertFalse(exclusion.excludes(empty.copy(rat = "NR"), lte))
    }
}
