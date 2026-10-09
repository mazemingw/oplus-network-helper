package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.ModemQosInfo
import com.nvmex.networkhelper.util.shell.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MtkQosReaderTest {
    @Test fun retainsOneFdRestoresTheOriginalStackAndAvoidsRilAndUnsupportedRatFallbacks() {
        var command = ""
        val shell = object : ShellRunner {
            override fun exec(cmd: List<String>, timeoutMs: Long): ShellResult {
                command = cmd.last()
                assertEquals(50000L, timeoutMs)
                return ShellResult(0, "+CGDCONT: 4,\"IPV4V6\",\"ims\"\n+C5GQOSRDP: 4,5,,,,,10240,10240", "")
            }
        }
        val result = MtkQosReader(SuShellRunner(shell), File("/data/user/0/com.nvmex.networkhelper/cache"))
            .read(5, 1, ModemQosInfo.Source.NR_5GSM, false).single()
        assertEquals(5, result.subId)
        assertEquals(10240L, result.downlinkAmbrKbps)
        assertTrue(command.contains("timeout -s TERM 45"))
        assertTrue(command.contains("AT+ESUO=5"))
        assertTrue(command.contains("AT+ESUO=\$nh_initial"))
        assertTrue(command.contains("exec 3<>"))
        assertTrue(command.contains("nh_busy="))
        assertFalse(command.contains("/dev/ttyC2"))
        assertFalse(command.contains("killall"))
        assertFalse(command.contains("AT+CGEQOSRDP="))
        System.getenv("NH_PLUGIN_FIXTURE_DIR")?.let { base ->
            // Parse on the connected Android shell with -n: never opens a modem node.
            File(base, "mtk-probe-syntax.sh").writeText(command.replace("/system/bin/sh -c", "/system/bin/sh -n -c") + "\n")
        }
    }
}
