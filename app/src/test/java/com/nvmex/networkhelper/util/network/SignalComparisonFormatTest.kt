package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalComparisonFormatTest {
    @Test fun dataSimUsesSubscriptionIdentityAndTracksSwitches() {
        assertEquals(true, comparisonDataSimStatus(1, 1))
        assertEquals(false, comparisonDataSimStatus(5, 1))
        assertEquals(false, comparisonDataSimStatus(1, 5))
        assertEquals(true, comparisonDataSimStatus(5, 5))
        assertNull(comparisonDataSimStatus(null, 1))
        assertNull(comparisonDataSimStatus(-1, 1))
        assertNull(comparisonDataSimStatus(1, -1))
    }

    @Test fun numericBandsGetOnlyBandPrefixes() {
        assertEquals("N41", formatComparisonBand(NetworkPanelUiState(cellType = "NR", band = "41")))
        assertEquals("B3", formatComparisonBand(NetworkPanelUiState(cellType = "LTE", band = "3")))
        assertEquals("N78", formatComparisonBand(NetworkPanelUiState(dataNetworkType = "NR", band = "78")))
    }

    @Test fun prefixedBandsDoNotGetDuplicatePrefixesAndNsaAnchorStaysLte() {
        assertEquals("N78", formatComparisonBand(NetworkPanelUiState(band = " n78 ")))
        assertEquals("B40", formatComparisonBand(NetworkPanelUiState(band = "B40")))
        assertEquals("B3", formatComparisonBand(NetworkPanelUiState(
            cellType = "LTE", dataNetworkType = "NR", nrMode = "NSA", band = "3"
        )))
    }

    @Test fun missingAndOtherGenerationBandsAreNotInvented() {
        assertEquals("-", formatComparisonBand(null))
        assertEquals("-", formatComparisonBand(NetworkPanelUiState()))
        assertEquals("-", formatComparisonBand(NetworkPanelUiState(band = " ")))
        assertEquals("8", formatComparisonBand(NetworkPanelUiState(cellType = "WCDMA", band = "8")))
        assertEquals("N41", formatComparisonBand(NetworkPanelUiState(band = "5G:N41")))
        assertEquals("B3", formatComparisonBand(NetworkPanelUiState(band = "4G:B3")))
    }
}
