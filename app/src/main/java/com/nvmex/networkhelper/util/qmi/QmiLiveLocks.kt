package com.nvmex.networkhelper.util.qmi

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class QmiLiveLocks(val rats: Set<String>?, val cells: Map<String, Boolean?>)
data class QmiCurrentLocks(val cell: Boolean?, val rat: Boolean?)

internal fun qmiDecodeLiveLocks(replies: Map<Int, String>): QmiLiveLocks {
    fun tlvs(msg: Int) = replies[msg]?.let { runCatching { responseTlvs(it, msg) }.getOrNull() }
    val mode = tlvs(0x34)?.get(0x11)?.takeIf { it.size >= 2 }?.let {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
    }
    val rats = mode?.let { bits -> mapOf("gsm" to 4, "wcdma" to 8, "lte" to 16, "nr" to 64).filterValues { bits and it != 0 }.keys }
    val lte = tlvs(0xd7)?.get(0x10)?.let {
        val count = it.firstOrNull()?.toInt()?.and(255) ?: return@let null
        if (count > 64 || it.size < 1 + count * 4) null else count > 0
    }
    val nr = tlvs(0x10f)?.get(0x10)?.takeIf { it.size == 4 }?.let {
        when (ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int) { 2 -> false; 0, 1, 3, 4 -> true; else -> null }
    }
    return QmiLiveLocks(rats, mapOf("lte" to lte, "nr" to nr))
}

fun qmiCurrentLocks(snapshot: QmiRadioSnapshot?, rat: String, slot: Int?, now: Long): QmiCurrentLocks {
    val readback = snapshot?.takeIf { it.slot == slot && now - it.updatedAt in 0..12000 }?.locks
        ?: return QmiCurrentLocks(null, null)
    val key = rat.lowercase()
    return QmiCurrentLocks(readback.cells[key], readback.rats?.let { if (key in it) it == setOf(key) else null })
}
