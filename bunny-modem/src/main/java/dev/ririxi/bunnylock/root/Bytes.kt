package dev.ririxi.bunnylock.root

import dev.ririxi.bunnylock.modem.R

object LittleEndian {
    fun u16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    fun u32(value: Long) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    fun u64(value: Long) = ByteArray(8) { (value shr (8 * it)).toByte() }
}

class Reader(
    private val data: ByteArray,
) {
    private var offset = 0

    val remaining get() = data.size - offset

    private fun need(byteCount: Int) {
        if (offset + byteCount > data.size) fail(R.string.qmi_reply_too_short)
    }

    private fun unsigned(byteCount: Int): Long {
        need(byteCount)
        var value = 0L
        for (i in 0 until byteCount) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        offset += byteCount
        return value
    }

    fun u8(): Int = unsigned(1).toInt()

    fun u16(): Int = unsigned(2).toInt()

    fun i16(): Int = u16().toShort().toInt()

    fun u32(): Long = unsigned(4)

    fun i32(): Int = u32().toInt()

    fun u64(): Long = unsigned(8)

    fun bytes(byteCount: Int): ByteArray {
        need(byteCount)
        val result = data.copyOfRange(offset, offset + byteCount)
        offset += byteCount
        return result
    }

    fun skip(byteCount: Int) {
        need(byteCount)
        offset += byteCount
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

fun String.unhex(): ByteArray {
    val digits = filter { !it.isWhitespace() && it != ':' && it != ',' }
    if (digits.length % 2 != 0) fail(R.string.qmi_hex_odd_digits)
    return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
