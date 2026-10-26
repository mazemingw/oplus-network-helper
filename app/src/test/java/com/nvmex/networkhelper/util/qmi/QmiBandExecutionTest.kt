package com.nvmex.networkhelper.util.qmi

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class QmiBandExecutionTest {
    private val commands get() = listOf(qmiCommand("lte_set"), qmiCommand("mode_set"), qmiCommand("rat_set"))

    @Test fun explicitModeRejectionsDoNotDiscardLaterBandOrRatCommands() {
        listOf(3, 71, 94).forEach { code ->
            val sent = mutableListOf<String>()
            val rejected = qmiExecuteBandCommands(commands, true) {
                val name = it.getString("cmd"); sent += name
                if (name == "mode_set") throw QmiCommandException(name, "rejected", code, "modem_rejected")
            }
            assertTrue(rejected)
            assertEquals(listOf("lte_set", "mode_set", "rat_set"), sent)
        }
    }

    @Test fun knownReadOnlyModeIsSkippedWithoutSkippingOtherCommands() {
        val sent = mutableListOf<String>()
        assertFalse(qmiExecuteBandCommands(commands, false) { sent += it.getString("cmd") })
        assertEquals(listOf("lte_set", "rat_set"), sent)
    }

    @Test fun resourceOrClientFailureIsFatalAndNeverReplayed() {
        listOf(5, 7).forEach { code ->
            val sent = mutableListOf<String>()
            val failure = QmiCommandException("mode_set", "session failure", code, "modem_rejected")
            try {
                qmiExecuteBandCommands(commands, true) {
                    sent += it.getString("cmd")
                    if (it.getString("cmd") == "mode_set") throw failure
                }
                fail("Expected session failure")
            } catch (e: QmiCommandException) { assertSame(failure, e) }
            assertEquals(listOf("lte_set", "mode_set"), sent)
        }
    }

    @Test fun bandRejectionCannotBeReportedAsModeOnlyPartialSuccess() {
        val failure = QmiCommandException("lte_set", "rejected", 3)
        try {
            qmiExecuteBandCommands(commands, true) { throw failure }
            fail("Expected band failure")
        } catch (e: QmiCommandException) { assertSame(failure, e) }
    }

    @Test fun transportFailureAfterAWriteIsNotRetried() {
        var sends = 0
        val failure = IOException("timeout")
        try {
            qmiExecuteBandCommands(commands, true) { sends++; throw failure }
            fail("Expected transport failure")
        } catch (e: IOException) { assertSame(failure, e) }
        assertEquals(1, sends)
    }

    @Test fun bindingRejectionCannotDisableNrModeEditing() {
        var rejected = false
        val failure = QmiCommandException("mode_set", "bind failed", 3, "sim_bind")
        try {
            qmiExecuteBandCommands(listOf(qmiCommand("mode_set")), true, onModeRejected = { rejected = true }) { throw failure }
            fail("Expected bind failure")
        } catch (e: QmiCommandException) { assertSame(failure, e) }
        assertFalse(rejected)
    }

    @Test fun learnedReadOnlyModeSurvivesFailureOfALaterCommand() {
        var rejected = false
        try {
            qmiExecuteBandCommands(commands, true, onModeRejected = { rejected = true }) {
                when (it.getString("cmd")) {
                    "mode_set" -> throw QmiCommandException("mode_set", "unsupported", 94, "modem_rejected")
                    "rat_set" -> throw IOException("timeout")
                }
            }
            fail("Expected transport failure")
        } catch (_: IOException) { }
        assertTrue(rejected)
    }
}
