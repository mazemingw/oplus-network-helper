package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.ModemQosInfo

/**
 * Minimal NAS decoder for the QoS fields needed by the HOME panel.
 *
 * It intentionally handles only network-to-UE messages that allocate the
 * default data session. This keeps the DIAG reader small and avoids retaining
 * complete NAS messages containing temporary subscriber/network identifiers.
 */
internal object ModemQosParser {
    private const val EPD_5GSM = 0x2e
    private const val PDU_SESSION_ESTABLISHMENT_ACCEPT = 0xc2
    private const val PD_EPS_SESSION_MANAGEMENT = 0x02
    private const val ACTIVATE_DEFAULT_EPS_BEARER_REQUEST = 0xc1

    data class Parsed(
        val dnn: String? = null,
        val qci: Int? = null,
        val fiveQi: Int? = null,
        val qfi: Int? = null,
        val downlinkAmbrKbps: Long? = null,
        val uplinkAmbrKbps: Long? = null,
        val source: ModemQosInfo.Source
    )

    data class ParsedMmState(
        val mmState: Int,
        val mmSubState: Int,
        val fiveGTmsi: String?
    )

    fun parse5gMmStateDiagPayload(payload: ByteArray): ParsedMmState? {
        if (payload.size < 26) return null
        val minor = payload.u16le(0) ?: return null
        val major = payload.u16le(2) ?: return null
        if (!((major == 0 && minor == 1) || (major == 3 && minor == 0))) return null
        val gutiStart = 10
        val fiveGTmsi = if (payload[gutiStart].u8() == 0x02) {
            val value = payload.u32le(gutiStart + 8)
            value.takeIf { it >= 0 }?.let { String.format("%08X", it) }
        } else {
            null
        }
        return ParsedMmState(
            mmState = payload[4].u8(),
            mmSubState = payload.u16le(5) ?: return null,
            fiveGTmsi = fiveGTmsi
        )
    }

    fun parse5gDiagPayload(payload: ByteArray): Parsed? {
        // Qualcomm B800/B80A payload: u32 packet version + 3 release bytes + NAS.
        if (payload.size < 12 || payload.u32le(0) != 1L) return null
        return parse5gNas(payload.copyOfRange(7, payload.size))
    }

    fun parse5gNas(nas: ByteArray): Parsed? {
        if (nas.size < 8) return null

        // B80A can contain a duplicate, decoded DL NAS transport message.
        if (nas[0].u8() == 0x7e && nas[2].u8() == 0x68) {
            // Only decoded/plain N1 SM containers, never interpret ciphered/other payloads as QoS.
            if (nas[1].u8() and 0x0f != 0 || nas[3].u8() and 0x0f != 1) return null
            val containerLength = nas.u16be(4) ?: return null
            val containerStart = 6
            if (containerLength <= 0 || containerStart + containerLength > nas.size) return null
            return parse5gNas(nas.copyOfRange(containerStart, containerStart + containerLength))
        }

        if (nas[0].u8() != EPD_5GSM || nas[3].u8() != PDU_SESSION_ESTABLISHMENT_ACCEPT) {
            return null
        }

        var pos = 5 // 5GSM header + packed SSC mode/PDU session type

        val qosRulesLength = nas.u16be(pos) ?: return null
        pos += 2 + qosRulesLength
        if (pos >= nas.size) return null

        val ambrLength = nas[pos].u8()
        pos += 1
        if (ambrLength < 6 || pos + ambrLength > nas.size) return null

        val downlinkKbps = decode5gBitRate(nas[pos].u8(), nas.u16be(pos + 1) ?: return null)
        val uplinkKbps = decode5gBitRate(nas[pos + 3].u8(), nas.u16be(pos + 4) ?: return null)
        pos += ambrLength

        var qci: Int? = null
        var fiveQi: Int? = null
        var qfi: Int? = null
        var dnn: String? = null

        while (pos < nas.size) {
            val tag = nas[pos].u8()
            when {
                tag in LONG_TLV_TAGS -> {
                    val length = nas.u16be(pos + 1) ?: break
                    val valueStart = pos + 3
                    val valueEnd = valueStart + length
                    if (valueEnd > nas.size) break
                    when (tag) {
                        0x75 -> qci = parseMappedEpsQci(nas, valueStart, valueEnd) ?: qci
                        0x79 -> {
                            val flow = parseQosFlows(nas, valueStart, valueEnd)
                            fiveQi = flow.first ?: fiveQi
                            qfi = flow.second ?: qfi
                        }
                    }
                    pos = valueEnd
                }

                tag in SHORT_TLV_TAGS -> {
                    if (pos + 1 >= nas.size) break
                    val length = nas[pos + 1].u8()
                    val next = pos + 2 + length
                    if (next > nas.size) break
                    if (tag == 0x25) dnn = decodeApn(nas, pos + 2, next) ?: dnn
                    pos = next
                }

                tag == 0x59 || tag == 0x56 -> pos += 2 // Type 3 TV
                tag and 0xf0 == 0x80 || tag and 0xf0 == 0xc0 -> pos += 1 // Type 1 TV
                else -> break
            }
        }

        return Parsed(
            dnn = dnn,
            qci = qci,
            fiveQi = fiveQi,
            qfi = qfi,
            downlinkAmbrKbps = downlinkKbps,
            uplinkAmbrKbps = uplinkKbps,
            source = ModemQosInfo.Source.NR_5GSM
        )
    }

