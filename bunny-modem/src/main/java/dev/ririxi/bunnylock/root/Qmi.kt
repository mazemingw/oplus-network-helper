package dev.ririxi.bunnylock.root

import androidx.annotation.StringRes
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

/** libbunnyqrtr.so is loaded by RootServer. */
object Qrtr {
    @JvmStatic external fun open(
        service: Int,
        timeoutMs: Int,
    ): Int

    @JvmStatic external fun transact(
        fd: Int,
        request: ByteArray,
        timeoutMs: Int,
    ): ByteArray

    @JvmStatic external fun close(fd: Int)

    private val STEPS =
        mapOf(
            "lookup" to R.string.qrtr_lookup,
            "socket" to R.string.qrtr_socket,
            "connect" to R.string.qrtr_connect,
            "length" to R.string.qrtr_length,
            "send" to R.string.qrtr_send,
            "timeout" to R.string.qrtr_timeout,
            "wait" to R.string.qrtr_wait,
            "recv" to R.string.qrtr_recv,
        )

    fun <T> worded(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw reword(e)
        }

    private fun reword(e: IOException): Exception {
        val parts = e.message.orEmpty().split(':', limit = 4)
        val step = parts.getOrNull(1)?.let(STEPS::get)
        if (parts.size < 4 || parts[0] != "qrtr" || step === null) return e
        return HelperFailure(Text(R.string.qrtr_failed, Text(step), "${parts[3]}, errno ${parts[2]}"))
    }
}

// A QMI message on QRTR: type (u8), transaction id (u16), message id (u16), body length (u16), then the body.
private const val QMI_HEADER_SIZE = 7
private const val QMI_BODY_LENGTH_OFFSET = 5
private const val QMI_TYPE_REQUEST: Byte = 0

// The body is a run of TLVs: id (u8), length (u16), value.
private const val TLV_HEADER_SIZE = 3

// Every response carries this TLV: result (u16, 0 on success) and error code (u16).
private const val TLV_RESULT = 0x02

class Tlvs {
    private val out = ByteArrayOutputStream()

    fun add(
        id: Int,
        value: ByteArray,
    ): Tlvs {
        out.write(id)
        out.write(LittleEndian.u16(value.size))
        out.write(value)
        return this
    }

    fun u8(
        id: Int,
        value: Int,
    ) = add(id, byteArrayOf(value.toByte()))

    fun u32(
        id: Int,
        value: Long,
    ) = add(id, LittleEndian.u32(value))

    fun bytes(): ByteArray = out.toByteArray()
}

/** Every integer in QMI is little-endian. */
object LittleEndian {
    fun u16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    fun u32(value: Long) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    fun u64(value: Long) = ByteArray(8) { (value shr (8 * it)).toByte() }
}

/** Reads little-endian fields one after another, and fails when the modem sent fewer bytes than the layout needs. */
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

private fun u16At(
    bytes: ByteArray,
    offset: Int,
): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

/** A TLV that claims to run past the end of the message ends the parse. The ones before it are kept. */
private fun parseTlvs(message: ByteArray): Map<Int, ByteArray> {
    val end = minOf(message.size, QMI_HEADER_SIZE + u16At(message, QMI_BODY_LENGTH_OFFSET))
    val tlvs = LinkedHashMap<Int, ByteArray>()
    var position = QMI_HEADER_SIZE
    while (position + TLV_HEADER_SIZE <= end) {
        val id = message[position].toInt() and 0xFF
        val length = u16At(message, position + 1)
        val valueStart = position + TLV_HEADER_SIZE
        if (valueStart + length > end) break
        tlvs[id] = message.copyOfRange(valueStart, valueStart + length)
        position = valueStart + length
    }
    return tlvs
}

class QmiResponse(
    val raw: ByteArray,
) {
    val tlvs: Map<Int, ByteArray> = parseTlvs(raw)
    val result: Int
    val error: Int

    init {
        val resultTlv = tlvs[TLV_RESULT]
        if (resultTlv !== null && resultTlv.size >= 4) {
            val reader = Reader(resultTlv)
            result = reader.u16()
            error = reader.u16()
        } else {
            result = -1
            error = -1
        }
    }

    val ok get() = result == 0

    fun tlv(id: Int): ByteArray? = tlvs[id]

    fun check(@StringRes what: Int): QmiResponse {
        if (!ok) fail(R.string.qmi_refused, Text(what), errorName(error))
        return this
    }

    companion object {
        fun errorName(code: Int): Text =
            when (code) {
                -1 -> Text(R.string.qmi_error_no_result)
                0x00 -> Text(R.string.qmi_error_none)
                0x01 -> Text(R.string.qmi_error_malformed)
                0x02 -> Text(R.string.qmi_error_no_memory)
                0x03 -> Text(R.string.qmi_error_internal)
                0x05 -> Text(R.string.qmi_error_no_free_client)
                0x0E -> Text(R.string.qmi_error_call_failed)
                0x10 -> Text(R.string.qmi_error_not_provisioned)
                0x11 -> Text(R.string.qmi_error_missing_arg)
                0x13 -> Text(R.string.qmi_error_arg_too_long)
                0x19 -> Text(R.string.qmi_error_not_supported_modem)
                0x1A -> Text(R.string.qmi_error_no_effect)
                0x1E -> Text(R.string.qmi_error_invalid_profile_type)
                0x1F -> Text(R.string.qmi_error_invalid_service_type)
                0x29 -> Text(R.string.qmi_error_invalid_qos_id)
                0x2F -> Text(R.string.qmi_error_unknown)
                0x30 -> Text(R.string.qmi_error_invalid_arg)
                0x34 -> Text(R.string.qmi_error_not_ready)
                0x3C -> Text(R.string.qmi_error_invalid_transition)
                0x4A -> Text(R.string.qmi_error_info_unavailable)
                0x52 -> Text(R.string.qmi_error_access_denied)
                0x5A -> Text(R.string.qmi_error_incompatible_state)
                0x5E -> Text(R.string.qmi_error_not_supported)
                else -> Text(R.string.qmi_error_other, "0x%04X".format(code))
            }
    }
}

class QmiClient(
    service: Int,
    private val timeoutMs: Int = 3000,
) : Closeable {
    private val fd = Qrtr.worded { Qrtr.open(service, timeoutMs) }
    private var transactionId = (System.nanoTime() and 0x7FFF).toInt()

    fun send(
        msgId: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Int = this.timeoutMs,
    ): QmiResponse {
        transactionId = (transactionId + 1) and 0xFFFF
        if (transactionId == 0) transactionId = 1
        val header =
            byteArrayOf(QMI_TYPE_REQUEST) +
                LittleEndian.u16(transactionId) +
                LittleEndian.u16(msgId) +
                LittleEndian.u16(payload.size)
        return QmiResponse(Qrtr.worded { Qrtr.transact(fd, header + payload, timeoutMs) })
    }

    fun send(
        msgId: Int,
        tlvs: Tlvs,
    ) = send(msgId, tlvs.bytes())

    override fun close() = Qrtr.close(fd)
}

fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

fun String.unhex(): ByteArray {
    val digits = filter { !it.isWhitespace() && it != ':' && it != ',' }
    if (digits.length % 2 != 0) fail(R.string.qmi_hex_odd_digits)
    return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
