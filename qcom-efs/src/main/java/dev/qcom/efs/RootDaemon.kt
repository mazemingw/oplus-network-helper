package dev.qcom.efs

import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Extracts the bundled aarch64 helper and starts it as root.
 *
 * The helper is the only part of the app that sends DIAG packets; the app
 * itself never runs with elevated privileges and only speaks to the helper
 * over an abstract unix socket that is bound to this app's uid.
 *
 * SELinux is only read for the log. RawPartitionBackup separately uses a
 * read-only root process to copy EFS/NV block images to public backup storage.
 */
object RootDaemon {

    const val SOCKET_NAME = "nh_efsd"
    private const val ASSET_NAME = "nh-efsd"

    class Report(
        val ok: Boolean,
        val lines: List<String>,
        val error: String? = null,
        /** enforce value as read by root, since an app uid usually cannot. */
        val enforce: Int? = null,
    )

    fun logFile(ctx: Context): File = File(ctx.cacheDir, "qcom-efsd.log")

    fun modeName(v: Int?): String = when (v) {
        1 -> "强制模式"
        0 -> "宽容模式"
        else -> "未知"
    }

    // ---- lifecycle ------------------------------------------------------

    suspend fun start(ctx: Context, verbose: Boolean): Report =
        withContext(Dispatchers.IO) {
            val log = mutableListOf<String>()

            val binary = File(ctx.filesDir, ASSET_NAME)
            try {
                // A helper started earlier may still be executing from this
                // very file, and Linux refuses to reopen a running binary for
                // writing (ETXTBUSY).  Unlinking it first is allowed: the old
                // process keeps the old inode and we get a fresh file.
                binary.delete()
                ctx.assets.open(ASSET_NAME).use { input ->
                    binary.outputStream().use { input.copyTo(it) }
                }
                binary.setExecutable(true, true)
                binary.setReadable(true, true)
                log += "后台服务已释放到 ${binary.absolutePath}（${binary.length()} 字节）"
            } catch (t: Throwable) {
                return@withContext Report(false, log, "无法释放后台服务：${t.message}")
            }

            val logPath = logFile(ctx).absolutePath
            val uid = Process.myUid()
            val script = buildString {
                appendLine("echo \"UID=$(id -u)\"")
                appendLine("echo \"SE=$(cat /sys/fs/selinux/enforce 2>/dev/null)\"")
                appendLine("pkill -9 -f $ASSET_NAME 2>/dev/null")
                appendLine("chmod 700 '${binary.absolutePath}' 2>/dev/null")
                appendLine("rm -f '$logPath' 2>/dev/null")
                append("setsid '${binary.absolutePath}' -uid $uid -socket $SOCKET_NAME")
                if (verbose) append(" -verbose")
                appendLine(" </dev/null >>'$logPath' 2>&1 &")
                // The helper prints "ready" once its socket is listening, so
                // waiting for that line costs what the start really takes
                // (tens of milliseconds) instead of a flat second.  The bound
                // is a safety net: a helper that never gets there is the
                // connect attempt's problem to report, not this loop's.
                appendLine(
                    "n=0; while [ \$n -lt 40 ]; do grep -q ' ready' '$logPath' 2>/dev/null " +
                        "&& break; n=\$((n+1)); sleep 0.05; done"
                )
                appendLine("chmod 644 '$logPath' 2>/dev/null")
                appendLine("echo __DONE__")
                appendLine("exit")
            }

            val output = try {
                runSu(script, log)
            } catch (t: Throwable) {
                return@withContext Report(
                    false, log,
                    "Root Shell 不可用：${t.message ?: "无法启动 su"}"
                )
            }

            val rootUid = output.firstOrNull { it.startsWith("UID=") }?.removePrefix("UID=")?.trim()
            val enforce = output.firstOrNull { it.startsWith("SE=") }
                ?.removePrefix("SE=")?.trim()
                ?.let { if (it == "1") 1 else if (it == "0") 0 else null }

            if (enforce != null) log += "SELinux 当前为${modeName(enforce)}，保持原状"

            if (rootUid != "0") {
                return@withContext Report(
                    false, log, "Root 授权被拒绝（id -u 返回 '$rootUid'）", enforce)
            }
            if (output.none { it.contains("__DONE__") }) {
                return@withContext Report(
                    false, log, "后台服务尚未启动，Root Shell 就已退出", enforce)
            }

            log += readDaemonLog(ctx)
            Report(true, log, null, enforce)
        }

    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { runSu("pkill -9 -f $ASSET_NAME\nexit\n", mutableListOf()) }
        Unit
    }

    fun readDaemonLog(ctx: Context): List<String> =
        runCatching { logFile(ctx).readLines().takeLast(200) }.getOrDefault(emptyList())

    private fun runSu(script: String, log: MutableList<String>): List<String> {
        val proc = ProcessBuilder("su").redirectErrorStream(true).start()
        proc.outputStream.bufferedWriter().use { it.write(script); it.flush() }

        val lines = mutableListOf<String>()
        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lines += line
                log += "su: $line"
            }
        }
        proc.waitFor()
        return lines
    }
}
