package com.nvmex.networkhelper.util.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogBufferTest {
    @Test fun repeatsWithChangingObjectAddressesRemainOneRow() {
        val buffer = DiagnosticLogBuffer()
        repeat(1000) { buffer.append(DiagnosticLevel.INFO, "HWUI", "view@${(0xabcdef + it).toString(16)}", now = 1000L + it) }
        assertEquals(1, buffer.snapshot().size)
        assertEquals(1000, buffer.snapshot().single().repeats)
    }
    @Test fun burstAndStorageAreBothBounded() {
        val buffer = DiagnosticLogBuffer(maxEntries = 10, maxBytes = 5000)
        repeat(1000) { buffer.append(DiagnosticLevel.ERROR, "test", "error $it", now = 1000) }
        assertTrue(buffer.snapshot().size <= 10)
        assertEquals(970L, buffer.dropped)
        buffer.append(DiagnosticLevel.ERROR, "test", "x".repeat(1200), "y".repeat(6000), now = 3000)
        assertTrue(buffer.snapshot().isEmpty()) // A single over-budget row cannot bypass the cap.
    }
    @Test fun frameworkNoiseDoesNotBecomeAnApplicationError() {
        assertEquals(DiagnosticLevel.INFO, DiagnosticLogPolicy.level("HWUI", "E", "dequeueBuffer vulkan"))
        assertEquals(DiagnosticLevel.ERROR, DiagnosticLogPolicy.level("HWUI", "E", "fatal signal SIGSEGV"))
        assertEquals(DiagnosticLevel.ERROR, DiagnosticLogPolicy.level("MyFeature", "E", "read failed"))
        assertTrue(DiagnosticLogPolicy.expectedModemRefusal("efs", "target rejected: bad length (0x15)"))
        assertFalse(DiagnosticLogPolicy.expectedModemRefusal("efs", "helper closed the connection"))
    }
    @Test fun restoreAndClearRespectLimitsAndPrivacy() {
        val buffer = DiagnosticLogBuffer(maxEntries = 2)
        buffer.restore((1..5).map { DiagnosticLogEntry(it.toLong(), it.toLong(), it.toLong(), DiagnosticLevel.WARNING,
            "test", "error $it IMEI1=123456789012345") })
        assertEquals(2, buffer.snapshot().size)
        assertFalse(buffer.snapshot().joinToString().contains("123456789012345"))
        buffer.clear(); assertTrue(buffer.snapshot().isEmpty())
    }
    @Test fun defaultBufferRetainsMoreThanThePrevious400RowLimit() {
        val buffer = DiagnosticLogBuffer()
        repeat(1000) { buffer.append(DiagnosticLevel.INFO, "test", "entry $it", now = it * 1000L) }
        assertEquals(1000, buffer.snapshot().size)
        assertEquals(16 * 1024 * 1024, DiagnosticLogLimits.MAX_BYTES)
    }
}
