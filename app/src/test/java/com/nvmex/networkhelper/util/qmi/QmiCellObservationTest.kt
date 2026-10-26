package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QmiCellObservationTest {
    private val nr = NetworkPanelUiState(cellType = "NR", arfcn = "640000", pci = "3", band = "N78",
        ssRsrp = "-85 dBm", rsrp = "-95", updatedAt = 1000)
    private val cell get() = qmiServingCells(nr, 1001).single()
    private fun device(locks: Map<String, String?>, targets: List<QmiCellTarget> = emptyList()) =
        QmiDeviceState(1, QmiProfile(setOf("nr", "lte"), emptyMap(), "both"), null, locks, cellTargets = targets)

    @Test fun servingCellUsesSameNetworkPanelFrequencyPciAndSsRsrp() {
        assertEquals(640000, cell.channel); assertEquals(3, cell.pci); assertEquals(78, cell.band)
        assertEquals(-85.0, cell.rsrp!!, 0.001); assertEquals(1000L, cell.at)
        assertEquals(-95.0, qmiServingCells(nr.copy(ssRsrp = "-"), 1001).single().rsrp!!, 0.001)
    }

    @Test fun staleFutureOrInvalidCellsCannotConfirmRegistration() {
        assertTrue(qmiServingCells(nr, 13001).isEmpty())
        assertTrue(qmiServingCells(nr, 999).isEmpty())
        assertTrue(qmiServingCells(nr.copy(updatedAt = 0), 1001).isEmpty())
        assertTrue(qmiServingCells(nr.copy(pci = "1008"), 1001).isEmpty())
        assertTrue(qmiServingCells(nr.copy(arfcn = "-"), 1001).isEmpty())
        assertTrue(qmiServingCells(null, 1001).isEmpty())
    }

    @Test fun nsaAnchorAndNrServingAreBothObservable() {
        val anchor = nr.copy(cellType = "LTE", arfcn = "1300", pci = "4", band = "B3", rsrp = "-90")
        val cells = qmiServingCells(nr.copy(nrMode = "NSA", nsaLteAnchor = anchor), 1001)
        assertEquals(listOf("NR", "LTE"), cells.map { it.rat })
        assertEquals(-90.0, cells[1].rsrp!!, 0.001)
        val fallback = nr.copy(nrMode = "NSA", anchorAvailable = true, anchorArfcn = "1300", anchorPci = "4", anchorBand = "B3")
        assertEquals(listOf(640000, 1300), qmiServingCells(fallback, 1001).map { it.channel })
    }

    @Test fun pciAloneCannotMatchAnotherFrequencyBandOrRat() {
        val target = QmiCellTarget("NR", 640000, setOf(3), setOf(78))
        assertTrue(qmiCellTargetMatches(target, cell))
        assertFalse(qmiCellTargetMatches(target, cell.copy(channel = 640100)))
        assertFalse(qmiCellTargetMatches(target, cell.copy(pci = 4)))
        assertFalse(qmiCellTargetMatches(target, cell.copy(band = 77)))
        assertFalse(qmiCellTargetMatches(target, cell.copy(band = null)))
        assertFalse(qmiCellTargetMatches(target, cell.copy(rat = "LTE")))
    }

    @Test fun frequencyOnlyAndMultiPciTargetsMatchOnlyTheirActualRestrictions() {
        assertTrue(qmiCellTargetMatches(QmiCellTarget("NR", 640000), cell.copy(pci = 9)))
        assertTrue(qmiCellTargetMatches(QmiCellTarget("NR", 640000, setOf(3, 4)), cell.copy(pci = 4)))
        assertFalse(qmiCellTargetMatches(QmiCellTarget("NR", 640000, setOf(3, 4)), cell.copy(pci = 5)))
        assertFalse(qmiCellTargetMatches(QmiCellTarget("NR"), cell))
    }

    @Test fun gnbRequiresKnownNciAndCorrectIdBitCount() {
        val target = QmiCellTarget("NR", gnbIds = setOf(123L), gnbBits = 24)
        val serving = cell.copy(nci = (123L shl 12) + 20)
        assertTrue(qmiCellTargetMatches(target, serving))
        assertFalse(qmiCellTargetMatches(target, cell))
        assertFalse(qmiCellTargetMatches(target, serving.copy(nci = 124L shl 12)))
        assertFalse(qmiCellTargetMatches(target.copy(gnbBits = 0), serving))
    }

    @Test fun readbackParsesActualLteAndNrLockIdentities() {
        val state = JSONObject("""{"valid":true,"sim":1,"rat":{"nr":true},"nr_mode":"both",
          "lte_cell_lock":{"valid":true,"locks":[{"earfcn":1300,"pci":4}]},
          "nr_cell_lock":{"valid":true,"type":"pci","pci_lock":{"arfcn":640000,"pci":3,"bands":[78]}}}""")
        val parsed = QmiDeviceState.parse(state)
        assertEquals(listOf("LTE", "NR"), parsed.cellTargets.map { it.rat })
        assertEquals(QmiCellLockState.MATCHED, qmiCellLockState(parsed, listOf(cell)))
        assertEquals(QmiCellLockState.OUTSIDE, qmiCellLockState(parsed, listOf(cell.copy(channel = 640100))))
    }

    @Test fun fallingBackToLteCannotConfirmAnNrCellLock() {
        val locked = device(mapOf("lte" to "", "nr" to "active"), listOf(QmiCellTarget("NR", 640000)))
        assertEquals(QmiCellLockState.OUTSIDE, qmiCellLockState(locked, listOf(cell.copy(rat = "LTE", channel = 1300))))
        assertEquals(QmiCellLockState.UNKNOWN, qmiCellLockState(locked, emptyList()))
    }

    @Test fun unsupportedReadbackCannotBeMistakenForUnlockedOrMatched() {
        assertEquals(QmiCellLockState.UNKNOWN, qmiCellLockState(null, listOf(cell)))
        assertEquals(QmiCellLockState.UNKNOWN, qmiCellLockState(device(mapOf("nr" to null)), listOf(cell)))
        assertEquals(QmiCellLockState.UNKNOWN, qmiCellLockState(device(mapOf("lte" to "", "nr" to null)), listOf(cell)))
        assertEquals(QmiCellLockState.NONE, qmiCellLockState(device(mapOf("lte" to "", "nr" to "")), listOf(cell)))
        val multi = device(mapOf("nr" to "NR multi-PCI"))
        assertEquals(QmiCellLockState.UNKNOWN, qmiCellLockState(multi, listOf(cell)))
        assertEquals(QmiCellLockState.MATCHED, qmiCellLockState(multi, listOf(cell), listOf(QmiCellTarget("NR", 640000, setOf(3)))))
    }

    @Test fun latestReadbackTakesPriorityOverLastSubmittedTarget() {
        val locked = device(mapOf("nr" to "active"), listOf(QmiCellTarget("NR", 640100, setOf(3))))
        assertEquals(QmiCellLockState.OUTSIDE, qmiCellLockState(locked, listOf(cell), listOf(QmiCellTarget("NR", 640000, setOf(3)))))
    }

    @Test fun missingBandOrNciDoesNotAssertThatTheServingCellIsOutsideTheLock() {
        val locked = device(mapOf("nr" to "active"), listOf(QmiCellTarget("NR", 640000, setOf(3), setOf(78))))
        assertEquals(QmiCellLockState.UNKNOWN, qmiCellLockState(locked, listOf(cell.copy(band = null))))
        assertEquals(QmiCellLockState.OUTSIDE, qmiCellLockState(locked, listOf(cell.copy(channel = 640100, band = null))))
        val gnb = locked.copy(cellTargets = listOf(QmiCellTarget("NR", gnbIds = setOf(123L), gnbBits = 24)))
        assertEquals(QmiCellLockState.UNKNOWN, qmiCellLockState(gnb, listOf(cell)))
    }

    @Test fun registrationConfirmationRequiresANewSampleAndAnActualActiveLock() {
        val target = QmiCellTarget("NR", 640000, setOf(3), setOf(78))
        val locked = device(mapOf("nr" to "active"), listOf(target))
        assertEquals(listOf(cell), qmiCellRegistrationMatches(locked, listOf(target), listOf(cell), 999))
        assertTrue(qmiCellRegistrationMatches(locked, listOf(target), listOf(cell), 1000).isEmpty())
        assertTrue(qmiCellRegistrationMatches(locked.copy(locks = mapOf("nr" to "")), listOf(target), listOf(cell), 999).isEmpty())
        assertTrue(qmiCellRegistrationMatches(locked.copy(locks = mapOf("nr" to null)), listOf(target), listOf(cell), 999).isEmpty())
        assertTrue(qmiCellRegistrationMatches(locked.copy(cellTargets = listOf(target.copy(channel = 640100))), listOf(target), listOf(cell), 999).isEmpty())
    }

    @Test fun inputTargetsReuseCommandValidationAndKeepAllPcis() {
        assertEquals(QmiCellTarget("LTE", 1300, setOf(3, 4)),
            qmiCellTargetsFromInput(QmiCellInput(QmiCellMode.LTE, "1300", "3,4")).single())
        assertEquals(QmiCellTarget("NR", 640000, setOf(3), setOf(78)),
            qmiCellTargetsFromInput(QmiCellInput(QmiCellMode.NR_PCI, "640000", "3", "78")).single())
        assertTrue(qmiCellTargetsFromInput(QmiCellInput(QmiCellMode.PLMN, plmn = "46001")).isEmpty())
    }

    @Test fun unknownSignalDoesNotBecomeAZeroStrengthReading() {
        listOf("-", "NaN", "Infinity", "0", "-999", "").forEach { assertNull(qmiRsrp(it)) }
        assertEquals(-101.0, qmiRsrp(" -101 dBm ")!!, 0.001)
    }
}
