package dev.ririxi.bunnylock.data

import android.content.Context
import dev.ririxi.bunnylock.modem.ModemException
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.asSentence
import dev.ririxi.bunnylock.modem.toText
import dev.ririxi.bunnylock.modem.verbatim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs RootServer in app_process inside a shell and talks JSON to it.
 * Every request carries an id, and the reader thread hands each reply to the call that waits for that id.
 */
abstract class HelperClient(
    private val context: Context,
    private val name: Text,
) : PrivilegedClient {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val pendingReplies = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val lastRequestId = AtomicInteger(0)

    override val running get() = process?.isAlive == true

    protected open val startFailed: Text get() = Text(R.string.modem_helper_start_failed, name)

    protected open val startTimedOut: Text get() = Text(R.string.modem_helper_start_timed_out, name)

    protected abstract fun openShell(): Process

    protected open fun exited() {}

    override suspend fun start(): JSONObject =
        withContext(Dispatchers.IO) {
            stop()
            val appInfo = context.applicationInfo
            val shell = openShell()
            val helperStdin = shell.outputStream.bufferedWriter()
            val helperStdout = shell.inputStream.bufferedReader()
            // The helper's stderr is only drained: a pipe that nobody reads would block the helper once it fills up.
            thread(name = "bunny-helper-stderr", isDaemon = true) {
                runCatching { shell.errorStream.bufferedReader().forEachLine { } }
            }
            // exec replaces the shell with the helper, so the shell's pipes become the helper's.
            helperStdin.write("export CLASSPATH='${appInfo.sourceDir}'\n")
            helperStdin.write(
                "exec /system/bin/app_process /system/bin --nice-name=bunnylockd dev.ririxi.bunnylock.root.RootServer '${appInfo.nativeLibraryDir}'\n",
            )
            helperStdin.flush()

            val ready = CompletableDeferred<JSONObject>()
            startReplyReader(helperStdout, ready)

            val hello =
                try {
                    withTimeout(20.seconds) { ready.await() }
                } catch (_: TimeoutCancellationException) {
                    shell.destroy()
                    throw RootException(context, startTimedOut)
                } catch (e: CancellationException) {
                    shell.destroy()
                    throw e
                } catch (e: Exception) {
                    shell.destroy()
                    throw RootException(context, e.reasonText() ?: startFailed)
                }
            process = shell
            writer = helperStdin
            hello
        }

    /**
     * Reads the helper's output for as long as it lives: the hello line completes [ready], every other line
     * answers the pending call with the same id. The helper's stdout closing is how its death is noticed,
     * and everything still waiting then fails.
     */
    private fun startReplyReader(
        helperStdout: BufferedReader,
        ready: CompletableDeferred<JSONObject>,
    ) {
        thread(name = "bunny-helper-reader", isDaemon = true) {
            try {
                while (true) {
                    val line = helperStdout.readLine() ?: break
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (json.has("ready")) {
                        ready.complete(json)
                    } else {
                        pendingReplies.remove(json.optInt("id"))?.complete(json)
                    }
                }
            } catch (_: IOException) {
                // Destroying the process can break the pipe in the middle of a read, which ends the loop like the end of the stream does.
            }
            val helperStopped = RootException(context, Text(R.string.modem_helper_stopped, name))
            ready.completeExceptionally(RootException(context, startFailed))
            pendingReplies.values.forEach { it.completeExceptionally(helperStopped) }
            pendingReplies.clear()
            exited()
        }
    }

    override suspend fun call(
        cmd: String,
        timeout: Duration,
        build: JSONObject.() -> Unit,
    ): JSONObject {
        val helperStdin = writer ?: throw RootException(context, Text(R.string.modem_helper_not_running, name))
        val id = lastRequestId.incrementAndGet()
        val request = JSONObject().put("id", id).put("cmd", cmd).apply(build)
        val reply = CompletableDeferred<JSONObject>()
        pendingReplies[id] = reply
        val response =
            try {
                withContext(Dispatchers.IO) {
                    // Calls can overlap, and each request has to reach the helper as one unbroken line.
                    synchronized(helperStdin) {
                        helperStdin.write(request.toString())
                        helperStdin.write("\n")
                        helperStdin.flush()
                    }
                }
                withTimeout(timeout) { reply.await() }
            } catch (_: TimeoutCancellationException) {
                throw RootException(context, Text(R.string.modem_helper_no_answer, name))
            } catch (_: IOException) {
                // The pipe to a helper that has died breaks on the write.
                throw RootException(context, Text(R.string.modem_helper_stopped, name))
            } finally {
                pendingReplies.remove(id)
            }
        if (!response.optBoolean("ok")) throw context.errorOf(response)
        return response.optJSONObject("data") ?: JSONObject()
    }

    override fun stop() {
        // A helper that is already gone can't take the exit command. Destroying the process covers that case.
        runCatching {
            writer?.apply {
                write("{\"cmd\":\"exit\"}\n")
                flush()
            }
        }
        process?.destroy()
        process = null
        writer = null
    }
}

fun Context.errorOf(reply: JSONObject): RootException {
    reply.optJSONObject("text")?.let { return RootException(this, it.toText()) }
    val error = reply.optString("error")
    return if (error.isBlank()) RootException(this, Text(R.string.something_went_wrong)) else RootException(error)
}

fun Throwable.reasonText(): Text? {
    val ownText =
        when (this) {
            is RootException -> text
            is ModemException -> text
            else -> null
        }
    return ownText ?: message?.takeIf { it.isNotBlank() }?.let { verbatim(it.asSentence()) }
}
