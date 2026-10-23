package com.nvmex.networkhelper.util.records

/** UTF-8 CSV with RFC 4180 quoting, including embedded newlines and Excel-safe text cells. */
internal object CsvTable {
    fun encode(headers: List<String>, rows: List<List<String>>): String = buildString {
        append('\uFEFF')
        (listOf(headers) + rows).forEach { row ->
            require(row.size == headers.size) { "CSV 列数不一致" }
            append(row.joinToString(",") { value ->
                val protected = if (value.firstOrNull() in listOf('=', '+', '@', '\t', '\r') ||
                    (value.startsWith("-") && value.toDoubleOrNull() == null)) "'$value" else value
                "\"" + protected.replace("\"", "\"\"") + "\""
            })
            append("\r\n")
        }
    }
    fun decode(text: String): List<Map<String, String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var closed = false
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        fun finishCell() { row.add(cell.toString()); cell.setLength(0); closed = false }
        fun finishRow() { finishCell(); if (row.any { it.isNotEmpty() }) rows.add(row); row = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else cell.append(c)
            } else when (c) {
                '"' -> { require(cell.isEmpty() && !closed) { "CSV 引号位置无效" }; quoted = true }
                ',' -> finishCell()
                '\r', '\n' -> { finishRow(); if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++ }
                else -> { require(!closed || c == ' ') { "CSV 引号后存在无效内容" }; if (!closed) cell.append(c) }
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        if (cell.isNotEmpty() || row.isNotEmpty() || closed) finishRow()
        require(rows.isNotEmpty()) { "CSV 文件为空" }
        val headers = rows.first().map { it.trim() }
        require(headers.none { it.isBlank() } && headers.distinct().size == headers.size) { "CSV 表头为空或重复" }
        return rows.drop(1).mapIndexed { index, values ->
            require(values.size == headers.size) { "CSV 第 ${index + 2} 行列数不一致" }
            headers.zip(values).toMap()
        }
    }
}