    fun parseLteDiagPayload(payload: ByteArray): Parsed? {
        // Qualcomm B0E2 payload: packet version + three release bytes + LTE NAS.
        if (payload.size < 9) return null
        return parseLteNas(payload.copyOfRange(4, payload.size))
    }

    fun parseLteNas(nas: ByteArray): Parsed? {
        if (nas.size < 5 || nas[0].u8() and 0x0f != PD_EPS_SESSION_MANAGEMENT) return null
        if (nas[2].u8() != ACTIVATE_DEFAULT_EPS_BEARER_REQUEST) return null
        val epsQosLength = nas[3].u8()
        if (epsQosLength < 1 || 4 + epsQosLength > nas.size) return null

        var pos = 4 + epsQosLength
        if (pos !in nas.indices) return null
        val apnEnd = pos + 1 + nas[pos].u8()
        if (apnEnd > nas.size) return null
        val dnn = decodeApn(nas, pos + 1, apnEnd)
        pos = apnEnd
        pos = skipLv(nas, pos) ?: return null // PDN address

        var downlinkAmbrKbps: Long? = null
        var uplinkAmbrKbps: Long? = null
        while (pos < nas.size) {
            val tag = nas[pos].u8()
            when {
                tag and 0xf0 in setOf(0x80, 0x90, 0xb0, 0xc0) -> pos += 1
                tag == 0x32 || tag == 0x58 -> pos += 2
                tag == 0x7b -> {
                    val length = nas.u16be(pos + 1) ?: break
                    val next = pos + 3 + length
                    if (next > nas.size) break
                    pos = next
                }
                else -> {
                    if (pos + 1 >= nas.size) break
                    val length = nas[pos + 1].u8()
                    val valueStart = pos + 2
                    val next = valueStart + length
                    if (next > nas.size) break
                    when (tag) {
                        0x5e -> if (length == 2 || length == 4 || length == 6) {
                            downlinkAmbrKbps = decodeEpsApnAmbr(
                                base = nas[valueStart].u8(),
                                extended = if (length >= 4) nas[valueStart + 2].u8() else null,
                                extended2 = if (length >= 6) nas[valueStart + 4].u8() else null
                            )
                            uplinkAmbrKbps = decodeEpsApnAmbr(
                                base = nas[valueStart + 1].u8(),
                                extended = if (length >= 4) nas[valueStart + 3].u8() else null,
                                extended2 = if (length >= 6) nas[valueStart + 5].u8() else null
                            )
                        }
                        0x5f -> if (length >= 6) {
                            downlinkAmbrKbps = decodeExtendedBitRate(
                                nas[valueStart].u8(), nas.u16be(valueStart + 1) ?: 0
                            ) ?: downlinkAmbrKbps
                            uplinkAmbrKbps = decodeExtendedBitRate(
                                nas[valueStart + 3].u8(), nas.u16be(valueStart + 4) ?: 0
                            ) ?: uplinkAmbrKbps
                        }
                    }
                    pos = next
                }
            }
        }

        return Parsed(
            dnn = dnn,
            qci = nas[4].u8(),
            downlinkAmbrKbps = downlinkAmbrKbps,
            uplinkAmbrKbps = uplinkAmbrKbps,
            source = ModemQosInfo.Source.LTE_ESM
        )
    }

    private fun parseQosFlows(data: ByteArray, start: Int, end: Int): Pair<Int?, Int?> {
        var pos = start
        var fiveQi: Int? = null
        var defaultQfi: Int? = null
        while (pos + 3 <= end) {
            val qfi = data[pos].u8() and 0x3f
            val parameterCount = data[pos + 2].u8() and 0x3f
            pos += 3
            for (parameterIndex in 0 until parameterCount) {
                if (pos + 2 > end) return fiveQi to defaultQfi
                val type = data[pos].u8()
                val length = data[pos + 1].u8()
                val valueStart = pos + 2
                val next = valueStart + length
                if (next > end) return fiveQi to defaultQfi
                if (type == 1 && length >= 1) {
                    fiveQi = data[valueStart].u8()
                    defaultQfi = qfi
                }
                pos = next
            }
            if (parameterCount == 0) break
        }
        return fiveQi to defaultQfi
    }

    private fun skipLv(data: ByteArray, offset: Int): Int? {
        if (offset !in data.indices) return null
        val next = offset + 1 + data[offset].u8()
        return next.takeIf { it <= data.size }
    }

