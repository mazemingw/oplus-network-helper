package com.nvmex.networkhelper.util.shell

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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

            val ok = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ok) {
                p.destroy()
                return ShellResult(code = -1, out = "", err = "timeout")
            }

            val out = BufferedReader(InputStreamReader(p.inputStream)).readTextSafely()
            val err = BufferedReader(InputStreamReader(p.errorStream)).readTextSafely()
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
            sb.append(line).append('\n')
        }
        return sb.toString()
    }
}

class SuShellRunner(
    private val base: ShellRunner = DefaultShellRunner()
) {
    fun hasSu(timeoutMs: Long = 800): Boolean {
        val r = base.exec(listOf("su", "-c", "id"), timeoutMs)
        return r.code == 0 && r.out.contains("uid=", ignoreCase = true)
    }

    fun execSu(cmd: String, timeoutMs: Long = 1500): ShellResult {
        // 注意：这里不做长命令交互，够用就行
        return base.exec(listOf("su", "-c", cmd), timeoutMs)
    }
}
