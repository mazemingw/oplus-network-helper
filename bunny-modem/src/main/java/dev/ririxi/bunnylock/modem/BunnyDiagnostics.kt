package dev.ririxi.bunnylock.modem

/** Host-side sink; the standalone privileged process has no dependency on it. */
object BunnyDiagnostics {
    @Volatile
    var sink: ((channel: String, warning: Boolean, message: String) -> Unit)? = null

    internal fun record(channel: String, warning: Boolean, message: String) {
        sink?.invoke(channel, warning, message)
    }
}
