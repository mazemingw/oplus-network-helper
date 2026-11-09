package dev.qcom.efs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteHueTest {

    @Test
    fun `same byte value gets the same hue every time`() {
        for (b in 0..255) {
            assertEquals(0f, byteHue(b) - byteHue(b.toByte().toInt()), 0f)
        }
    }

    @Test
    fun `every byte value maps into the hue range`() {
        for (b in 0..255) {
            assertTrue("hue of $b", byteHue(b) in 0f..360f)
        }
    }

    @Test
    fun `all 256 hues are distinct`() {
        assertEquals(256, (0..255).map { byteHue(it) }.toSet().size)
    }

    @Test
    fun `no two hues wrap onto the same colour`() {
        // 360 degrees wraps back to 0 on the wheel, so the hues must stay
        // distinct modulo 360 -- a hue of exactly 360 would collide with 0.
        assertEquals(256, (0..255).map { byteHue(it) % 360f }.toSet().size)
    }

    @Test
    fun `neighbouring values land far apart on the wheel`() {
        // The scramble is the point: "a3" and "a4" must not look nearly alike.
        for (b in 0..254) {
            var d = Math.abs(byteHue(b) - byteHue(b + 1))
            d = minOf(d, 360f - d)
            assertTrue("distance between $b and ${b + 1}", d > 100f)
        }
    }
}
