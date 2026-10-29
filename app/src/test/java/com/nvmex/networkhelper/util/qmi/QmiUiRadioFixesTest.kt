package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.CellTimelinePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QmiUiRadioFixesTest {
    private fun nr(text: String) = QmiCellInput(QmiCellMode.NR_MULTI_PCI, "504990", text, "41", 30)

    @Test fun screenshotInputPromotesToMultiPciAndKeepsFullRangeAndCorrectCommand() {
        val input = qmiCellWithPciText(nr("").copy(mode = QmiCellMode.NR_PCI), "1005,293,")
        assertEquals(QmiCellMode.NR_MULTI_PCI, input.mode)
        val command = qmiCellCommand(input)
        assertEquals("nr_cell_lock_multi_pci_set", command.getString("cmd"))
        assertEquals("[1005,293]", command.getJSONArray("pci_list").toString())
        assertEquals(504990, command.getInt("arfcn")); assertEquals(41, command.getInt("band")); assertEquals(30, command.getInt("scs_khz"))
        assertEquals(setOf(1005, 293), qmiCellTargetsFromInput(input).single().pcis)
    }

    @Test fun pciZero1007ChineseCommasWhitespaceAndDuplicatesAreAccepted() {
        assertEquals("[0,1007]", qmiCellCommand(nr(" 0，1007， 0, \n")).getJSONArray("pci_list").toString())
        val single = qmiCellCommand(nr("1007,").copy(mode = QmiCellMode.NR_PCI))
        assertEquals("nr_cell_lock_pci_set", single.getString("cmd")); assertEquals(1007, single.getInt("pci"))
        assertEquals("[1]", qmiCellCommand(QmiCellInput(channel = "1650", pcis = "1,")).getJSONArray("pci_list").toString())
    }

    @Test fun invalidPcisAndMoreThan64NeverProduceNativeCommand() {
        listOf("", ",， ", "-1", "1008", "4294967296", "293x", (0..64).joinToString(",")).forEach {
            assertTrue(it, runCatching { qmiCellCommand(nr(it)) }.isFailure)
        }
        val result = runCatching { qmiCellCommand(nr("1005,293").copy(mode = QmiCellMode.NR_PCI)) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("multi-PCI"))
        assertEquals(64, qmiCellCommand(nr((944..1007).joinToString(","))).getJSONArray("pci_list").length())
    }

    private fun frame(msg: Int, tag: Int, value: ByteArray): String {
        val b = ByteBuffer.allocate(17 + value.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(2); b.putShort(1); b.putShort(msg.toShort()); b.putShort((b.capacity() - 7).toShort())
        b.put(2); b.putShort(4); b.putInt(0); b.put(tag.toByte()); b.putShort(value.size.toShort()); b.put(value)
        return b.array().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test fun rawReadbackUsesCurrentRatAndDoesNotCallAnotherRatLocked() {
        val locks = qmiDecodeLiveLocks(mapOf(
            0x34 to frame(0x34, 0x11, byteArrayOf(64, 0)),
            0xd7 to frame(0xd7, 0x10, byteArrayOf(0)),
            0x10f to frame(0x10f, 0x10, byteArrayOf(3, 0, 0, 0))))
        val snapshot = QmiRadioSnapshot(1, 1000, locks = locks)
        assertEquals(QmiCurrentLocks(true, true), qmiCurrentLocks(snapshot, "NR", 1, 1001))
        assertEquals(QmiCurrentLocks(false, null), qmiCurrentLocks(snapshot, "LTE", 1, 1001))
        assertEquals(QmiCurrentLocks(null, null), qmiCurrentLocks(snapshot, "NR", 0, 1001))
        assertEquals(QmiCurrentLocks(null, null), qmiCurrentLocks(snapshot, "NR", 1, 13001))
    }

    @Test fun unsupportedAndMalformedLockReadsRemainUnknownAndAutoRatIsUnlocked() {
        assertEquals(QmiLiveLocks(null, mapOf("lte" to null, "nr" to null)), qmiDecodeLiveLocks(emptyMap()))
        val auto = qmiDecodeLiveLocks(mapOf(0x34 to frame(0x34, 0x11, byteArrayOf(0x7c, 0)),
            0x10f to frame(0x10f, 0x10, byteArrayOf(2, 0, 0, 0)),
            0xd7 to frame(0xd7, 0x10, byteArrayOf(1))))
        assertEquals(QmiCurrentLocks(false, false), qmiCurrentLocks(QmiRadioSnapshot(0, 1000, locks = auto), "NR", 0, 1001))
        assertNull(auto.cells["lte"])
    }

    @Test fun realNrNeighborFromSubscriptionCallbackSurvivesAbsentQmiAndNoPciIsStillExcluded() {
        val zero = CellTimelinePoint(1000, "NR", "—", "0", "", "78", "-95", "—", "—", "640000", "460", "01")
        val state = NetworkPanelUiState(subId = 8, cellType = "NR", pci = "2", arfcn = "640000", band = "78",
            mcc = "460", mnc = "01", neighborCells = listOf(zero, zero.copy(pci = "-"), zero.copy(pci = "1007", arfcn = "641000")))
        val rows = qmiTimelineNeighbors(null, state, 0, 1001)
        assertEquals(listOf("0", "1007"), rows.map { it.point.pci })
        assertEquals("0", qmiQuickLockRequest(1, 8, rows.first().point, QmiQuickLockKind.CELL).cellInput().pcis)
        assertEquals(2, qmiCellCandidates(qmiLiveNeighbors(null, state, 0, 1001), emptyList(), null, 0).size)
        assertTrue(qmiTimelineNeighbors(null, state, 0, 13001).isEmpty())
    }

    @Test fun monitoringBatchRemainsWithinNativeLimitAndContainsNoSetter() {
        val commands = qmiMonitoringReads(true).map { it.take(4).toInt(16) }
        assertTrue(commands.size <= 12)
        assertTrue(commands.containsAll(listOf(0x34, 0xd7, 0x10f)))
        assertFalse(commands.any { it in setOf(0x33, 0xd8, 0x10e) })
    }
}
