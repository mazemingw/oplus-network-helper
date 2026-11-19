package dev.ririxi.bunnylock.modem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSelectionTest {
    private fun device(chipset: Chipset, qrtr: Boolean = false, ril: Boolean = false,
                       atNodes: List<String> = emptyList(), pixel: Boolean = false) = DeviceInfo(
        chipset = chipset, qrtr = qrtr, socModel = "", platform = "", manufacturer = "", model = "",
        baseband = "", ril = "", sdk = 36, atNodes = atNodes, defaultAtNode = atNodes.firstOrNull().orEmpty(),
        pixelRouter = pixel, samsungMenu = false, aospChannels = true, aospNrDc = true,
        rilPassthrough = ril, rilPassthroughStatus = null, rilPassthroughChecked = emptyList(), samsungNrMode = false,
    )

    private fun DeviceInfo.suggested(shellOnly: Boolean = false) =
        suggestedBackend(this, availableBackends(this, shellOnly))

    @Test fun autoUsesTheDetectedTransportAndFallsBackToHal() {
        assertEquals(Backend.QUALCOMM, device(Chipset.QUALCOMM, qrtr = true).suggested())
        assertEquals(Backend.MEDIATEK, device(Chipset.MEDIATEK, ril = true).suggested())
        assertEquals(Backend.MEDIATEK, device(Chipset.MEDIATEK, atNodes = listOf("/dev/radio/pttycmd1")).suggested())
        assertEquals(Backend.PIXEL, device(Chipset.TENSOR, pixel = true).suggested())
        assertEquals(Backend.ANDROID, device(Chipset.TENSOR).suggested())
        assertEquals(Backend.UNISOC, device(Chipset.UNISOC, ril = true).suggested())
        assertEquals(Backend.ANDROID, device(Chipset.UNISOC).suggested())
    }

    @Test fun adbDoesNotOfferRootOnlyTransports() {
        val detected = availableBackends(device(Chipset.QUALCOMM, qrtr = true), shellOnly = true)
        assertEquals(setOf(Backend.ANDROID), detected)
        assertFalse(Backend.PIXEL in availableBackends(device(Chipset.TENSOR, pixel = true), shellOnly = true))
        assertTrue(Backend.MEDIATEK in availableBackends(device(Chipset.MEDIATEK, ril = true), shellOnly = true))
    }
}
