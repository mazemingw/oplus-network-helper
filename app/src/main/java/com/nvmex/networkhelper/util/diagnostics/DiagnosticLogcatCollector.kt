package com.nvmex.networkhelper.util.diagnostics

internal data class CapturedDiagnostic(val tag: String, val level: DiagnosticLevel, val message: String, val detail: String)

/** Join Log.e(tag, message, throwable) by thread and tag before deciding whether to upload. */
internal class DiagnosticLogcatCollector {
    private data class Pending(val tag: String, val priority: String, val message: String,
        val lines: StringBuilder = StringBuilder(), var updatedAt: Long, val orphan: Boolean = false)
    private val pending = LinkedHashMap<String, Pending>()
    private val continuation = Regex("^\\s*(?:at\\s|Caused by:|Suppressed:|\\.\\.\\. \\d+ more)")
    private val exceptionHeader = Regex("^[\\w.$]+(?:Exception|Error)(?::|\\s|$)")

    @Synchronized fun accept(tag: String, thread: String, priority: String, message: String,
        now: Long = System.currentTimeMillis()): List<CapturedDiagnostic> {
        val result = drain(now, false).toMutableList()
        val key = "$thread|$tag"
        val old = pending[key]
        val isContinuation = continuation.containsMatchIn(message) || (old != null && exceptionHeader.containsMatchIn(message))
        if (isContinuation) {
            val item = old ?: Pending(tag, "W", "堆栈片段（缺少异常头）", updatedAt = now, orphan = true).also { pending[key] = it }
            if (item.lines.length < 12000) item.lines.append(message.take(12000 - item.lines.length)).append('\n')
            item.updatedAt = now
        } else {
            pending.remove(key)?.let { result.add(finish(it)) }
            pending[key] = Pending(tag, priority, message.take(4000), updatedAt = now)
        }
        while (pending.size > 64) result.add(finish(pending.remove(pending.keys.first())!!))
        return result
    }

    @Synchronized fun flush(now: Long = System.currentTimeMillis()): List<CapturedDiagnostic> = drain(now, false)
    @Synchronized fun clear() { pending.clear() }
    private fun drain(now: Long, all: Boolean): List<CapturedDiagnostic> {
        val keys = pending.filterValues { all || now - it.updatedAt >= 350 }.keys.toList()
        return keys.map { finish(pending.remove(it)!!) }
    }
    private fun finish(item: Pending): CapturedDiagnostic {
        val detail = item.lines.toString()
        val level = if (item.orphan) DiagnosticLevel.WARNING else DiagnosticLogPolicy.level(item.tag, item.priority, item.message + "\n" + detail)
        return CapturedDiagnostic(item.tag, level, item.message, detail)
    }
}

internal data class DiagnosticAdmission(val key: String, val at: Long)

/** Persist snapshot after admission so restarting the app does not reset the hourly budget. */
internal class DiagnosticRateLimiter {
    private val admitted = mutableListOf<DiagnosticAdmission>()
    fun restore(saved: List<DiagnosticAdmission>, now: Long = System.currentTimeMillis()) {
        admitted.clear()
        admitted.addAll(saved.filter { it.at in (now - 3_600_000)..(now + 60_000) && it.key.matches(Regex("[a-f0-9]{64}")) }.sortedBy { it.at }.takeLast(20))
    }
    fun admit(key: String, now: Long): Boolean {
        admitted.removeAll { it.at < now - 3_600_000 || it.at > now + 60_000 }
        if (admitted.size >= 20 || admitted.any { it.key == key && now - it.at < 600_000 }) return false
        admitted.add(DiagnosticAdmission(key, now)); return true
    }
    fun snapshot(): List<DiagnosticAdmission> = admitted.toList()
}
