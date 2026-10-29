package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.CellTimelinePoint
import org.junit.Assert.*
import org.junit.Test

class QmiQuickLockTest {
    private val nr = CellTimelinePoint(1000, "NR", "1", "0", "123", "N78", "-90", "-10", "20", "640000", "460", "01")
    private val hardware = QmiBand.entries.associateWith { if (it.rat == "nr") setOf(1, 3, 41, 78) else if (it == QmiBand.LTE) setOf(1, 3) else emptySet() }
    private val profile = QmiProfile(setOf("lte", "nr"), hardware, "both")
    private fun request(point: CellTimelinePoint = nr, kind: QmiQuickLockKind = QmiQuickLockKind.CELL) = qmiQuickLockRequest(2, 8, point, kind)

    @Test fun fddDefaultsTo15AndTddTo30() {
        listOf("1", "3", "5", "8", "28", "N66").forEach { assertEquals(15, qmiDefaultScs(it)) }
        listOf("38", "40", "41", "77", "78", "79").forEach { assertEquals(30, qmiDefaultScs(it)) }
    }

    @Test fun unknownAndSulKeepManualScs() {
        listOf("", "999", "83").forEach {
            assertNull(qmiDefaultScs(it))
            assertEquals(60, qmiCellWithDefaultScs(QmiCellInput(QmiCellMode.NR_PCI, band = it, scs = 60)).scs)
        }
    }

    @Test fun changingNrBandRecomputesScsWhileLteDoesNotUseNrScs() {
        val fdd = qmiCellWithDefaultScs(QmiCellInput(QmiCellMode.NR_PCI, band = "3", scs = 30))
        assertEquals(15, fdd.scs)
        assertEquals(30, qmiCellWithDefaultScs(fdd.copy(band = "78")).scs)
        val lte = QmiCellInput(QmiCellMode.LTE, band = "3", scs = 60)
        assertEquals(lte, qmiCellWithDefaultScs(lte))
    }

    @Test fun frequencyOnlyDefaultsOnlyWhenDuplexIsUnambiguous() {
        assertEquals(30, qmiCellWithDefaultScs(QmiCellInput(QmiCellMode.NR_ARFCN, channel = "640000", scs = 15)).scs)
        assertEquals(15, qmiCellWithDefaultScs(QmiCellInput(QmiCellMode.NR_ARFCN, channel = "368000", scs = 30)).scs)
        // 2650 MHz is shared by n7 FDD and n41 TDD.
        assertEquals(60, qmiCellWithDefaultScs(QmiCellInput(QmiCellMode.NR_ARFCN, channel = "530000", scs = 60)).scs)
    }

    @Test fun timelineKeepsPhysicalSimAndSubscriptionAcrossSerialization() {
        val request = request()
        val restored = QmiQuickLockRequest.parse(request.json())
        assertEquals(request, restored)
        assertEquals(2, restored.sim); assertEquals(8, restored.subId)
        assertEquals("0", restored.cellInput().pcis)
        assertEquals(30, restored.cellInput().scs)
    }

    @Test fun fddTimelineCellPrefills15KhzAndDoesNotConvertItsFrequency() {
        val request = request(nr.copy(band = "N3", arfcn = "368000"))
        assertEquals("368000", request.cellInput().channel)
        assertEquals(15, request.cellInput().scs)
    }

    @Test fun incompleteCellCanStillOfferBandSelection() {
        val point = nr.copy(arfcn = "-", pci = "-")
        rejects { request(point) }
        assertEquals(78, request(point, QmiQuickLockKind.BAND).band)
        rejects { request(nr.copy(band = "-"), QmiQuickLockKind.BAND) }
    }

    @Test fun bandShortcutOnlySelectsTargetDomainsAndPreservesOtherAllowedRats() {
        val request = request(kind = QmiQuickLockKind.BAND)
        val draft = qmiQuickBandSelection(request, profile, hardware, true, "460", "01")
        assertEquals(profile.rats, draft.rats)
        assertEquals(setOf(78), draft.bands[QmiBand.NR_SA])
        assertEquals(setOf(78), draft.bands[QmiBand.NR_NSA])
        assertTrue(draft.bands[QmiBand.LTE].isNullOrEmpty())
        assertEquals(hardware, profile.bands) // Modem readback is not mutated by preparing a draft.
    }

    @Test fun shortcutNeverBypassesHardwareOrOperatorFiltering() {
        rejects { qmiQuickBandSelection(request(), profile, emptyMap(), true, "460", "01") }
        rejects { qmiQuickBandSelection(request(nr.copy(band = "41")), profile, hardware, true, "460", "01") }
        assertEquals(setOf(41), qmiQuickBandSelection(request(nr.copy(band = "41")), profile, hardware, false, "460", "01").bands[QmiBand.NR_SA])
    }

    @Test fun lteShortcutSelectsLteAndDoesNotTouchNrBandDrafts() {
        val request = request(nr.copy(cellType = "LTE", band = "B3", arfcn = "1300", pci = "503"), QmiQuickLockKind.BAND)
        val draft = qmiQuickBandSelection(request, profile, hardware, true, "460", "01")
        assertEquals(setOf(3), draft.bands[QmiBand.LTE])
        assertTrue(draft.bands[QmiBand.NR_SA].isNullOrEmpty())
    }

    @Test fun invalidSimOrUnsupportedRatCannotBecomeAQuickTarget() {
        rejects { qmiQuickLockRequest(0, 8, nr, QmiQuickLockKind.CELL) }
        rejects { qmiQuickLockRequest(3, 8, nr, QmiQuickLockKind.CELL) }
        rejects { qmiQuickLockRequest(1, -1, nr, QmiQuickLockKind.CELL) }
        rejects { request(nr.copy(cellType = "GSM"), QmiQuickLockKind.BAND) }
        rejects { request(nr.copy(pci = "1008")) }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected invalid shortcut") } catch (_: Exception) { }
    }
}
