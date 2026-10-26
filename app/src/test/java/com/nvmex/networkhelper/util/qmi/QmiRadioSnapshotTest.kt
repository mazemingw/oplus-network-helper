package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QmiRadioSnapshotTest {
    private fun bytes(size: Int, fill: ByteBuffer.() -> Unit) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
    private fun frame(msg: Int, vararg tlvs: Pair<Int, ByteArray>): String {
        val payload = ByteArrayOutputStream()
        (listOf(2 to byteArrayOf(0, 0, 0, 0)) + tlvs).forEach { (tag, data) ->
            payload.write(tag); payload.write(data.size and 255); payload.write(data.size shr 8); payload.write(data)
        }
        return (bytes(7) { put(2); putShort(1); putShort(msg.toShort()); putShort(payload.size().toShort()) } + payload.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun channels(vararg values: Int) = bytes(1 + 4 * values.size) { put(values.size.toByte()); values.forEach { putInt(it) } }
    private fun intra(channel: Int = 1300, pci: Int = 10, count: Int = 2) = bytes(19 + 10 * count) {
        put(1); put(byteArrayOf(0x64, 0xf0.toByte(), 0x00)); putShort(1); putInt(100)
        putShort(channel.toShort()); putShort(pci.toShort()); putInt(0); put(count.toByte())
        repeat(count) { putShort((pci + it).toShort()); putShort(-100); putShort((-800 - it * 10).toShort()); putShort(-650); putShort(0) }
    }
    private fun state(channel: String = "640000") = NetworkPanelUiState(cellType = "NR", dataNetworkType = "NR", arfcn = channel, pci = "3", band = "78", mcc = "460", mnc = "01")
    private fun point(type: String = "NR", channel: String = "640000", pci: String = "3", band: String = "78", ts: Long = 100) =
        CellTimelinePoint(ts, type, "1", pci, "100", band, "-90", "-10", "20", channel, "460", "01")

    @Test fun lteIntraNeighborsExcludeServingAndRetainActualPciAndSignal() {
        val snapshot = QmiRadioDecoder.decode(0, 1000, mapOf(0x43 to frame(0x43, 0x13 to intra())))
        val neighbor = snapshot.neighbors.single()
        assertEquals(QmiCellMode.LTE, neighbor.input.mode)
        assertEquals("11", neighbor.input.pcis)
        assertEquals("1300", neighbor.input.channel)
        assertEquals("3", neighbor.input.band)
        assertEquals(-81.0, neighbor.rsrp!!, 0.01)
    }

    @Test fun extendedLteChannelsAreNotTruncatedToSixteenBits() {
        val response = frame(0x43, 0x13 to intra(), 0x27 to bytes(4) { putInt(66436) })
        assertEquals("66436", QmiRadioDecoder.decode(0, 1, mapOf(0x43 to response)).neighbors.single().input.channel)
    }

    @Test fun interFrequencyNeighborsHonorExtendedChannels() {
        val inter = bytes(18) { put(1); put(1); putShort(1300); put(byteArrayOf(0, 0, 0)); put(1)
            putShort(42); putShort(-100); putShort(-900); putShort(-800); putShort(0) }
        val response = frame(0x43, 0x14 to inter, 0x28 to channels(66436))
        val cell = QmiRadioDecoder.decode(1, 1, mapOf(0x43 to response)).neighbors.single()
        assertEquals("66436", cell.input.channel)
        assertEquals("42", cell.input.pcis)
    }

    @Test fun malformedMeasurementDoesNotLeakPartiallyDecodedNeighbors() {
        val malformed = intra(count = 3).dropLast(1).toByteArray()
        assertTrue(QmiRadioDecoder.decode(0, 1, mapOf(0x43 to frame(0x43, 0x13 to malformed))).neighbors.isEmpty())
    }

    @Test fun nrNeighborIsFrequencyOnlyAndNeverFabricatesPci() {
        val response = frame(0xd2, 0x1c to channels(640000), 0x1d to channels(640000, 720000))
        val cell = QmiRadioDecoder.decode(0, 1, mapOf(0xd2 to response)).neighbors.single()
        assertEquals(QmiCellMode.NR_ARFCN, cell.input.mode)
        assertEquals("720000", cell.input.channel)
        assertEquals("", cell.input.pcis)
        assertEquals("79", cell.input.band)
        assertNull(cell.rsrp)
    }

    @Test fun absentOrRejectedMessagesProduceNoInventedCaOrNeighbors() {
        val snapshot = QmiRadioDecoder.decode(0, 1, mapOf(0x43 to "bad", 0xac to frame(0xd2)))
        assertTrue(snapshot.neighbors.isEmpty()); assertNull(snapshot.lteCa); assertTrue(snapshot.nrServingChannels.isEmpty())
    }

    @Test fun lteCaUsesActualPcellAndConfiguredScellsAndKnownBandwidthEnum() {
        val primary = bytes(10) { putShort(10); putShort(1300); putInt(5); putShort(122) }
        val scells = bytes(37) { put(2)
            putShort(11); putInt(1650); putInt(3); putShort(122); putInt(2); put(0); put(1)
            putShort(-1); putInt(-1); putInt(999); putShort(126); putInt(0); put(1); put(0) }
        val info = QmiRadioDecoder.decode(1, 1000, mapOf(0xac to frame(0xac, 0x13 to primary, 0x19 to scells))).lteCa!!
        assertEquals(1, info.slot); assertEquals(2, info.carriers.size)
        assertEquals("20 MHz", info.carriers[0].dlBwText)
        assertEquals("10 MHz", info.carriers[1].dlBwText)
        assertEquals(2, info.carriers[1].scellState)
        assertEquals(3, info.carriers[1].band)
        assertEquals(-1, info.carriers[1].ulEarfcn)
        assertNull(info.carriers[1].rsrp)
    }

    @Test fun invalidCaPciIsRejected() {
        val primary = bytes(10) { putShort(504); putShort(1300); putInt(5); putShort(122) }
        assertNull(QmiRadioDecoder.decode(0, 1, mapOf(0xac to frame(0xac, 0x13 to primary))).lteCa)
    }

    @Test fun lteCaRejectsOtherSlotsStaleAndMismatchedServingCells() {
        val response = frame(0xac, 0x13 to bytes(10) { putShort(10); putShort(1300); putInt(5); putShort(122) })
        val snapshot = QmiRadioDecoder.decode(0, 1000, mapOf(0xac to response))
        val serving = NetworkPanelUiState(cellType = "LTE", dataNetworkType = "LTE", arfcn = "1300", pci = "10")
        assertNotNull(qmiLteCaForState(snapshot, serving, 0, 1001))
        assertNull(qmiLteCaForState(snapshot, serving, 1, 1001))
        assertNull(qmiLteCaForState(snapshot, serving, 0, 14000))
        assertNull(qmiLteCaForState(snapshot, serving.copy(pci = "20"), 0, 1001))
        assertNull(qmiLteCaForState(snapshot, state(), 0, 1001))
    }

    @Test fun candidatePriorityIsNeighborThenMostRecentHistoryThenServing() {
        val neighbor = QmiCellCandidate(QmiCellInput(QmiCellMode.LTE, "1300", "20", "3"), QmiCellSource.NEIGHBOR)
        val result = qmiCellCandidates(listOf(neighbor), listOf(point(pci = "4", ts = 200), point(pci = "5", ts = 300)), state())
        assertEquals(listOf(QmiCellSource.NEIGHBOR, QmiCellSource.HISTORY, QmiCellSource.HISTORY, QmiCellSource.SERVING), result.map { it.source })
        assertEquals(listOf("20", "5", "4", "3"), result.map { it.input.pcis })
    }

    @Test fun duplicateCandidatesKeepHighestPrioritySource() {
        val input = QmiCellInput(QmiCellMode.NR_PCI, "640000", "3", "78")
        val result = qmiCellCandidates(listOf(QmiCellCandidate(input, QmiCellSource.NEIGHBOR)), listOf(point()), state())
        assertEquals(1, result.size); assertEquals(QmiCellSource.NEIGHBOR, result.single().source)
    }

    @Test fun currentMarkerAndLiveSignalSurviveNeighborDeduplication() {
        val input = QmiCellInput(QmiCellMode.NR_PCI, "640000", "3", "78")
        val network = state().copy(updatedAt = 1000, ssRsrp = "-82")
        val result = qmiCellCandidates(listOf(QmiCellCandidate(input, QmiCellSource.NEIGHBOR, -100.0)),
            listOf(point()), network, now = 1001).single()
        assertEquals(QmiCellSource.NEIGHBOR, result.source)
        assertTrue(result.isCurrent)
        assertEquals(-82.0, result.rsrp!!, 0.001)
    }

    @Test fun historicalSignalRemainsAvailableButStaleServingIsNotMarkedCurrent() {
        val network = state().copy(updatedAt = 1000, ssRsrp = "-82")
        val result = qmiCellCandidates(emptyList(), listOf(point()), network, now = 13001).single()
        assertEquals(QmiCellSource.HISTORY, result.source)
        assertFalse(result.isCurrent)
        assertEquals(-90.0, result.rsrp!!, 0.001)
    }

    @Test fun nsaAnchorIsMarkedCurrentAndMissingSignalStaysUnknown() {
        val anchor = NetworkPanelUiState(cellType = "LTE", arfcn = "1300", pci = "10", band = "B3")
        val network = state().copy(updatedAt = 1000, nsaLteAnchor = anchor)
        val result = qmiCellCandidates(emptyList(), emptyList(), network, now = 1001)
        assertEquals(listOf("3", "10"), result.map { it.input.pcis })
        assertTrue(result.all { it.isCurrent })
        assertTrue(result.all { it.rsrp == null })
    }

    private fun caCarrier(pci: Int = 6, channel: Int = 640100) = NrCaCarrier(1, 0, pci, 77, "N78", channel,
        2, "Activated", 14, "100 MHz", 2, "Activated", 14, "100 MHz")

    private fun lteCaCarrier(pci: Int = 6, channel: Int = 1650) =
        LteCaCarrier(1, pci, channel, -1, 3, "B3", 5, "20 MHz", 2, "Activated", 1, null, null, null)

    @Test fun pciCandidatePriorityIncludesCaBetweenHistoryAndCurrent() {
        val neighbor = QmiCellCandidate(QmiCellInput(QmiCellMode.LTE, "1300", "20", "3"), QmiCellSource.NEIGHBOR)
        val network = state().copy(lteCaInfo = LteCaInfo(0, listOf(lteCaCarrier()), 1000))
        val candidates = qmiCellCandidates(listOf(neighbor), listOf(point(pci = "4")), network, slot = 0, now = 1001)
        assertEquals(listOf(QmiCellSource.NEIGHBOR, QmiCellSource.HISTORY, QmiCellSource.CARRIER, QmiCellSource.SERVING), candidates.map { it.source })
        assertEquals(listOf("20", "4", "6", "3"), candidates.map { it.input.pcis })
    }

    @Test fun frequencyOnlyAndInvalidPciNeighborsNeverEnterSelector() {
        val neighbors = listOf(
            QmiCellCandidate(QmiCellInput(QmiCellMode.NR_ARFCN, "720000", band = "79"), QmiCellSource.NEIGHBOR),
            QmiCellCandidate(QmiCellInput(QmiCellMode.LTE, "1300", "504", "3"), QmiCellSource.NEIGHBOR),
            QmiCellCandidate(QmiCellInput(QmiCellMode.NR_PCI, "640000", "1008", "78"), QmiCellSource.NEIGHBOR))
        assertTrue(qmiCellCandidates(neighbors, emptyList(), null).isEmpty())
    }

    @Test fun lteCaCandidatesRequireFreshSameSlotAndValidPci() {
        val base = state().copy(arfcn = "-", lteCaInfo = LteCaInfo(0, listOf(lteCaCarrier(pci = 0)), 1000))
        assertEquals("0", qmiCellCandidates(emptyList(), emptyList(), base, 0, 1001).single().input.pcis)
        assertTrue(qmiCellCandidates(emptyList(), emptyList(), base, 1, 1001).isEmpty())
        assertTrue(qmiCellCandidates(emptyList(), emptyList(), base, 0, 14000).isEmpty())
        val noPci = base.copy(lteCaInfo = base.lteCaInfo!!.copy(carriers = listOf(lteCaCarrier(-1))))
        assertTrue(qmiCellCandidates(emptyList(), emptyList(), noPci, 0, 1001).isEmpty())
    }

    @Test fun caCurrentDuplicateKeepsEarlierHistoricalSource() {
        val network = NetworkPanelUiState(cellType = "LTE", arfcn = "1300", pci = "3", band = "3",
            lteCaInfo = LteCaInfo(0, listOf(lteCaCarrier(3, 1300)), 1000))
        val candidates = qmiCellCandidates(emptyList(), listOf(point(type = "LTE", channel = "1300", band = "3")), network, 0, 1001)
        assertEquals(1, candidates.size)
        assertEquals(QmiCellSource.HISTORY, candidates.single().source)
    }

    @Test fun nrCaIsExcludedEvenWithValidPciAndConfiguredCarrier() {
        val network = state().copy(arfcn = "-", nrCaInfo = NrCaInfo(0, 0, listOf(
            caCarrier(6, 528960).copy(bandRaw = 40, bandText = "N41"),
            caCarrier(7, 513000).copy(bandRaw = 40, bandText = "N41")), 1000))
        assertTrue(qmiCellCandidates(emptyList(), emptyList(), network, 0, 1001).isEmpty())
    }

    @Test fun removingNrCaKeepsMeasuredNrCellsAndLteCaAvailable() {
        val network = state("524910").copy(band = "41",
            nrCaInfo = NrCaInfo(0, 0, listOf(caCarrier(3, 528960).copy(bandRaw = 40, bandText = "N41")), 1000),
            lteCaInfo = LteCaInfo(0, listOf(lteCaCarrier()), 1000))
        val result = qmiCellCandidates(emptyList(), listOf(point(channel = "504990", pci = "7", band = "41")), network, 0, 1001)
        assertEquals(listOf("504990", "1650", "524910"), result.map { it.input.channel })
        assertEquals(listOf(QmiCellSource.HISTORY, QmiCellSource.CARRIER, QmiCellSource.SERVING), result.map { it.source })
    }

    @Test fun lteCaWithPciCanPrefillWhileDeconfiguredCarriersAreOmitted() {
        val carrier = LteCaCarrier(1, 7, 1300, -1, 3, "B3", 5, "20 MHz", 2, "Activated", 1, null, null, null)
        val network = NetworkPanelUiState(lteCaInfo = LteCaInfo(0,
            listOf(carrier, carrier.copy(idx = 2, pci = 8, scellState = 0)), 1000))
        val selected = qmiCellCandidates(emptyList(), emptyList(), network, 0, 1001).single()
        assertEquals(QmiCellSource.CARRIER, selected.source)
        assertEquals(QmiCellMode.LTE, selected.input.mode)
        assertEquals("7", selected.input.pcis)
        assertEquals("1300", selected.input.channel)
    }

    @Test fun incompleteHistoricalCellsAndDifferentOperatorsAreExcluded() {
        val result = qmiCellCandidates(emptyList(), listOf(point(channel = "-"), point(pci = "9999"), point().copy(mnc = "00")), state().copy(arfcn = "-"))
        assertTrue(result.isEmpty())
    }

    @Test fun historicalTargetsRemainAvailableWhileTheSimIsOutOfService() {
        assertEquals(QmiCellSource.HISTORY, qmiCellCandidates(emptyList(), listOf(point()), null).single().source)
    }

    @Test fun oldTimelineWithoutFrequencyCannotInventALockTarget() {
        assertTrue(qmiCellCandidates(emptyList(), listOf(point().copy(arfcn = "-")), state().copy(arfcn = "-")).isEmpty())
    }

    @Test fun qmiNrCaContainsOnlyScellFrequenciesAndUnknownMetrics() {
        val snapshot = QmiRadioSnapshot(0, 1000, nrServingChannels = listOf(640000, 720000))
        val ca = qmiNrFrequencyFallback(snapshot, state(), 1001)!!
        assertTrue(ca.frequencyOnly); assertEquals(1, ca.carriers.size)
        assertEquals(720000, ca.carriers.single().dlArfcn)
        assertEquals(-1, ca.carriers.single().pci)
        assertEquals(-1, ca.carriers.single().dlStateRaw)
        assertEquals(-1, ca.carriers.single().dlBwRaw)
    }

    @Test fun nrFallbackRejectsStaleOtherServingAndSingleCarrierSnapshots() {
        val snapshot = QmiRadioSnapshot(0, 1000, nrServingChannels = listOf(640000, 720000))
        assertNull(qmiNrFrequencyFallback(snapshot, state(), 14000))
        assertNull(qmiNrFrequencyFallback(snapshot, state("620000"), 1001))
        assertNull(qmiNrFrequencyFallback(snapshot, state().copy(dataNetworkType = "LTE"), 1001))
        assertNull(qmiNrFrequencyFallback(snapshot.copy(nrServingChannels = listOf(640000)), state(), 1001))
    }

    @Test fun matchingQmiServingPciCanResolveAndroidVersusQmiChannelDifference() {
        val snapshot = QmiRadioSnapshot(0, 1000, nrServingChannels = listOf(640000, 720000), nrPrimaryChannel = 640000, nrPrimaryPci = 3)
        assertEquals(720000, qmiNrFrequencyFallback(snapshot, state("640032"), 1001)!!.carriers.single().dlArfcn)
        assertNull(qmiNrFrequencyFallback(snapshot.copy(nrPrimaryPci = 4), state("640032"), 1001))
    }

    @Test fun engineeringNrCaWinsThenFreshQmiThenNoDataAcrossSlots() {
        val qmi = qmiNrFrequencyFallback(QmiRadioSnapshot(0, 1000, nrServingChannels = listOf(640000, 720000)), state(), 1001)!!
        val engineering = qmi.copy(frequencyOnly = false)
        assertSame(engineering, qmiChooseNrCa(engineering, qmi, 0, 1001))
        assertSame(qmi, qmiChooseNrCa(engineering.copy(updatedAt = 1), qmi, 0, 12500))
        assertNull(qmiChooseNrCa(engineering, qmi, 1, 1001))
        assertNull(qmiChooseNrCa(null, null, 0, 1001))
    }

    @Test fun actualNrLockClearsWithoutSelectingNrInForm() {
        val commands = qmiActiveCellClearCommands(mapOf("lte" to "", "nr" to "NR-ARFCN 640000 PCI 3", "plmn" to ""))
        assertEquals(listOf("nr_cell_lock_clear"), commands.map { it.getString("cmd") })
    }

    @Test fun simultaneousActualLocksAreAllClearedIncludingPlmn() {
        val commands = qmiActiveCellClearCommands(mapOf("lte" to "PCI 1", "nr" to "PCI 2", "plmn" to "46001"))
        assertEquals(listOf("lte_cell_lock_clear", "nr_cell_lock_clear", "plmn_lock_clear"), commands.map { it.getString("cmd") })
        assertTrue(qmiActiveCellClearCommands(mapOf("lte" to "", "nr" to null)).isEmpty())
    }

    @Test fun coupledNrLockUsesSaToAvoidRelyingOnAnLteNsaAnchor() {
        val profile = QmiProfile(setOf("lte", "nr"), emptyMap(), "nsa")
        val commands = qmiCellRatCommands(QmiCellInput(QmiCellMode.NR_PCI), profile)
        assertEquals(listOf("mode_set", "rat_set"), commands.map { it.getString("cmd") })
        assertEquals("sa", commands[0].getString("mode")); assertEquals("nr", commands[1].getString("rat"))
    }

    @Test fun coupledLteLockDoesNotWriteNrMode() {
        val commands = qmiCellRatCommands(QmiCellInput(QmiCellMode.LTE), QmiProfile(setOf("lte", "nr"), emptyMap(), "both"))
        assertEquals("lte", commands.single().getString("rat"))
    }
}
