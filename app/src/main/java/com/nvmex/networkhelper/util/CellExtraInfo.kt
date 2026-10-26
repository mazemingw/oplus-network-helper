package com.nvmex.networkhelper.util

/** Commas, equals and backslashes may be escaped by the engineering-data importer. */
fun parseCellExtraInfo(raw: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    val key = StringBuilder()
    val value = StringBuilder()
    var inValue = false
    fun append(char: Char) { (if (inValue) value else key).append(char) }
    fun finish() {
        if (key.isNotEmpty() || value.isNotEmpty()) {
            result += if (inValue) key.toString().trim() to value.toString()
                else "" to key.toString()
        }
        key.clear(); value.clear(); inValue = false
    }
    var index = 0
    while (index < raw.length) {
        when (val char = raw[index++]) {
            '\\' -> if (index < raw.length) {
                val escaped = raw[index++]
                when (escaped) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    '\\', ',', '=' -> append(escaped)
                    else -> { append('\\'); append(escaped) }
                }
            } else append('\\')
            ',' -> finish()
            '=' -> if (!inValue) inValue = true else append(char)
            else -> append(char)
        }
    }
    finish()
    return result
}
