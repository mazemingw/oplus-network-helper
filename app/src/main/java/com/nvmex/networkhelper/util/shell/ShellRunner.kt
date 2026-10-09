package com.nvmex.networkhelper.util.shell

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

data class ShellResult(
    val code: Int,
    val out: String,
    val err: String
)

interface ShellRunner {
    fun exec(cmd: List<String>, timeoutMs: Long = 1500): ShellResult
    fun exec(cmd: String, timeoutMs: Long = 1500): ShellResult = exec(listOf("sh", "-c", cmd), timeoutMs)
}

class DefaultShellRunner : ShellRunner {

    override fun exec(cmd: List<String>, timeoutMs: Long): ShellResult {
        return try {
            val pb = ProcessBuilder(cmd)
            val p = pb.start()

            // Drain both pipes while the process is running. Waiting first can deadlock
            // root tools with verbose SELinux output or multiple AT responses.
            val stdout = FutureTask { BufferedReader(InputStreamReader(p.inputStream)).readTextSafely() }
            val stderr = FutureTask { BufferedReader(InputStreamReader(p.errorStream)).readTextSafely() }
            Thread(stdout, "shell-stdout").apply { isDaemon = true; start() }
            Thread(stderr, "shell-stderr").apply { isDaemon = true; start() }
            val ok = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ok) {
                p.destroyForcibly()
                runCatching { p.inputStream.close(); p.errorStream.close(); p.outputStream.close() }
                return ShellResult(code = -1, out = "", err = "timeout")
            }

            val out = stdout.get(2, TimeUnit.SECONDS)
            val err = stderr.get(2, TimeUnit.SECONDS)
            ShellResult(code = p.exitValue(), out = out, err = err)
        } catch (e: Exception) {
            ShellResult(code = -2, out = "", err = e.message ?: "exec error")
        }
    }

    private fun BufferedReader.readTextSafely(): String {
        val sb = StringBuilder()
        var line: String?
        while (true) {
            line = readLine() ?: break
            if (sb.length < 512 * 1024) sb.append(line.take(512 * 1024 - sb.length)).append('\n')
        }
        return sb.toString()
    }
}

class SuShellRunner(
    private val base: ShellRunner = DefaultShellRunner(),
    private val mountMaster: Boolean = false
) {
    private val rootArgs by lazy {
        // App data isolation can hide OTHER packages even from an app's uid-0 shell.
        // Only cross-package plugin operations opt into the global mount namespace.
        val supportsMaster = mountMaster && base.exec(listOf("su", "--help"), 2500).let {
            (it.out + it.err).contains("--mount-master")
        }
        if (supportsMaster) listOf("su", "-M", "-c") else listOf("su", "-c")
    }
    fun hasSu(timeoutMs: Long = 800): Boolean {
        val r = base.exec(rootArgs + "id", timeoutMs)
        return r.code == 0 && Regex("(?:^|\\s)uid=0(?:\\(|\\s|$)").containsMatchIn(r.out)
    }

    fun execSu(cmd: String, timeoutMs: Long = 1500): ShellResult {
        // 注意：这里不做长命令交互，够用就行
        return base.exec(rootArgs + cmd, timeoutMs)
    }
}
