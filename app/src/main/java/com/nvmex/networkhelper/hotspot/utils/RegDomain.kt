package com.nvmex.networkhelper.hotspot.utils

data class RegDomain(
    val code: String,          // "US"
    val nameZh: String,        // "美国"
    val nameEn: String = nameZh,
    val note: String? = null,  // "FCC"
    val experimental: Boolean = false
)

private fun flagEmoji(code: String): String {
    // 仅对 A-Z 两位码有效；否则返回占位
    val c = code.uppercase()
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return "🏳️"
    val base = 0x1F1E6
    val first = base + (c[0].code - 'A'.code)
    val second = base + (c[1].code - 'A'.code)
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
