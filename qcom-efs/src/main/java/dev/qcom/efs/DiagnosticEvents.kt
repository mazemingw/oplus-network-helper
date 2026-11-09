package dev.qcom.efs

/** The host owns global diagnostics; this module never starts an uploader or a new DIAG session. */
object DiagnosticEvents {
    @Volatile var listener: ((source: String, message: String, error: Throwable?) -> Unit)? = null
    @Volatile var imeiReader: (suspend () -> String?)? = null

    fun record(source: String, message: String) {
        runCatching { listener?.invoke(source, message, null) }
    }
    fun error(source: String, error: Throwable, operation: String) {
        runCatching { listener?.invoke(source, operation, error) }
    }
}
