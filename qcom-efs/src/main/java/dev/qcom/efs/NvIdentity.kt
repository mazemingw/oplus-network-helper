package dev.qcom.efs

/** NV_UE_IMEI_I (550), read only: length byte followed by the mobile identity BCD representation. */
internal fun decodeNvImei(hex: String): String? = runCatching {
    require(hex.length >= 18 && hex.length % 2 == 0)
    val bytes = hex.take(18).chunked(2).map { it.toInt(16) }
    require(bytes[0] == 8 && bytes[1] and 15 == 10)
    buildString {
        append(bytes[1] shr 4)
        for (b in bytes.drop(2)) { require((b and 15) <= 9 && (b shr 4) <= 9); append(b and 15); append(b shr 4) }
    }.takeIf { it.length == 15 && it.all(Char::isDigit) && it.any { c -> c != '0' } }
}.getOrNull()
