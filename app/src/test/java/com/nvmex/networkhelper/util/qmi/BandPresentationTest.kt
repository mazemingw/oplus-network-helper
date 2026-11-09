package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.*
import com.nvmex.networkhelper.util.network.LTE_GLOBAL_BANDS
import com.nvmex.networkhelper.util.network.NR_GLOBAL_BANDS
import org.junit.Assert.*
import org.junit.Test

class BandPresentationTest {
    private val now = 100_000L
    private val hardware = QmiBand.entries.associateWith { when (it) {
        QmiBand.GSM -> setOf(850, 900, 1800)
        QmiBand.WCDMA -> setOf(1, 5, 8)
        QmiBand.LTE -> setOf(1, 3, 5, 8, 34, 38, 39, 40, 41, 66)
        else -> setOf(1, 3, 5, 8, 28, 41, 78, 79, 83)
    } }

    @Test fun mobileAndBroadnetUseTheRequestedBands() {
        for (mnc in listOf("0", "00", "02", "07", "15")) {
            val rule = mainlandBandRule("460", mnc)!!
            assertEquals(setOf(3, 8, 34, 38, 39, 40, 41), rule.lte)
            assertEquals(setOf(3, 8, 28, 41, 78, 79), rule.nr)
        }
    }

    @Test fun unicomAndTelecomBothIncludeB5AndB8() {
        for (mnc in listOf("01", "06", "09", "03", "05", "11")) {
            val rule = mainlandBandRule("460", mnc)!!
            assertEquals(setOf(1, 3, 5, 8), rule.lte)
            assertEquals(setOf(1, 3, 5, 8, 78), rule.nr)
        }
    }

    @Test fun unknownOrNonMainlandPlmnsAreNotGuessed() {
        assertNull(mainlandBandRule("310", "00"))
        assertNull(mainlandBandRule("460", ""))
        assertNull(mainlandBandRule("460", "-"))
        assertNull(mainlandBandRule("460", "001"))
        assertNull(mainlandBandRule("460", "99"))
    }

    @Test fun carrierChoicesAreIntersectedWithHardware() {
        assertEquals(setOf(3, 41), qmiVisibleBands(QmiBand.NR_SA, setOf(1, 3, 41, 83), true, "460", "00"))
        assertEquals(setOf(1, 3, 5, 8), qmiVisibleBands(QmiBand.LTE, hardware.getValue(QmiBand.LTE), true, "460", "11"))
    }

    @Test fun disablingMainlandModeRestoresAllHardwareChoices() {
        assertEquals(hardware[QmiBand.NR_SA], qmiVisibleBands(QmiBand.NR_SA, hardware.getValue(QmiBand.NR_SA), false, "460", "00"))
        assertEquals(hardware[QmiBand.LTE], qmiVisibleBands(QmiBand.LTE, hardware.getValue(QmiBand.LTE), true, "310", "260"))
    }

    @Test fun mainlandRulesDoNotInventGsmOrWcdmaRestrictions() {
        for (domain in listOf(QmiBand.GSM, QmiBand.WCDMA))
            assertEquals(hardware[domain], qmiVisibleBands(domain, hardware.getValue(domain), true, "460", "00"))
    }

    @Test fun helpUsesRealCapabilitiesAndOnlyFallsBackWhenUnknown() {
        val fallback = setOf(3, 8, 41)
        assertEquals(setOf(3), qmiHelpBandNumbers(QmiBand.LTE, setOf(1, 3), fallback, "460", "00"))
        assertTrue(qmiHelpBandNumbers(QmiBand.LTE, emptySet(), fallback, "460", "00").isEmpty())
        assertEquals(fallback, qmiHelpBandNumbers(QmiBand.LTE, null, fallback, "460", "00"))
    }

    @Test fun allCatalogBandsHaveFrequencyOrSulLabels() {
        LTE_GLOBAL_BANDS.forEach { assertTrue(qmiBandButtonLabel(QmiBand.LTE, it.band).endsWith("G")) }
        NR_GLOBAL_BANDS.forEach {
            val label = qmiBandButtonLabel(QmiBand.NR_SA, it.band)
            assertTrue(label.endsWith(if (it.duplex == "SUL") "SUL" else "G"))
        }
    }

    @Test fun requestedFrequencyExamplesAndSulNeverShowFakeDownlinkFrequencies() {
        assertEquals("B3 1.8G", qmiBandButtonLabel(QmiBand.LTE, 3))
        assertEquals("N41 2.6G", qmiBandButtonLabel(QmiBand.NR_SA, 41))
        assertEquals("N83 SUL", qmiBandButtonLabel(QmiBand.NR_SA, 83))
        assertEquals("N84 SUL", qmiBandButtonLabel(QmiBand.NR_NSA, 84))
        assertEquals("N95 SUL", qmiBandButtonLabel(QmiBand.NR_SA, 95))
        assertEquals("B999 —", qmiBandButtonLabel(QmiBand.LTE, 999))
    }

