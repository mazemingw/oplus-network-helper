package dev.ririxi.bunnylock.root

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object Sys {
    // A command's children can keep its output open after it has ended or been killed. They only get this long.
    private const val OUTPUT_DRAIN_MS = 1000L
    private const val SHELL_TIMEOUT_SECONDS = 15L

    fun prop(key: String): String =
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
        }.getOrDefault("")

    fun sh(command: String): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        // Reading on this thread would wait for the command to close its output, so the timeout could never cut in.
        val reader = thread(name = "bunny-sh", isDaemon = true) { runCatching { process.inputStream.copyTo(output) } }
        if (!process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        reader.join(OUTPUT_DRAIN_MS)
        return output.toString(Charsets.UTF_8.name()).trim()
    }

    /** [hints] is lowercase. A Tensor Pixel with a MediaTek modem only names it in its RIL, as in "MTK-t900-Release". */
    fun isMediatek(hints: String) = "mediatek" in hints || Regex("\\bmt\\d{4}|\\bmtk").containsMatchIn(hints)
}
