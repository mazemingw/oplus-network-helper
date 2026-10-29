package dev.ririxi.bunnylock.root

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.ririxi.bunnylock.modem.R
import java.io.File
import java.io.FileDescriptor

object At {
    fun exists(node: String) = if (node.startsWith(OemHook.PREFIX)) OemHook.available() else File(node).exists()

    /** [node] is a modem tty path, or "ril:<slot>" to go through MediaTek's RIL (see [OemHook]). */
    fun send(
        node: String,
        command: String,
        timeoutMs: Int = 4000,
    ): String {
        val throughRil = node.startsWith(OemHook.PREFIX)
        if (throughRil || node.startsWith(OemHook.RAW_PREFIX)) {
            // A reply through the RIL takes longer than one from a tty, so short timeouts are raised.
            val rilTimeoutMs = maxOf(timeoutMs, 10_000)
            val slot = OemHook.slotOf(node)
            val reply = if (throughRil) OemHook.send(slot, command, rilTimeoutMs) else OemHook.sendRaw(slot, command, rilTimeoutMs)
            return cleanReply(reply, command, node)
        }
        val fd =
            try {
                Os.open(node, OsConstants.O_RDWR or OsConstants.O_NOCTTY or OsConstants.O_NONBLOCK, 0)
            } catch (e: ErrnoException) {
                fail(R.string.at_open_failed, node, e.toText())
            }
        try {
            discardPendingInput(fd)
            writeFully(fd, (command + "\r").toByteArray())
            return cleanReply(readUntilFinalResult(fd, node, timeoutMs), command, node)
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    fun ok(
        node: String,
        command: String,
        timeoutMs: Int = 4000,
    ): String {
        val reply = send(node, command, timeoutMs)
        val lines = reply.lines()
        if (lines.none { it == "OK" }) fail(R.string.at_not_accepted, command, lines.joinToString(" "))
        return reply
    }

    /**
     * Drops what earlier commands or unsolicited messages left unread, so it isn't taken for this command's reply.
     * Stops after 64 buffers so a port that never goes quiet can't hold the command up.
     */
    private fun discardPendingInput(fd: FileDescriptor) {
        val buffer = ByteArray(4096)
        repeat(64) {
            try {
                if (Os.read(fd, buffer, 0, buffer.size) <= 0) return
            } catch (_: ErrnoException) {
                return
            }
        }
    }

    private fun writeFully(
        fd: FileDescriptor,
        bytes: ByteArray,
    ) {
        var written = 0
        while (written < bytes.size) written += Os.write(fd, bytes, written, bytes.size - written)
    }

    /** Running out of time isn't an error here: whatever has arrived by then is returned. */
    private fun readUntilFinalResult(
        fd: FileDescriptor,
        node: String,
        timeoutMs: Int,
    ): String {
        val reply = StringBuilder()
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remainingMs = (deadline - System.currentTimeMillis()).toInt()
            if (remainingMs <= 0) break
            val pollFd =
                StructPollfd().apply {
                    this.fd = fd
                    events = OsConstants.POLLIN.toShort()
                }
            if (Os.poll(arrayOf(pollFd), remainingMs) <= 0) continue
            val count =
                try {
                    Os.read(fd, buffer, 0, buffer.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    fail(R.string.at_read_failed, node, e.toText())
                }
            if (count <= 0) continue
            reply.append(String(buffer, 0, count, Charsets.ISO_8859_1))
            if (hasFinalResult(reply)) break
        }
        return reply.toString()
    }

    private fun hasFinalResult(reply: CharSequence): Boolean =
        reply.split('\r', '\n').any {
            val line = it.trim()
            line == "OK" || line == "ERROR" || line.startsWith("+CME ERROR") || line.startsWith("+CMS ERROR")
        }

    /** One trimmed line per row, without blank lines or the modem's echo of [command]. */
    private fun cleanReply(
        raw: String,
        command: String,
        node: String,
    ): String {
        val lines = raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() && it != command }
        if (lines.isEmpty()) fail(R.string.at_no_reply, command, node)
        return lines.joinToString("\n")
    }
}