    private fun decodeApn(data: ByteArray, start: Int, end: Int): String? {
        if (start >= end || start < 0 || end > data.size) return null
        val labels = mutableListOf<String>()
        var pos = start
        while (pos < end) {
            val length = data[pos].u8()
            pos++
            if (length <= 0 || pos + length > end) return null
            val label = data.copyOfRange(pos, pos + length).toString(Charsets.US_ASCII)
            if (label.any { !it.isLetterOrDigit() && it != '-' }) return null
            labels += label
            pos += length
        }
        return labels.joinToString(".").lowercase().takeIf { it.isNotBlank() }
    }

    /** TS 24.301 9.9.4.2, result is expressed in kbit/s. */
    private fun decodeEpsApnAmbr(base: Int, extended: Int?, extended2: Int?): Long? {
        val baseRate = when (base) {
            in 1..63 -> base.toLong()
            in 64..127 -> (64L + (base - 64L) * 8L)
            in 128..254 -> (576L + (base - 128L) * 64L)
            255 -> 0L
            else -> null
        }
        val extendedRate = when (extended) {
            null, 0 -> baseRate
            in 1..74 -> 8_600L + extended * 100L
            in 75..186 -> 16_000L + (extended - 74L) * 1_000L
            in 187..250 -> 128_000L + (extended - 186L) * 2_000L
            else -> 256_000L // Reserved extended values are mapped to 0xFA.
        }
        return when (extended2) {
            null, 0, 255 -> extendedRate
            // APN-AMBR is additive. EPS QoS MBR/GBR has a different coding!
            in 1..254 -> extendedRate?.plus(extended2 * 256_000L)
            else -> extendedRate
        }
    }

    /** TS 24.301 9.9.4.29 extended APN-AMBR. */
    private fun decodeExtendedBitRate(unit: Int, value: Int): Long? {
        if (value <= 0) return null
        val multiplierKbps = when (unit) {
            3 -> 4_000L
            4 -> 16_000L
            5 -> 64_000L
            6 -> 256_000L
            7 -> 1_000_000L
            8 -> 4_000_000L
            9 -> 16_000_000L
            10 -> 64_000_000L
            11 -> 256_000_000L
            12 -> 1_000_000_000L
            else -> return null
        }
        // Values <= 65280 Mbps must be ignored (TS 24.301 9.9.4.29).
        return (multiplierKbps * value.toLong()).takeIf { it > 65_280_000L }
    }

    private fun parseMappedEpsQci(data: ByteArray, start: Int, end: Int): Int? {
        var pos = start
        while (pos + 4 <= end) {
            val contextLength = data.u16be(pos + 1) ?: return null
            val contextEnd = pos + 3 + contextLength
            if (contextEnd > end) return null
            val parameterCount = data[pos + 3].u8() and 0x07
            var parameterPos = pos + 4
            repeat(parameterCount) {
                if (parameterPos + 2 > contextEnd) return@repeat
                val type = data[parameterPos].u8()
                val length = data[parameterPos + 1].u8()
                val valueStart = parameterPos + 2
                val next = valueStart + length
                if (next > contextEnd) return null
                if (type == 1 && length >= 1) return data[valueStart].u8()
                parameterPos = next
            }
            pos = contextEnd
        }
        return null
    }

    private fun decode5gBitRate(unit: Int, value: Int): Long? {
        val multiplierKbps = when (unit) {
            1 -> 1L
            2 -> 4L
            3 -> 16L
            4 -> 64L
            5 -> 256L
            6 -> 1_000L
            7 -> 4_000L
            8 -> 16_000L
            9 -> 64_000L
            10 -> 256_000L
            11 -> 1_000_000L
            12 -> 4_000_000L
            13 -> 16_000_000L
            14 -> 64_000_000L
            15 -> 256_000_000L
            16 -> 1_000_000_000L
            else -> return null
        }
        return multiplierKbps * value.toLong()
    }

    private fun Byte.u8(): Int = toInt() and 0xff

    private fun ByteArray.u16be(offset: Int): Int? {
        if (offset < 0 || offset + 1 >= size) return null
        return (this[offset].u8() shl 8) or this[offset + 1].u8()
    }

    private fun ByteArray.u16le(offset: Int): Int? {
        if (offset < 0 || offset + 1 >= size) return null
        return this[offset].u8() or (this[offset + 1].u8() shl 8)
    }

    private fun ByteArray.u32le(offset: Int): Long {
        if (offset < 0 || offset + 3 >= size) return -1
        return this[offset].u8().toLong() or
            (this[offset + 1].u8().toLong() shl 8) or
            (this[offset + 2].u8().toLong() shl 16) or
            (this[offset + 3].u8().toLong() shl 24)
    }

    private val LONG_TLV_TAGS = setOf(0x71, 0x72, 0x75, 0x77, 0x78, 0x79, 0x7b)
    private val SHORT_TLV_TAGS = setOf(0x17, 0x18, 0x1f, 0x22, 0x25, 0x29, 0x35, 0x66)
}
