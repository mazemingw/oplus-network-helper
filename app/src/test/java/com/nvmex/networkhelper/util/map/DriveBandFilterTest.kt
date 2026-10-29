package com.nvmex.networkhelper.util.map

import com.amap.api.maps.model.LatLng
import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import org.junit.Assert.*
import org.junit.Test

class DriveBandFilterTest {
    private fun state(rat: String, band: String) = NetworkPanelUiState(cellType = rat, band = band)
    private fun point(slot: Int, time: Long, rat: String, band: String) = DrivePoint(
        latLng = LatLng(31.0 + time / 100_000_000.0, 121.0), ts = time, simSlot = slot,
        rsrpDbm = -95, isHandover = false, cellKey = "$slot-$rat-$band", snapshot = state(rat, band))

    @Test fun acceptsCommaListsAndNormalizesPrefixesWhitespaceAndDuplicates() {
        val filter = DriveBandFilter(DriveBandFilter.parseLte(" B41,28， 41, "), DriveBandFilter.parseNr("n78, N41"))
        assertEquals(setOf(28, 41), filter.lte)
        assertEquals(setOf(41, 78), filter.nr)
        assertEquals("28,41", filter.lteInput())
        assertEquals("41,78", filter.nrInput())
        assertEquals(filter, DriveBandFilter(DriveBandFilter.parseLte(filter.lteInput()), DriveBandFilter.parseNr(filter.nrInput())))
    }

    @Test fun invalidInputCannotSilentlyProduceAPartialFilter() {
        for (input in listOf("41,abc", "n41", "0", "-28", "41 28", "4.1", "2147483647", ",，")) {
            assertThrows(IllegalArgumentException::class.java) { DriveBandFilter.parseLte(input) }
        }
        assertThrows(IllegalArgumentException::class.java) { DriveBandFilter.parseNr("B28") }
    }

    @Test fun onlyListedRatAndExactBandMatchWhileBlankBothRestoresAll() {
        val onlyLte = DriveBandFilter(lte = setOf(41, 28))
        assertTrue(onlyLte.matches(state("LTE", "41")))
        assertTrue(onlyLte.matches(state("lte", "B28")))
        assertFalse(onlyLte.matches(state("NR", "41")))
        assertFalse(onlyLte.matches(state("LTE", "1")))
        val onlyNr = DriveBandFilter(nr = setOf(41, 28))
        assertTrue(onlyNr.matches(state("NR", "n28")))
        assertFalse(onlyNr.matches(state("LTE", "28")))
        assertTrue(DriveBandFilter().matches(state("WCDMA", "-")))
        assertFalse(DriveBandFilter().active)
    }

    @Test fun unknownOrAmbiguousRecordedBandIsNotInferredFromChannelOrCa() {
        val filter = DriveBandFilter(nr = setOf(41, 78), lte = setOf(3))
        assertFalse(filter.matches(state("NR", "-").copy(arfcn = "627264", anchorBandCombo = "n78")))
        assertFalse(filter.matches(state("NR", "77/78")))
        assertFalse(filter.matches(state("NR", "B41")))
        assertFalse(filter.matches(state("LTE", "31")))
        assertTrue(filter.matches(state("NR", "n41")))
        assertTrue(filter.matches(state("LTE", "B3")))
    }

    @Test fun historyFiltersBothSimsCentersOnAMatchAndKeepsOriginalDataForClearing() {
        val lte = point(0, 1000, "LTE", "3")
        val nr = point(1, 2000, "NR", "41")
        val oldNr = point(0, 3000, "NR", "28")
        val source = DriveHistory(listOf(lte, nr, oldNr))
        val filtered = source.filteredBy(DriveBandFilter(nr = setOf(41, 28)))
        assertSame(nr, filtered.firstPoint)
        assertEquals("28", filtered.snapshots[0]!!.band)
        assertEquals("41", filtered.snapshots[1]!!.band)
        assertEquals(listOf(1000L, 3000L), source.pointsBySlot[0]!!.map { it.ts })
        assertSame(source, source.filteredBy(DriveBandFilter()))
        assertEquals(1, source.filteredBy(DriveBandFilter(nr = setOf(41))).initialSlot(0))
        assertNull(source.filteredBy(DriveBandFilter(nr = setOf(78))).firstPoint)
    }

    @Test fun hiddenBandsSplitTheTrackRatherThanConnectingAcrossExcludedSamples() {
        val points = listOf(41, 41, 78, 41, 41, 28, 28).mapIndexed { i, band -> point(0, (i + 1) * 1000L, "NR", "$band") }
        val filter = DriveBandFilter(nr = setOf(41))
        val fixes = points.map { DriveTrackFix(it.ts, it.latLng!!.latitude, it.latLng!!.longitude) }
        assertEquals(listOf(listOf(0, 1), listOf(3, 4)), driveTrackSegments(fixes) { filter.matches(points[it].snapshot) })
        assertEquals(listOf(points.indices.toList()), driveTrackSegments(fixes))
        assertEquals(emptyList<List<Int>>(), driveTrackSegments(fixes) { false })
    }

    @Test fun importedCsvBandMetadataUsesTheSameFilterAsLiveSnapshots() {
        val state = state("NR", "28")
        val row = DrivePointEntity(sessionId = "s", ts = 1000, lat = 31.0, lng = 121.0,
            rsrpDbm = -95, isHandover = false, cellKey = "n28", snapshotJson = SnapshotJson.toJson(state))
        val imported = DriveCsv.decode(DriveCsv.encode(listOf(row), emptyList(), "")).single().points.single()
        val filter = DriveBandFilter(nr = setOf(28))
        assertTrue(filter.matches(state))
        assertTrue(filter.matches(SnapshotJson.fromJson(imported.snapshotJson)))
    }
}
