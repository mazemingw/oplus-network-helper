package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.ModemQosInfo.Source

/** RAT identity only, never a QCI/AMBR cache. Unknown also invalidates old sessions. */
internal class ModemQosNetworkTracker(private val settleMs: Long = 2_000L) {
    data class Changes(val invalidated: Set<Int>, val stabilized: Set<Int>)
    private data class Entry(var source: Source?, var changedAt: Long, var dirty: Boolean = false)
    private val entries = mutableMapOf<Int, Entry>()

    @Synchronized
    fun reset(sources: Map<Int, Source?>, now: Long) {
        entries.clear()
        sources.forEach { (subId, source) -> entries[subId] = Entry(source, now) }
    }

    @Synchronized
    fun observe(sources: Map<Int, Source?>, now: Long): Changes {
        val invalidated = (entries.keys - sources.keys).toMutableSet()
        invalidated.forEach(entries::remove)
        val stabilized = mutableSetOf<Int>()
        sources.forEach { (subId, source) ->
            val entry = entries[subId]
            if (entry == null) {
                entries[subId] = Entry(source, now, dirty = true)
            } else {
                if (entry.source != source) {
                    invalidated += subId
                    entry.source = source
                    entry.changedAt = now
                    entry.dirty = true
                }
                if (entry.dirty && source != null && now - entry.changedAt >= settleMs) {
                    entry.dirty = false
                    stabilized += subId
                }
            }
        }
        return Changes(invalidated, stabilized)
    }
}
