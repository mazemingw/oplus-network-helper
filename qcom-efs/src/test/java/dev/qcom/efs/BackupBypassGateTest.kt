package dev.qcom.efs

import org.junit.Assert.*
import org.junit.Test

class BackupBypassGateTest {
    @Test fun `confirmation is rejected until five seconds have elapsed and cannot be reused`() {
        var time = 1000L
        val gate = BackupBypassGate { time }
        assertFalse(gate.confirm())
        gate.begin(); assertEquals(5, gate.remainingSeconds())
        time += 4999; assertEquals(1, gate.remainingSeconds()); assertFalse(gate.confirm())
        time++; assertEquals(0, gate.remainingSeconds()); assertTrue(gate.confirm()); assertFalse(gate.confirm())
    }

    @Test fun `cancelling or reopening restarts the full countdown`() {
        var time = 0L
        val gate = BackupBypassGate { time }
        gate.begin(); time = 10000; gate.cancel(); assertFalse(gate.confirm())
        gate.begin(); assertEquals(5, gate.remainingSeconds()); assertFalse(gate.confirm())
    }

    @Test fun `override only relaxes the backup gate without claiming a successful backup`() {
        val failed = UiState(phase = Phase.READY, storageGranted = true, initialBackupError = "synthetic failure")
        assertTrue(failed.canOfferBackupBypass); assertFalse(failed.backupWriteAuthorized)
        val overridden = failed.copy(backupBypassed = true)
        assertTrue(overridden.backupWriteAuthorized); assertFalse(overridden.initialBackupComplete); assertFalse(overridden.cloudBackupComplete)
        assertFalse(overridden.canOfferBackupBypass)
        assertFalse(failed.copy(backupRunning = true).canOfferBackupBypass)
        assertFalse(failed.copy(initialBackupError = null).canOfferBackupBypass)
        assertFalse(failed.copy(phase = Phase.DISCONNECTED).canOfferBackupBypass)
    }
}
