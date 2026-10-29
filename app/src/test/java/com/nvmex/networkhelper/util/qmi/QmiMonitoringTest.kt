package com.nvmex.networkhelper.util.qmi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QmiMonitoringTest {
    private fun bindFailure() = QmiCommandException("nas_read", "Monitoring: SIM 1 bind failed.", -1, "daemon")

    @Test fun transientBindFailureRecoversWithinTheSameReadOnlyOwner() = runBlocking {
        var reads = 0; var opens = 0; val pauses = mutableListOf<Long>()
        val result = qmiRecoverMonitoring(read = { if (++reads == 1) throw bindFailure(); 42 },
            reopen = { opens++ }, pause = { pauses += it })
        assertEquals(42, result); assertEquals(2, reads); assertEquals(1, opens)
        assertEquals(listOf(400L), pauses)
    }

    @Test fun persistentFailureStopsAfterTwoReopens() = runBlocking {
        var reads = 0; var opens = 0; val pauses = mutableListOf<Long>(); val failure = bindFailure()
        try {
            qmiRecoverMonitoring(read = { reads++; throw failure }, reopen = { opens++ }, pause = { pauses += it })
            fail("Expected persistent failure")
        } catch (e: QmiCommandException) { assertSame(failure, e) }
        assertEquals(3, reads); assertEquals(2, opens); assertEquals(listOf(400L, 1200L), pauses)
    }

    @Test fun cancellationAndAuthenticationFailureNeverRestartAClient() = runBlocking {
        listOf(CancellationException("left page"), SecurityException("wrong peer")).forEach { failure ->
            var opens = 0
            try {
                qmiRecoverMonitoring(read = { throw failure }, reopen = { opens++ }, pause = { fail("Must not delay") })
                fail("Expected failure")
            } catch (e: Exception) { assertSame(failure, e) }
            assertEquals(0, opens)
        }
    }

    @Test fun recoveryCannotReplayAnyWriteOrManualResetCommand() {
        listOf("nr_set", "rat_set", "mode_set", "session_reset", "nr_cell_lock_pci_set").forEach {
            assertFalse(qmiMonitoringCanRetry(QmiCommandException(it, "Monitoring: bind failed", 5, "daemon")))
        }
        assertFalse(qmiMonitoringCanRetry(IllegalArgumentException("No active SIM")))
        assertFalse(qmiMonitoringCanRetry(QmiCommandException("nas_read", "invalid request", 48, "bad_request")))
        assertTrue(qmiMonitoringCanRetry(IOException("socket closed")))
        assertFalse(qmiMonitoringCanRetry(IOException("Root access required")))
        assertFalse(qmiMonitoringCanRetry(IOException("Permission denied")))
        assertFalse(qmiMonitoringCanRetry(IOException("Malformed QMI response")))
    }

    @Test fun healthyReadDoesNotReopenOrPause() = runBlocking {
        assertEquals(7, qmiRecoverMonitoring(read = { 7 }, reopen = { fail("Must not reopen") }, pause = { fail("Must not delay") }))
    }

    @Test fun radioAndAllRxRatsUseOneCombinedWhitelistedRequest() {
        assertEquals(QmiRadioDecoder.reads, qmiMonitoringReads(false))
        assertEquals(QmiRadioDecoder.reads + QmiRxDecoder.reads, qmiMonitoringReads(true))
        assertEquals(10, qmiMonitoringReads(true).size)
        val reads = JSONArray()
        repeat(QmiRadioDecoder.reads.size) { reads.put(JSONObject().put("status", "not_supported")) }
        repeat(4) { reads.put(JSONObject().put("status", "ok").put("rx", rxFrame())) }
        val decoded = qmiDecodeMonitoring(1, 1000, reads, true)
        assertEquals(1, decoded.radio.slot)
        assertTrue(decoded.radio.neighbors.isEmpty())
        assertEquals(listOf("LTE", "NR", "WCDMA", "GSM"), decoded.rx.map { it.rat })
        assertEquals(-105.0, decoded.rx[1].chains.single().rsrp!!, 0.001)
        assertEquals(-90.0, decoded.rx[0].chains.single().power!!, 0.001)
    }

    @Test fun unsupportedRxStaysUnknownAndIncompleteBatchesAreRejected() {
        val reads = JSONArray(); repeat(qmiMonitoringReads(true).size) { reads.put(JSONObject().put("status", "not_supported")) }
        assertTrue(qmiDecodeMonitoring(0, 1000, reads, true).rx.all { it.chains.isEmpty() })
        try { qmiDecodeMonitoring(0, 1000, JSONArray(), true); fail("Expected incomplete batch failure") }
        catch (_: IllegalArgumentException) { }
        val radio = JSONArray(); repeat(QmiRadioDecoder.reads.size) { radio.put(JSONObject().put("status", "not_supported")) }
        assertTrue(qmiDecodeMonitoring(0, 1000, radio, false).rx.isEmpty())
    }

    @Test fun aFullyTimedOutBatchTriggersSessionRecoveryInsteadOfPollingAStaleSocket() {
        val reads = JSONArray(); repeat(qmiMonitoringReads(true).size) { reads.put(JSONObject().put("status", "timeout")) }
        try { qmiDecodeMonitoring(0, 1000, reads, true); fail("Expected transport failure") }
        catch (e: IOException) { assertTrue(qmiMonitoringCanRetry(e)) }
    }

    private fun rxFrame(): String {
        val chain = ByteBuffer.allocate(21).order(ByteOrder.LITTLE_ENDIAN).put(1).putInt(-900).putInt(-80)
            .putInt(-900).putInt(-1050).putInt(12345).array()
        val buffer = ByteBuffer.allocate(7 + 7 + 3 + chain.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(2).putShort(1).putShort(0x5a).putShort((buffer.capacity() - 7).toShort())
        buffer.put(2).putShort(4).putInt(0).put(0x10).putShort(chain.size.toShort()).put(chain)
        return buffer.array().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
