package com.nvmex.networkhelper.util.modem

import dev.ririxi.bunnylock.modem.*
import org.junit.Assert.*
import org.junit.Test

class ModemDriverTest {
    private fun device(vendor: String = "unknown", qrtr: Boolean = false, mtk: Boolean = false,
                       ports: List<String> = emptyList(), pixel: Boolean = false) = DeviceInfo(
        vendor, qrtr, "", "", "", "", "", "", 36, ports, pixel, false, true, true,
        mtk, null, emptyList(), false)

    @Test fun qrtrWinsAutomaticDetection() { assertEquals(Backend.QUALCOMM, device("qualcomm", qrtr = true).suggested()) }
    @Test fun mediatekRilAndAtPortsAreDetected() {
        assertEquals(Backend.MEDIATEK, device("mediatek", mtk = true).suggested())
        assertEquals(Backend.MEDIATEK, device("mediatek", ports = listOf("/dev/radio/pttycmd1")).suggested())
    }
    @Test fun pixelNeedsItsRouterOtherwiseHalIsUsed() {
        assertEquals(Backend.PIXEL, device("tensor", pixel = true).suggested())
        assertEquals(Backend.ANDROID, device("tensor").suggested())
    }
    @Test fun adbAccessOnlyAllowsHalOrAvailableMediatekRil() {
        val plain = device("qualcomm", qrtr = true)
        assertTrue(plain.allows(Backend.ANDROID, true))
        assertFalse(plain.allows(Backend.QUALCOMM, true))
        assertFalse(plain.allows(Backend.PIXEL, true))
        assertFalse(plain.allows(Backend.MEDIATEK, true))
        assertTrue(device("mediatek", mtk = true).allows(Backend.MEDIATEK, true))
    }
    @Test fun autoUsesDetectionAndManualSelectionIsNeverSilentlyReplaced() {
        assertEquals(Backend.MEDIATEK, driverBackend(ModemDriver.AUTO, Backend.MEDIATEK))
        assertEquals(Backend.PIXEL, driverBackend(ModemDriver.PIXEL, Backend.QUALCOMM))
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
