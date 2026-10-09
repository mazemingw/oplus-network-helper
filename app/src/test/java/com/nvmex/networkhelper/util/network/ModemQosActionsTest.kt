package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.util.shell.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModemQosActionsTest {
    private fun ok(output: String = "") = ShellResult(0, output, "")

    @Test fun radioResetUsesAirplaneNotDataToggleAndRestoresNormally() = runBlocking {
        val commands = mutableListOf<String>()
        var safetyChecks = 0
        reregisterForQos({ cmd, _ -> commands += cmd; ok(if (commands.size == 1) "disabled" else "") },
            { safetyChecks++ }, { assertEquals(1000L, it) })
        assertEquals(listOf("cmd connectivity airplane-mode", "cmd connectivity airplane-mode enable", "cmd connectivity airplane-mode disable"), commands)
        assertEquals(2, safetyChecks)
    }

    @Test fun rejectsExistingAirplaneAndUnsafeCallsWithoutRadioChanges() {
        val commands = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { reregisterForQos({ cmd, _ -> commands += cmd; ok("enabled") }, {}, {}) }
        }
        assertEquals(listOf("cmd connectivity airplane-mode"), commands)
        commands.clear()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { reregisterForQos({ cmd, _ -> commands += cmd; ok("disabled") }, { error("call active") }, {}) }
        }
        assertTrue(commands.isEmpty())
    }

    @Test fun restoresRadioEvenIfEnablingReturnsFailure() {
        val commands = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                reregisterForQos({ cmd, _ ->
                    commands += cmd
                    if (cmd.endsWith(" enable")) ShellResult(-1, "", "timeout") else ok("disabled")
                }, {}, {})
            }
        }
        assertEquals("cmd connectivity airplane-mode disable", commands.last())
    }

    @Test fun restoresRadioOnCancellationAndRetriesRestoreFailureOnce() {
        val commands = mutableListOf<String>()
        var restoreAttempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                reregisterForQos({ cmd, _ ->
                    commands += cmd
                    if (cmd.endsWith(" disable") && ++restoreAttempts == 1) ShellResult(-1, "", "timeout") else ok("disabled")
                }, {}, { throw CancellationException("cancelled") })
            }
        }
        assertEquals(2, restoreAttempts)
        assertEquals("cmd connectivity airplane-mode disable", commands.last())
    }

    @Test fun cleanupRunsExactlyFiveOriginalTargetsOnceAndSkipsMissingProcesses() {
        val commands = mutableListOf<String>()
        val report = DiagCleanup.run { cmd, _ -> commands += cmd; ok("NOT_RUNNING") }
        assertEquals(6, commands.size) // tool check + five targets
        for (target in DiagCleanup.targets) assertEquals(1, commands.count { "killall -v '$target'" in it })
        assertTrue(report.contains("未运行，跳过"))
        assertFalse(commands.any { "ipacm-diag" in it || "diag_socket_log" in it || "killall -9" in it })
    }

    @Test fun cleanupReportsFailuresAndStillTriesRemainingTargets() {
        var count = 0
        val report = DiagCleanup.run { _, _ -> if (++count == 3) ShellResult(1, "", "denied") else ok("killed") }
        assertEquals(6, count)
        assertTrue(report.contains("清理失败 (denied)"))
        assertTrue(report.contains("diag-router: 已发送结束信号"))
    }
}
