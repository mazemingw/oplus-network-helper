package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.*
import org.junit.Assert.*
import org.junit.Test

class QmiNeighborPresentationTest {
    @Test fun qmiFallbackIncludesOnePrimaryBeforeBothSecondariesWithoutFakeMeasurements() {
        val snapshot = QmiRadioSnapshot(0, 1000, nrServingChannels = listOf(152650, 504990, 720000, 504990, 152650))
        val ca = qmiNrFrequencyFallback(snapshot, state, 1001)!!
        assertEquals(listOf(504990, 152650, 720000), ca.carriers.map { it.dlArfcn })
        assertEquals(listOf(0, 1, 2), ca.carriers.map { it.ccId })
        assertEquals(listOf("N41", "N28", "N79"), ca.carriers.map { it.bandText })
        assertEquals(293, ca.carriers.first().pci)
        assertTrue(ca.carriers.drop(1).all { it.pci == -1 && it.dlStateRaw == -1 && it.dlBwRaw == -1 })
    }
    private val state = NetworkPanelUiState(cellType = "NR", dataNetworkType = "NR", arfcn = "504990", pci = "293",
        band = "41", mcc = "460", mnc = "15", updatedAt = 1000)
    private fun carrier(channel: Int, band: String = "—") = NrCaCarrier(1, 0, -1, -1, band, channel,
        -1, "—", -1, "—", -1, "—", -1, "—")
    private val lte = QmiCellCandidate(QmiCellInput(QmiCellMode.LTE, "1300", "10", "3"), QmiCellSource.NEIGHBOR, -88.0)
    private val nrFrequency = QmiCellCandidate(QmiCellInput(QmiCellMode.NR_ARFCN, "720000", band = "79"), QmiCellSource.NEIGHBOR)

    @Test fun screenshotChannelsBecomeN28AndN41ForTheRegisteredOperator() {
        assertEquals("N28", qmiNrCaBandLabel(carrier(152650), "460", "15"))
        assertEquals("N41", qmiNrCaBandLabel(carrier(524910), "460", "15"))
    }

    @Test fun ambiguousFrequencyRetainsCandidatesAndReportedBandWins() {
        val text = qmiNrCaBandLabel(carrier(524910))
        assertTrue(text.contains("N7")); assertTrue(text.contains("N41")); assertFalse(text.contains("524910"))
        assertEquals("N7", qmiNrCaBandLabel(carrier(524910, "N7"), "460", "15"))
        assertEquals("N?", qmiNrCaBandLabel(carrier(-1)))
    }

    @Test fun fallbackUpdatesBandLabelsWithoutInventingCarrierMetricsOrLockCandidates() {
        val snapshot = QmiRadioSnapshot(0, 1000, nrServingChannels = listOf(504990, 152650, 524910))
        val ca = qmiNrFrequencyFallback(snapshot, state, 1001)!!
        assertEquals(listOf("N41", "N28", "N41"), ca.carriers.map { it.bandText })
        assertEquals(0, ca.carriers.first().ccId)
        assertEquals(293, ca.carriers.first().pci)
        assertTrue(ca.carriers.drop(1).all { it.pci == -1 })
        assertTrue(ca.carriers.all { it.bandRaw == -1 && it.dlBwRaw == -1 && it.dlStateRaw == -1 })
        assertTrue(qmiCellCandidates(emptyList(), emptyList(), state.copy(arfcn = "-", nrCaInfo = ca), 0, 1001).isEmpty())
    }

    @Test fun timelineShowsRealNeighborsAndDiscardsNrFrequencyOnlyWithoutPci() {
        val result = qmiTimelineNeighbors(QmiRadioSnapshot(0, 1000, listOf(lte, nrFrequency)), state, 0, 1001)
        assertEquals(listOf("LTE"), result.map { it.point.cellType })
        assertEquals("10", result[0].point.pci); assertEquals("-88.0", result[0].point.rsrp)
        assertTrue(result.all { it.point.ci.isEmpty() && it.point.tac == "—" })
    }

    @Test fun staleOtherSimServingAndDuplicateRowsAreExcluded() {
        val serving = QmiCellCandidate(QmiCellInput(QmiCellMode.NR_PCI, "504990", "293", "41"), QmiCellSource.NEIGHBOR)
        val snapshot = QmiRadioSnapshot(0, 1000, listOf(serving, lte, lte))
        assertEquals(1, qmiTimelineNeighbors(snapshot, state, 0, 1001).size)
        assertTrue(qmiTimelineNeighbors(snapshot, state, 1, 1001).isEmpty())
        assertTrue(qmiTimelineNeighbors(snapshot, state, 0, 13001).isEmpty())
        assertTrue(qmiTimelineNeighbors(snapshot, state, 0, 999).isEmpty())
    }

    @Test fun nrFrequencyOnlyLeavesNoNeighborRowsSoServingHistoryCanBeUsed() {
        assertTrue(qmiTimelineNeighbors(QmiRadioSnapshot(0, 1000, listOf(nrFrequency)), state, 0, 1001).isEmpty())
    }

    @Test fun nrNeighborWithActualPciZeroIsAnUsableCellTarget() {
        val nr = QmiCellCandidate(QmiCellInput(QmiCellMode.NR_PCI, "640000", "0", "78"), QmiCellSource.NEIGHBOR, -95.0)
        val point = qmiTimelineNeighbors(QmiRadioSnapshot(0, 1000, listOf(nr)), state, 0, 1001).single().point
        assertEquals("0", point.pci)
        assertEquals("0", qmiQuickLockRequest(1, 8, point, QmiQuickLockKind.CELL).cellInput().pcis)
    }
}
