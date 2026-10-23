package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.menu.LteCellParamRow
import com.nvmex.networkhelper.model.menu.LteCellQueryResp
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.map.LteSiteQueryResp
import com.nvmex.networkhelper.network.model.NrSiteAnchor
import com.nvmex.networkhelper.network.model.NrSiteQueryResp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ServingStationLookupTest {
    private val state = NetworkPanelUiState(cellType = "LTE", ci = "1234", tac = "1", arfcn = "100", pci = "2")
    private val row = LteCellParam(eci = 1234, earfcn = 100, latitude = 31.0, longitude = 121.0)
    private suspend fun lookup(local: suspend () -> List<LteCellParam> = { emptyList() },
        site: suspend () -> List<LteCellParam> = { emptyList() },
        cell: suspend () -> List<LteCellParam> = { emptyList() }) = lookupServingStation(local, site, cell,
        match = { matchServingLte(state, it) }, candidates = { servingLteCandidates(state, it) },
        hasPosition = { validStationCoordinates(it.latitude, it.longitude) })

    @Test fun offlineLocalCellWorksEvenWithMissingFrequencyWithoutAnyHttpRequest() = runBlocking {
        val saved = row.copy(earfcn = null)
        assertEquals(saved, lookup(local = { listOf(saved) }, site = { error("Unexpected HTTP") },
            cell = { error("Unexpected HTTP") }).anchor)
    }

    @Test fun unavailableSiteEndpointFallsBackToTheHomeCellEndpoint() = runBlocking {
        val home = LteCellQueryResp("primary_eci", 1, listOf(LteCellParamRow(eci = 1234,
            latitude = 31.0, longitude = 121.0, cell_name = "首页已匹配")))
        val result = lookup(site = { throw IOException("site unavailable") }, cell = { home.stationRows() })
        assertEquals("首页已匹配", result.anchor?.cell_name)
    }

    @Test fun wrongCellInSiteDataDoesNotBlockHomeFallbackOrGetUsedAsServing() = runBlocking {
        assertEquals(row, lookup(site = { listOf(row.copy(eci = 9999)) }, cell = { listOf(row) }).anchor)
        assertNull(lookup(site = { listOf(row.copy(eci = 9999)) }, cell = { listOf(row.copy(eci = 8888)) }).anchor)
    }

    @Test fun incompleteLocalOrSiteCoordinatesCanBeCompletedByHomeQuery() = runBlocking {
        val result = lookup(local = { listOf(row.copy(latitude = null)) },
            site = { listOf(row.copy(longitude = null)) }, cell = { listOf(row) })
        assertEquals(row, result.anchor)
        assertFalse(result.matchedWithoutPosition)
    }

    @Test fun cellWithoutCoordinatesHasAnExplicitStatusAndIsNeverDrawn() = runBlocking {
        val result = lookup(local = { listOf(row.copy(latitude = null)) })
        assertNull(result.anchor)
        assertTrue(result.matchedWithoutPosition)
        assertFalse(lookup(site = { listOf(row.copy(eci = 9999, latitude = null)) }).matchedWithoutPosition)
    }

    @Test fun exactSiteAnchorsOutsideSectorListsAreIncludedForBothRats() {
        val lte = LteSiteQueryResp(anchor = row, data = listOf(row.copy(eci = 9999)))
        assertEquals(row, matchServingLte(state, lte.stationRows()))
        val nr = NrSiteQueryResp(anchor = NrSiteAnchor(gcell_id = "1234", nr_arfcn = 100,
            latitude = 31.0, longitude = 121.0))
        assertNotNull(matchServingNr(state.copy(cellType = "NR"), nr.stationRows()))
    }

    @Test fun cancellationDuringSimSwitchDoesNotRunAnotherQuery() = runBlocking {
        var fallbackCalled = false
        try {
            lookup(site = { throw CancellationException("SIM switched") }, cell = { fallbackCalled = true; listOf(row) })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(fallbackCalled)
    }

    @Test fun completeNetworkFailureRemainsAnErrorRatherThanNoMatch() = runBlocking {
        try {
            lookup(site = { throw IOException("site offline") }, cell = { throw IOException("cell offline") })
            fail("Network error must propagate")
        } catch (error: IOException) { assertEquals("cell offline", error.message) }
    }
}
