package dev.qcom.efs.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private fun newer(a: String, b: String) = UpdateChecker.compareVersions(a, b) > 0
    private fun same(a: String, b: String) = UpdateChecker.compareVersions(a, b) == 0

    @Test
    fun `a higher patch, minor or major is newer`() {
        assertTrue(newer("1.2.1", "1.2.0"))
        assertTrue(newer("1.3.0", "1.2.9"))
        assertTrue(newer("2.0.0", "1.9.9"))
    }

    @Test
    fun `an older version is not newer`() {
        assertTrue(!newer("1.2.0", "1.2.1"))
        assertTrue(!newer("1.2.0", "1.2.0"))
    }

    @Test
    fun `the v prefix is ignored on either side`() {
        assertTrue(same("v1.2.0", "1.2.0"))
        assertTrue(newer("v1.2.1", "1.2.0"))
        assertTrue(newer("1.2.1", "v1.2.0"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertTrue(same("1.2", "1.2.0"))
        assertTrue(same("1", "1.0.0"))
        assertTrue(newer("1.2.1", "1.2"))
    }

    @Test
    fun `numbers compare numerically, not as text`() {
        assertTrue(newer("1.10.0", "1.9.0"))
        assertTrue(newer("1.2.10", "1.2.9"))
    }

    @Test
    fun `a pre-release is older than the finished release`() {
        assertTrue(newer("1.3.0", "1.3.0-rc1"))
        assertTrue(!newer("1.3.0-rc1", "1.3.0"))
        assertTrue(newer("1.3.0-rc2", "1.3.0-rc1"))
    }

    @Test
    fun `build metadata does not affect the order`() {
        assertTrue(same("1.2.0+build7", "1.2.0"))
    }

    @Test
    fun `normalize strips only the leading v`() {
        assertEquals("1.2.0", UpdateChecker.normalize("v1.2.0"))
        assertEquals("1.2.0", UpdateChecker.normalize("V1.2.0"))
        assertEquals("1.2.0", UpdateChecker.normalize("  1.2.0 "))
    }

    @Test
    fun `a non-numeric segment does not crash the comparison`() {
        // "1.x.0" degrades to 1.0.0 rather than throwing.
        assertTrue(newer("1.2.0", "1.x.0"))
    }
}
