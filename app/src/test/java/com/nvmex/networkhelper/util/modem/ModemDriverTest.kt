package com.nvmex.networkhelper.util.modem

import dev.ririxi.bunnylock.modem.Backend
import org.junit.Assert.*
import org.junit.Test

class ModemDriverTest {
    @Test fun autoUsesDetectionAndManualSelectionIsNeverSilentlyReplaced() {
        assertEquals(Backend.MEDIATEK, driverBackend(ModemDriver.AUTO, Backend.MEDIATEK))
        assertEquals(Backend.PIXEL, driverBackend(ModemDriver.PIXEL, Backend.QUALCOMM))
        assertEquals(Backend.UNISOC, driverBackend(ModemDriver.UNISOC, Backend.ANDROID))
        assertNull(driverBackend(ModemDriver.ORIGINAL_QMI, Backend.ANDROID))
    }
    @Test fun nrPciAcceptsEndpointsAndSeparatorsWithoutDroppingZero() {
        assertEquals(listOf(0, 1007, 1005, 293), parseModemPcis("0，1007,1005 293,", true))
        assertEquals(listOf(293), parseModemPcis("293,293", true))
    }
    @Test fun outOfRangeOrMalformedPciIsRejectedBeforeDispatch() {
        listOf("1008", "-1", "2x", "503,504").forEach { text ->
            val nr = text != "503,504"
            assertThrows(IllegalArgumentException::class.java) { parseModemPcis(text, nr) }
        }
        assertEquals(listOf(0, 503), parseModemPcis("0,503", false))
    }
    @Test fun blankPciMeansFrequencyOnlyNotPciZero() { assertTrue(parseModemPcis("", true).isEmpty()) }
}
