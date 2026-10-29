package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.NrCaInfo

/** Per SIM/cell arbitration. A transient incomplete engineering frame cannot switch the source. */
class NrCaSourceArbiter {
    private data class Held(val cell: String, val info: NrCaInfo)
    private val engineeringBySlot = mutableMapOf<Int, Held>()

    fun choose(engineering: NrCaInfo?, fallback: NrCaInfo?, slot: Int?, cell: String, now: Long): NrCaInfo? {
        if (slot == null) return null
        val held = engineeringBySlot[slot]
        if (held?.cell != cell) engineeringBySlot.remove(slot)
        engineering?.takeIf { it.slot == slot && now - it.updatedAt in 0..12000 && it.carriers.isNotEmpty() }
            ?.let { engineeringBySlot[slot] = Held(cell, it) }
        val preferred = engineeringBySlot[slot]?.info?.takeIf { now - it.updatedAt in 0..12000 }
        if (preferred == null) engineeringBySlot.remove(slot)
        return preferred ?: fallback?.takeIf { it.slot == slot && now - it.updatedAt in 0..12000 && it.carriers.isNotEmpty() }
    }
}
