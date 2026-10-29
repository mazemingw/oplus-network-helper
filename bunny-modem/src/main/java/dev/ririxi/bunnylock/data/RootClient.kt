package dev.ririxi.bunnylock.data

import android.content.Context
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import org.json.JSONObject
import java.io.IOException

class RootClient(
    private val context: Context,
) : HelperClient(context, Text(R.string.helper_root)) {
    override val startFailed: Text get() = Text(R.string.modem_root_start_failed)

    override val startTimedOut: Text get() = Text(R.string.modem_root_timed_out)

    override fun openShell(): Process =
        try {
            ProcessBuilder("su").start()
        } catch (_: IOException) {
            throw RootException(context, Text(R.string.modem_no_su))
        }

    override suspend fun start(): JSONObject {
        val hello = super.start()
        val uid = hello.optInt("uid", -1)
        if (uid != 0) {
            stop()
            throw RootException(context, Text(R.string.modem_root_wrong_uid, uid))
        }
        return hello
    }
}
