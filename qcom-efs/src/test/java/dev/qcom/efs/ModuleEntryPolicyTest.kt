package dev.qcom.efs

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleEntryPolicyTest {
    @Test fun enteringAgainAfterDisconnectAlwaysConnects() {
        repeat(3) {
            assertEquals(ModuleEntryAction.CONNECT, moduleEntryAction(Phase.DISCONNECTED, false, false, false))
            assertEquals(ModuleEntryAction.VERIFY, moduleEntryAction(Phase.READY, false, false, true))
        }
    }
    @Test fun lifecycleDuplicatesDoNotConnectWhileAnotherOperationRuns() {
        assertEquals(ModuleEntryAction.WAIT, moduleEntryAction(Phase.CONNECTING, false, false, false))
        assertEquals(ModuleEntryAction.WAIT, moduleEntryAction(Phase.READY, true, false, true))
        assertEquals(ModuleEntryAction.WAIT, moduleEntryAction(Phase.READY, false, true, true))
        assertEquals(ModuleEntryAction.RECONNECT, moduleEntryAction(Phase.READY, false, false, false))
    }
}
