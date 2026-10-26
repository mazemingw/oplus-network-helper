package com.nvmex.networkhelper.util.qmi

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class QmiSessionRecoveryTest {
    private fun exhausted() = QmiCommandException("session_reset", "Client IDs exhausted", 5, "sim_bind")

    @Test fun healthyResetDoesNotStopOtherApplications() {
        assertEquals("ready", qmiResetWithRecovery(reset = { "ready" }, recover = { fail("Unexpected cleanup") }))
    }

    @Test fun exhaustedNasBindCleansUpBeforeExactlyOneRetry() {
        val events = mutableListOf<String>()
        var attempts = 0
        val result = qmiResetWithRecovery(reset = {
            events += "reset"
            if (++attempts == 1) throw exhausted()
            "ready"
        }, recover = { events += "cleanup" })
        assertEquals("ready", result)
        assertEquals(listOf("reset", "cleanup", "reset"), events)
    }

    @Test fun unrelatedCommandStageAndErrorNeverTriggerCleanup() {
        listOf(
            QmiCommandException("query", "error", 5, "sim_bind"),
            QmiCommandException("session_reset", "error", 5, "state_read"),
            QmiCommandException("session_reset", "error", 7, "sim_bind")
        ).forEach { error ->
            try {
                qmiResetWithRecovery(reset = { throw error }, recover = { fail("Unexpected cleanup") })
                fail("Expected original error")
            } catch (actual: QmiCommandException) { assertSame(error, actual) }
        }
    }

    @Test fun persistentExhaustionDoesNotLoopOrRepeatedlyKill() {
        var attempts = 0; var cleanups = 0
        try {
            qmiResetWithRecovery(reset = { attempts++; throw exhausted() }, recover = { cleanups++ })
            fail("Expected persistent failure")
        } catch (_: QmiCommandException) { }
        assertEquals(2, attempts)
        assertEquals(1, cleanups)
    }

    @Test fun failedCleanupIsReportedWithoutAnotherBind() {
        var attempts = 0
        val failure = IOException("Root cleanup denied")
        try {
            qmiResetWithRecovery(reset = { attempts++; throw exhausted() }, recover = { throw failure })
            fail("Expected cleanup failure")
        } catch (actual: IOException) { assertSame(failure, actual) }
        assertEquals(1, attempts)
    }

    @Test fun transportFailureDoesNotStopOtherApplications() {
        val failure = IOException("Socket disconnected")
        try {
            qmiResetWithRecovery(reset = { throw failure }, recover = { fail("Unexpected cleanup") })
            fail("Expected transport failure")
        } catch (actual: IOException) { assertSame(failure, actual) }
    }
}
