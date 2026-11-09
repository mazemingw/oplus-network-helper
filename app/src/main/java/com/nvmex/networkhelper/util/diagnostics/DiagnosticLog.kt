package com.nvmex.networkhelper.util.diagnostics

enum class DiagnosticLevel(val label: String) { INFO("信息"), WARNING("警告"), ERROR("错误") }

object DiagnosticLogLimits {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 12_800
}

data class DiagnosticLogEntry(
    val id: Long, val firstAt: Long, val lastAt: Long, val level: DiagnosticLevel,
    val source: String, val message: String, val detail: String = "", val repeats: Int = 1,
)

/** Bounded independently of disk I/O and UI refresh. Repeated rows update a counter instead of growing. */
internal class DiagnosticLogBuffer(private val maxEntries: Int = DiagnosticLogLimits.MAX_ENTRIES, private val maxBytes: Int = DiagnosticLogLimits.MAX_BYTES) {
    private val rows = LinkedHashMap<String, DiagnosticLogEntry>()
    private var bytes = 0
    private var nextId = 0L
    private var second = -1L
    private var admissions = 0
    var dropped = 0L; private set

    @Synchronized fun append(level: DiagnosticLevel, source: String, message: String, detail: String = "", now: Long = System.currentTimeMillis()): Boolean {
        val safeSource = DiagnosticRedactor.redact(source.take(100))
        val safeMessage = DiagnosticRedactor.redact(message.take(1200))
        val key = "$level|$safeSource|${DiagnosticLogPolicy.signature(safeMessage)}"
        val old = rows[key]
        if (old != null && now - old.lastAt in 0..60_000) {
            rows.remove(key)
            rows[key] = old.copy(lastAt = now, repeats = (old.repeats + 1).coerceAtMost(1_000_000))
            return true
        }
        if (second != now / 1000) { second = now / 1000; admissions = 0 }
        if (++admissions > 30) { dropped++; return false }
        rows.remove(key)?.let { bytes -= cost(it) }
        val row = DiagnosticLogEntry(++nextId, now, now, level, safeSource, safeMessage,
            DiagnosticRedactor.redact(detail.take(6000)))
        rows[key] = row
        bytes += cost(row)
        trim()
        return true
    }

    @Synchronized fun restore(saved: List<DiagnosticLogEntry>) {
        val combined = (saved + rows.values).sortedBy { it.lastAt }.takeLast(maxEntries)
        rows.clear(); bytes = 0
        for (row in combined) {
            val safe = row.copy(id = ++nextId, source = DiagnosticRedactor.redact(row.source.take(100)),
                message = DiagnosticRedactor.redact(row.message.take(1200)), detail = DiagnosticRedactor.redact(row.detail.take(6000)),
                repeats = row.repeats.coerceIn(1, 1_000_000))
            val key = "${safe.level}|${safe.source}|${DiagnosticLogPolicy.signature(safe.message)}"
            rows.remove(key)?.let { bytes -= cost(it) }
            rows[key] = safe; bytes += cost(safe)
        }
        trim()
    }

    // UTF-8 plus JSON escape expansion; reserve fixed space for field names and numeric metadata.
    private fun encodedBytes(value: String) = value.toByteArray(Charsets.UTF_8).size + value.sumOf { char ->
        when (char) { '"', '\\', '/', '\b', '\u000C', '\n', '\r', '\t' -> 1; '\u2028', '\u2029' -> 3; else -> if (char < ' ') 5 else 0 }
    }
    private fun cost(row: DiagnosticLogEntry) = encodedBytes(row.source) + encodedBytes(row.message) + encodedBytes(row.detail) + 256
    private fun trim() {
        while (rows.isNotEmpty() && (rows.size > maxEntries || bytes > maxBytes)) {
            val key = rows.keys.first(); bytes -= cost(rows.remove(key)!!)
        }
    }
    @Synchronized fun snapshot(): List<DiagnosticLogEntry> = rows.values.toList().asReversed()
    @Synchronized fun clear() { rows.clear(); bytes = 0; dropped = 0; admissions = 0; second = -1 }
}

internal object DiagnosticLogPolicy {
    private val critical = Regex("(?i)FATAL EXCEPTION|fatal signal|SIGSEGV|OutOfMemoryError|ANR in")
    private val frameworkNoise = Regex("(?i)^(?:VRI\\[|HWUI|BLASTBufferQueue|BufferQueue|DynamicFramerate|OplusBracketLog|VibratorInfo|chromium|cr_|Gms|ServiceBindIntentUtils|SchedAssist|ashmem|OplusPredictiveBackController|SettingTrigger|MI-PreRender|XQos|LB$|FPS-BOOST|IJankManager|InterceptorProxy-Helper|FramePredict|TurboSchedMonitor|CheckBoxHook--)")
    private val runtimeNoise = Regex("(?i)\\[perfctl]|\\[ALTAIR]|Unsupported class loader:|Missing inline cache|Cleared Reference was only reachable|failed to read IDMAP|Failed to open file.*@idmap")
    fun level(tag: String, priority: String, message: String): DiagnosticLevel = when {
        critical.containsMatchIn(message) -> DiagnosticLevel.ERROR
        frameworkNoise.containsMatchIn(tag) || (tag == "ActivityThread" && message.contains("provider info")) -> DiagnosticLevel.INFO
        runtimeNoise.containsMatchIn(message) -> DiagnosticLevel.INFO
        priority == "E" || priority == "F" -> DiagnosticLevel.ERROR
        priority == "W" -> DiagnosticLevel.WARNING
        else -> DiagnosticLevel.INFO
    }
    fun expectedModemRefusal(source: String, message: String) = source == "efs" &&
        Regex("(?i)target rejected|bad length|bad command|not supported|不支持|基带拒绝").containsMatchIn(message)
    fun signature(message: String) = message.replace(Regex("(?i)@[0-9a-f]{4,}|0x[0-9a-f]{6,}"), "@*")
}
