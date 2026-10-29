package dev.ririxi.bunnylock.data

import android.content.Context
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.string
import org.json.JSONObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The helper couldn't be started or reached, or it refused a command. Despite the name, the Shizuku client throws it too. */
class RootException(
    message: String,
    val text: Text? = null,
) : Exception(message) {
    constructor(context: Context, text: Text) : this(context.string(text), text)
}

interface PrivilegedClient {
    val running: Boolean

    /** Starts the helper and returns its hello: uid, version and whether the QRTR library loaded. */
    suspend fun start(): JSONObject

    /**
     * Sends [cmd] with the fields that [build] adds to the request and returns the "data" object of the helper's reply.
     * Throws [RootException] when the helper reports an error or doesn't answer within [timeout].
     */
    suspend fun call(
        cmd: String,
        timeout: Duration = 20.seconds,
        build: JSONObject.() -> Unit = {},
    ): JSONObject

    fun stop()
}
