package dev.qcom.efs

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream

class EfsException(message: String, val efsErrno: Int? = null) : Exception(message)

/**
 * One request per line, one JSON object per answer.  Every exchange is
 * serialised: the daemon keeps a single DIAG session and cannot interleave.
 */
class EfsClient : Closeable {

    private val mutex = Mutex()
    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    suspend fun connect(timeoutMs: Long = 5_000) = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            val deadline = System.currentTimeMillis() + timeoutMs
            var last: Throwable? = null
            while (System.currentTimeMillis() < deadline) {
                try {
                    val s = LocalSocket()
                    s.connect(LocalSocketAddress(RootDaemon.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                    s.soTimeout = 120_000
                    socket = s
                    reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8), 1 shl 16)
                    writer = s.outputStream
                    return@withLock
                } catch (t: Throwable) {
                    last = t
                    Thread.sleep(150)
                }
            }
            throw EfsException(
                "cannot reach the helper on @${RootDaemon.SOCKET_NAME}: ${last?.message ?: "timed out"}"
            )
        }
    }

    /** Sends a request and returns the parsed answer; throws on `"ok":false`. */
    suspend fun request(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            val json = raw(payload)
            if (!json.optBoolean("ok", false)) {
                val errno = if (json.has("efs_errno")) json.optInt("efs_errno") else null
                throw EfsException(json.optString("error", "the helper rejected the request"), errno)
            }
            json
        } catch (error: Exception) {
            if (payload.optString("cmd") != "shutdown" && error !is kotlinx.coroutines.CancellationException && (error as? EfsException)?.efsErrno != 2)
                DiagnosticEvents.error("efs", error, "cmd=${payload.optString("cmd")}; efs_errno=${(error as? EfsException)?.efsErrno}")
            throw error
        }
    }

    /** Same, but returns failures instead of throwing. */
    suspend fun raw(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            val out = writer ?: throw EfsException("not connected to the helper")
            val rd = reader ?: throw EfsException("not connected to the helper")
            // A root EFS walk can take several minutes; the normal two-minute
            // socket timeout must not kill a healthy full backup halfway through.
            socket?.soTimeout = when (payload.optString("cmd")) {
                "pull_tree", "image" -> 30 * 60 * 1000
                else -> 120_000
            }

            // A helper that died leaves a socket that still reports itself
            // connected; the first write then fails with a bare "Broken pipe".
            // Say what that means, and drop the socket so isConnected tells
            // the truth from here on.  A read that timed out is dropped too:
            // its late answer would otherwise be taken for the next request's.
            val line = try {
                out.write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                rd.readLine()
            } catch (e: IOException) {
                closeLocked()
                throw EfsException("lost the connection to the helper (${e.message}) - use Reconnect in the menu")
            } ?: run {
                closeLocked()
                throw EfsException("the helper closed the connection - use Reconnect in the menu")
            }
            try {
                JSONObject(line)
            } catch (t: Throwable) {
                throw EfsException("malformed answer from the helper: ${line.take(200)}")
            }
        }
    }

    suspend fun cmd(name: String, build: JSONObject.() -> Unit = {}): JSONObject =
        request(JSONObject().put("cmd", name).apply(build))

    override fun close() {
        runCatching { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }
}
