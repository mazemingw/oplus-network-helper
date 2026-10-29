package com.nvmex.networkhelper.util.qmi

import android.content.Context
import android.os.Process
import com.nvmex.networkhelper.util.shell.SuShellRunner
import java.io.File
import java.io.IOException

/** Manual recovery only: a rejected NAS bind may require releasing the other app's clients. */
internal fun <T> qmiResetWithRecovery(reset: () -> T, recover: () -> Unit): T {
    try {
        return reset()
    } catch (e: QmiCommandException) {
        if (e.command != "session_reset" || e.stage != "sim_bind" || e.modemCode != 5) throw e
    }
    recover()
    return reset() // Exactly one retry, and no frequency/cell-lock commands.
}

internal class QmiSessionRecovery(private val context: Context) {
    /** Returns true only when the verified competing application was stopped. */
    fun releaseBandMenu(): Boolean {
        val script = File.createTempFile("qmi-recover-", ".sh", context.cacheDir)
        try {
            context.assets.open("qmi/recover-bandmenu-session.sh").use { input ->
                script.outputStream().use { output -> input.copyTo(output) }
            }
            val path = "'" + script.absolutePath.replace("'", "'\"'\"'") + "'"
            val result = SuShellRunner(mountMaster = true).execSu("sh $path ${Process.myUid() / 100000}", 15_000)
            if (result.code != 0) throw IOException("QMI session cleanup failed (${result.code}): ${result.err.takeLast(500)}")
            val stopped = result.out.lineSequence().filter { it.startsWith("bandmenu_stopped=") || it.startsWith("bunny_stopped=") }
                .map { it.substringAfter('=') }.toList()
            if (stopped.size != 2 || stopped.any { it !in setOf("0", "1") }) throw IOException("Invalid backend cleanup response")
            return "1" in stopped
        } finally {
            script.delete()
        }
    }
}
