package dev.ririxi.bunnylock.shizuku

import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import java.io.InputStream
import java.io.OutputStream

internal class RemoteShell(
    private val remote: IRemoteProcess,
) : Process() {
    private val stdin = ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
    private val stdout = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
    private val stderr = ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int = remote.waitFor()

    override fun exitValue(): Int = remote.exitValue()

    // The default relies on an exception from exitValue() that doesn't cross the binder call.
    override fun isAlive(): Boolean = runCatching { remote.alive() }.getOrDefault(false)

    override fun destroy() {
        runCatching { remote.destroy() }
        listOf(stdin, stdout, stderr).forEach { runCatching { it.close() } }
    }
}
