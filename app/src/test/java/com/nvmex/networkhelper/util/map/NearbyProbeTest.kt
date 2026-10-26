package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NearbyCellsResponse
import com.nvmex.networkhelper.network.model.NrCellParamItem
import org.junit.Assert.*
import org.junit.Test

class NearbyProbeTest {
    @Test fun blankRemainsOneUnrestrictedRequestAndListsNormalizeRatAndDuplicates() {
        assertEquals(listOf<String?>(null), nearbyProbeBands(null, null))
        assertEquals(listOf("B1", "B3", "n78"), nearbyProbeBands("b1, B3，N78,B1", null))
        assertEquals(listOf("B1", "B3"), nearbyProbeBands("1,3,B1", "LTE"))
        assertEquals(listOf("n41", "n78"), nearbyProbeBands("41,n78", "NR"))
        assertEquals(listOf("41"), nearbyProbeBands("41", null))
    }

    @Test fun ratConflictsAndInvalidListsFailBeforeAnyNetworkRequest() {
        assertEquals("band_rat_mismatch", assertThrows(IllegalArgumentException::class.java) {
            nearbyProbeBands("B3,n78", "LTE")
        }.message)
        listOf(",", "0", "B-3", "B3 n78", "1.5", (1..33).joinToString(",")).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { nearbyProbeBands(input, null) }
        }
    }

    @Test fun mergeDeduplicatesOverlappingResponsesAndSortsNearestFirst() {
        val far = LteCellParam(id = 1, latitude = 31.01, longitude = 121.0, earfcn = 1650)
        val near = far.copy(id = 2, latitude = 31.001)
        val missingLocation = far.copy(id = 3, latitude = null, longitude = null)
        val nr = NrCellParamItem(id = 1, latitude = 31.002, longitude = 121.0, nr_arfcn = 633984)
        val result = mergeNearbyProbeResults(listOf(
            NearbyCellsResponse(true, listOf(far, missingLocation), listOf(nr)),
            NearbyCellsResponse(true, listOf(far, near), listOf(nr), truncated = true)
        ), 31.0, 121.0)
        assertTrue(result.ok)
        assertTrue(result.truncated)
        assertEquals(listOf(near, far, missingLocation), result.lte)
        assertEquals(listOf(nr), result.nr)
    }

    @Test fun missingIdsDoNotCollapseDistinctCarriersAtTheSameSite() {
        val first = LteCellParam(eci = 123, tac = 100, earfcn = 1650, pci = 5,
            latitude = 31.0, longitude = 121.0)
        val second = first.copy(earfcn = 38950)
        val result = mergeNearbyProbeResults(listOf(
            NearbyCellsResponse(true, listOf(first, second, first))
        ), 31.0, 121.0)
        assertEquals(listOf(first, second), result.lte)
    }
}
