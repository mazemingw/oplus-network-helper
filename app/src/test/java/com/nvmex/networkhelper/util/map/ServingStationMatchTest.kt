package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NrCellParamItem
import org.junit.Assert.*
import org.junit.Test

class ServingStationMatchTest {
    private val lte = LteCellParam(eci = 1234, tac = 1, earfcn = 100, pci = 2, latitude = 31.0, longitude = 121.0)
    private val nr = NrCellParamItem(gcell_id = "1234", nr_tac = 1, nr_arfcn = 100, nr_pci = 2, latitude = 31.0, longitude = 121.0)
    private fun state(rat: String, id: String = "1234") = NetworkPanelUiState(cellType = rat, ci = id, tac = "1", arfcn = "100", pci = "2")

    @Test fun onlyMatchesTheCurrentRatAndActualCellIdentifier() {
        assertEquals(lte, matchServingLte(state("LTE"), listOf(lte)))
        assertNull(matchServingLte(state("NR"), listOf(lte)))
        assertEquals(nr, matchServingNr(state("NR"), listOf(nr)))
        assertNull(matchServingNr(state("LTE"), listOf(nr)))
        assertNull(matchServingLte(state("LTE", "9999"), listOf(lte)))
        assertNull(matchServingNr(state("NR", "9999"), listOf(nr)))
    }

    @Test fun permitsCompleteTacFrequencyPciMatchOnlyIfCellIdIsUnavailable() {
        assertEquals(lte, matchServingLte(state("LTE", "-"), listOf(lte)))
        assertNull(matchServingLte(state("LTE", "-").copy(tac = "-"), listOf(lte)))
        assertNull(matchServingLte(state("LTE", "-"), listOf(lte, lte.copy(eci = 9999, latitude = 30.0))))
    }

    @Test fun doesNotTreatStaleFrequencyOrMissingCoordinatesAsServing() {
        assertNull(matchServingLte(state("LTE").copy(arfcn = "200"), listOf(lte)))
        assertNull(matchServingNr(state("NR"), listOf(nr.copy(longitude = null))))
    }

    @Test fun exactIdentifierAllowsIncompleteFrequencyButNeverAConflictingKnownFrequency() {
        val lteMissing = lte.copy(earfcn = null)
        val nrMissing = nr.copy(nr_arfcn = null)
        assertEquals(lteMissing, matchServingLte(state("LTE"), listOf(lteMissing)))
        assertEquals(nrMissing, matchServingNr(state("NR"), listOf(nrMissing)))
        assertNull(matchServingLte(state("LTE", "-"), listOf(lteMissing)))
        assertNull(matchServingNr(state("NR", "-"), listOf(nrMissing)))
        assertNull(matchServingNr(state("NR"), listOf(nr.copy(nr_arfcn = 200))))
    }

    @Test fun invalidOrAmbiguousCoordinatesNeverBecomeAMapAnchor() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 91.0).forEach { lat ->
            assertNull(matchServingLte(state("LTE"), listOf(lte.copy(latitude = lat))))
            assertNull(matchServingNr(state("NR"), listOf(nr.copy(latitude = lat))))
        }
        assertNull(matchServingNr(state("NR"), listOf(nr.copy(latitude = 0.0, longitude = 0.0))))
        assertNull(matchServingLte(state("LTE"), listOf(lte, lte.copy(latitude = 32.0))))
        assertNull(matchServingNr(state("NR"), listOf(nr, nr.copy(latitude = 32.0))))
    }
}