    @Test fun inactiveRatChoicesArePrunedAndCannotWriteHiddenNrSettings() {
        val current = QmiProfile(setOf("nr", "lte"), hardware, "both")
        val selection = qmiEmptyBandSelection(current).copy(rats = setOf("lte"), nrMode = "sa",
            bands = mapOf(QmiBand.NR_SA to setOf(78), QmiBand.LTE to setOf(3)))
        val pruned = qmiPruneSelection(selection, hardware, true, "460", "00")
        assertTrue(pruned.bands.getValue(QmiBand.NR_SA).isEmpty())
        val target = qmiSelectedBandTarget(current, pruned)
        assertEquals(current.bands[QmiBand.NR_SA], target.bands[QmiBand.NR_SA])
        assertEquals("both", target.nrMode)
        assertEquals(listOf("rat_set", "lte_set"), qmiBandChanges(current, target, hardware, false).map { it.getString("cmd") })
    }

    @Test fun changingCarrierPrunesNowHiddenChoices() {
        val selection = QmiProfile(setOf("nr", "lte"), mapOf(QmiBand.NR_SA to setOf(41, 78), QmiBand.LTE to setOf(3, 41)), "both")
        val pruned = qmiPruneSelection(selection, hardware, true, "460", "01")
        assertEquals(setOf(78), pruned.bands[QmiBand.NR_SA])
        assertEquals(setOf(3), pruned.bands[QmiBand.LTE])
    }

    @Test fun activeNrCaCanHighlightMultipleBandsWithoutSelectingThem() {
        val state = serving("NR", "N41", "SA").copy(nrCaInfo = NrCaInfo(0, 0,
            listOf(nrCarrier(78, 2), nrCarrier(79, 1)), now))
        val observation = qmiObserveBands(state, 0, now)
        assertEquals(setOf(41, 78), observation.active(QmiBand.NR_SA))
        assertTrue(observation.active(QmiBand.NR_NSA).isEmpty())
        assertTrue(qmiEmptyBandSelection(QmiProfile(setOf("nr"), hardware, "sa")).bands.values.all { it.isEmpty() })
    }

    @Test fun nsaAnchorAndNrAreMappedToSeparateDomains() {
        val state = serving("NR", "41", "NSA").copy(nsaLteAnchor = serving("LTE", "B3", "-"))
        val observation = qmiObserveBands(state, 0, now)
        assertEquals(setOf(41), observation.active(QmiBand.NR_NSA))
        assertEquals(setOf(3), observation.active(QmiBand.LTE))
        assertTrue(observation.active(QmiBand.NR_SA).isEmpty())
    }

    @Test fun staleAndOtherSlotCaDoNotHighlightCurrentBands() {
        val wrongSlot = serving("NR", "41", "SA").copy(nrCaInfo = NrCaInfo(1, 0, listOf(nrCarrier(78, 2)), now))
        assertEquals(setOf(41), qmiObserveBands(wrongSlot, 0, now).active(QmiBand.NR_SA))
        val stale = wrongSlot.copy(nrCaInfo = wrongSlot.nrCaInfo!!.copy(slot = 0, updatedAt = now - 13_000))
        assertEquals(setOf(41), qmiObserveBands(stale, 0, now).active(QmiBand.NR_SA))
    }

    @Test fun noCaDataStillHighlightsTheServingBand() {
        assertEquals(setOf(3), qmiObserveBands(serving("LTE", "3", "-"), 0, now).active(QmiBand.LTE))
    }

    @Test fun registrationBeforeTheCommandCannotConfirmANewLock() {
        val selection = target(QmiBand.NR_SA, setOf(41))
        val observation = qmiObserveBands(serving("NR", "41", "SA"), 0, now)
        assertTrue(qmiMatchingRegistration(selection, observation, now).isEmpty())
        assertEquals(setOf("N41"), qmiMatchingRegistration(selection, observation, now - 1))
    }

    @Test fun caOutsideTheAllowedMaskPreventsPrematureSuccess() {
        val state = serving("NR", "41", "SA").copy(nrCaInfo = NrCaInfo(0, 0, listOf(nrCarrier(78, 2)), now))
        val observed = qmiObserveBands(state, 0, now)
        assertTrue(qmiMatchingRegistration(target(QmiBand.NR_SA, setOf(41)), observed, now - 1).isEmpty())
        assertEquals(setOf("N41", "N78"), qmiMatchingRegistration(target(QmiBand.NR_SA, setOf(41, 78)), observed, now - 1))
    }

    @Test fun oneOfSeveralAllowedBandsIsEnoughButAnotherActiveRatCannotContradictItsTarget() {
        val selection = QmiProfile(setOf("nr", "lte"), mapOf(QmiBand.NR_NSA to setOf(41, 78), QmiBand.LTE to setOf(3)), "both")
        val state = serving("NR", "41", "NSA").copy(nsaLteAnchor = serving("LTE", "8", "-"))
        assertTrue(qmiMatchingRegistration(selection, qmiObserveBands(state, 0, now), now - 1).isEmpty())
        val good = state.copy(nsaLteAnchor = serving("LTE", "3", "-"))
        assertEquals(setOf("N41", "B3"), qmiMatchingRegistration(selection, qmiObserveBands(good, 0, now), now - 1))
    }

    private fun target(domain: QmiBand, bands: Set<Int>) = QmiProfile(setOf(domain.rat), mapOf(domain to bands), "both")
    private fun serving(rat: String, band: String, mode: String) = NetworkPanelUiState(cellType = rat, band = band, nrMode = mode, updatedAt = now, subId = 1)
    private fun nrCarrier(band: Int, active: Int) = NrCaCarrier(0, 0, 1, band - 1, "N$band", 640000,
        active, "", 100, "100MHz", 0, "", 0, "")
}
