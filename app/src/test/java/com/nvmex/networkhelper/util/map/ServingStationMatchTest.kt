package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NrCellParamItem
import org.junit.Assert.*
import org.junit.Test

class ServingStationMatchTest {
    private fun matchLte(state: NetworkPanelUiState, rows: List<LteCellParam>) =
        matchServingLte(state, rows, ServingStationReference(31.0, 121.0))
    private fun matchNr(state: NetworkPanelUiState, rows: List<NrCellParamItem>) =
        matchServingNr(state, rows, ServingStationReference(31.0, 121.0))
    private val lte = LteCellParam(eci = 1234, tac = 1, earfcn = 100, pci = 2, latitude = 31.0, longitude = 121.0)
    private val nr = NrCellParamItem(gcell_id = "1234", nr_tac = 1, nr_arfcn = 100, nr_pci = 2, latitude = 31.0, longitude = 121.0)
    private fun state(rat: String, id: String = "1234") = NetworkPanelUiState(cellType = rat, ci = id, tac = "1", arfcn = "100", pci = "2")

    @Test fun onlyMatchesTheCurrentRatAndActualCellIdentifier() {
        assertEquals(lte, matchLte(state("LTE"), listOf(lte)))
        assertNull(matchLte(state("NR"), listOf(lte)))
        assertEquals(nr, matchNr(state("NR"), listOf(nr)))
        assertNull(matchNr(state("LTE"), listOf(nr)))
        assertNull(matchLte(state("LTE", "9999"), listOf(lte)))
        assertNull(matchNr(state("NR", "9999"), listOf(nr)))
    }

    @Test fun permitsCompleteTacFrequencyPciMatchOnlyIfCellIdIsUnavailable() {
        assertEquals(lte, matchLte(state("LTE", "-"), listOf(lte)))
        assertNull(matchLte(state("LTE", "-").copy(tac = "-"), listOf(lte)))
        assertNull(matchLte(state("LTE", "-"), listOf(lte, lte.copy(eci = 9999, latitude = 31.01))))
    }

    @Test fun channelReconfigurationDoesNotInvalidateAnExactCellIdentifier() {
        assertEquals(lte, matchLte(state("LTE").copy(arfcn = "200"), listOf(lte)))
        assertEquals(nr, matchNr(state("NR").copy(arfcn = "200"), listOf(nr)))
        assertNull(matchLte(state("LTE", "-").copy(arfcn = "200"), listOf(lte)))
        assertNull(matchNr(state("NR", "-").copy(arfcn = "200"), listOf(nr)))
        assertNull(matchNr(state("NR"), listOf(nr.copy(longitude = null))))
    }

    @Test fun exactIdentifierAllowsIncompleteFrequencyWithoutWeakeningUnknownIdMatching() {
        val lteMissing = lte.copy(earfcn = null)
        val nrMissing = nr.copy(nr_arfcn = null)
        assertEquals(lteMissing, matchLte(state("LTE"), listOf(lteMissing)))
        assertEquals(nrMissing, matchNr(state("NR"), listOf(nrMissing)))
        assertNull(matchLte(state("LTE", "-"), listOf(lteMissing)))
        assertNull(matchNr(state("NR", "-"), listOf(nrMissing)))
        assertEquals(nr.copy(nr_arfcn = 200), matchNr(state("NR"), listOf(nr.copy(nr_arfcn = 200))))
    }

    @Test fun screenshotCellMatchesDespiteEngineeringChannelBeingFourStepsHigher() {
        val live = NetworkPanelUiState(cellType = "LTE", ci = "126004493", tac = "30751", pci = "20", arfcn = "38496")
        val engineering = LteCellParam(eci = 126004493, tac = 30751, pci = 20, earfcn = 38500,
            latitude = 22.853732, longitude = 108.327103)
        assertEquals(engineering, matchServingLte(live, listOf(engineering), ServingStationReference(22.853732, 108.327103)))
    }

    @Test fun duplicateIdentifiersPreferTacThenPciThenChannelButDoNotGuessBetweenSites() {
        val wrongTac = lte.copy(tac = 99, latitude = 31.01)
        val wrongPci = lte.copy(pci = 99, latitude = 31.02)
        val oldChannel = lte.copy(earfcn = 200, latitude = 31.03)
        assertEquals(lte, matchLte(state("LTE"), listOf(wrongTac, wrongPci, oldChannel, lte)))
        assertEquals(oldChannel, matchLte(state("LTE"), listOf(wrongTac, wrongPci, oldChannel)))
        assertNull(matchLte(state("LTE"), listOf(oldChannel, oldChannel.copy(latitude = 31.04))))
        assertEquals(nr, matchNr(state("NR"), listOf(nr.copy(nr_tac = 99, latitude = 31.01), nr)))
    }

    @Test fun invalidOrAmbiguousCoordinatesNeverBecomeAMapAnchor() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 91.0).forEach { lat ->
            assertNull(matchLte(state("LTE"), listOf(lte.copy(latitude = lat))))
            assertNull(matchNr(state("NR"), listOf(nr.copy(latitude = lat))))
        }
        assertNull(matchNr(state("NR"), listOf(nr.copy(latitude = 0.0, longitude = 0.0))))
        assertNull(matchLte(state("LTE"), listOf(lte, lte.copy(latitude = 31.01))))
        assertNull(matchNr(state("NR"), listOf(nr, nr.copy(latitude = 31.01))))
    }
}
