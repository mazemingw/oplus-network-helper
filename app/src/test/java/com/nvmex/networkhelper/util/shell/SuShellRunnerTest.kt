package com.nvmex.networkhelper.util.shell

import org.junit.Assert.*
import org.junit.Test

class SuShellRunnerTest {
    private class Fake(private val master: Boolean, private val uid: Int = 0) : ShellRunner {
        val commands = mutableListOf<List<String>>()
        override fun exec(cmd: List<String>, timeoutMs: Long): ShellResult {
            commands += cmd
            return ShellResult(0, if (cmd.last() == "--help") {
                if (master) "-M, --mount-master" else "-c COMMAND"
            } else if (cmd.last() == "id") "uid=$uid(root) gid=0(root)" else "ok", "")
        }
    }
    @Test fun optsIntoGlobalNamespaceOnlyWhenRequestedAndSupported() {
        val fake = Fake(true)
        val runner = SuShellRunner(fake, mountMaster = true)
        assertTrue(runner.hasSu())
        runner.execSu("wc -c < '/data/user/0/com.oplus.engineernetwork/files/plugin-release.zip'")
        assertEquals(listOf("su", "--help"), fake.commands[0])
        assertEquals(listOf("su", "-M", "-c", "id"), fake.commands[1])
        assertEquals("-M", fake.commands[2][1])
        assertEquals(1, fake.commands.count { it.last() == "--help" })
    }
    @Test fun doesNotPassUnsupportedFlagsOrChangeOtherRootOperations() {
        for ((supported, requested) in listOf(false to true, true to false)) {
            val fake = Fake(supported)
            val runner = SuShellRunner(fake, mountMaster = requested)
            runner.execSu("id")
            assertEquals(listOf("su", "-c", "id"), fake.commands.last())
        }
    }
    @Test fun nonRootUidIsNotMistakenForRoot() {
        assertFalse(SuShellRunner(Fake(false, 10431)).hasSu())
        assertTrue(SuShellRunner(Fake(false)).hasSu())
    }
}
