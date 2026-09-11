package com.nvmex.networkhelper.viewmodel.cellparam

import android.net.Uri
import android.provider.OpenableColumns
import com.alibaba.excel.EasyExcel
import com.alibaba.excel.read.listener.ReadListener
import com.nvmex.networkhelper.model.cellparam.LteCellParam
import com.nvmex.networkhelper.model.cellparam.NrCellParam
import com.nvmex.networkhelper.xposed.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * 解析文件（CSV 或 Excel），根据 importType 返回对应类型的列表
 */
suspend fun parseFile(
    context: android.content.Context,
    uri: Uri,
    importType: ImportType
): List<*> = withContext(Dispatchers.IO) {
    val extension = getFileExtension(context, uri).lowercase()
    val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext emptyList()

    when (extension) {
        "xls", "xlsx" -> parseExcel(inputStream, importType)
        "csv", "txt" -> parseCsv(inputStream, importType)
        else -> throw IllegalArgumentException("不支持的文件类型: $extension")
    }
}

/**
 * 解析 Excel 文件（使用 EasyExcel）
 *
 * 说明：
 * - Excel 这里仍然按数据类字段映射
 * - 如果你后面要让 Excel 也严格跟 CSV 模板头一致，那就得单独再做 head 映射策略
 */
private fun parseExcel(inputStream: InputStream, importType: ImportType): List<*> {
    val dataList = mutableListOf<Any>()
    val clazz = when (importType) {
        ImportType.NR -> NrCellParam::class.java
        ImportType.LTE -> LteCellParam::class.java
    }

    EasyExcel.read(inputStream, clazz, object : ReadListener<Any> {
        override fun invoke(data: Any?, context: com.alibaba.excel.context.AnalysisContext?) {
            data?.let { dataList.add(it) }
        }

        override fun doAfterAllAnalysed(context: com.alibaba.excel.context.AnalysisContext?) = Unit

        override fun onException(
            exception: Exception?,
            context: com.alibaba.excel.context.AnalysisContext?
        ) {
            exception?.printStackTrace()
            throw exception ?: RuntimeException("Excel解析失败")
        }
    }).sheet(0).headRowNumber(1).doRead()

    return dataList
}

/**
 * 解析 CSV 文件
 *
 * 规则：
 * 1. 表头仅兼容大小写、空格、横杠、下划线差异
 * 2. 不做奇怪别名映射
 * 3. 出现非法字段直接拒绝
 * 4. 缺少必要字段直接拒绝
 */
private fun parseCsv(inputStream: InputStream, importType: ImportType): List<Any> {
    val result = mutableListOf<Any>()

    val bytes = inputStream.readBytes()
    if (bytes.isEmpty()) return emptyList()

    val charset = detectCharset(bytes)
    Logger.log("CSV 检测到编码: ${charset.name()}")

    val text = decodeBytes(bytes, charset)
    val reader = InputStreamReader(text.byteInputStream(Charsets.UTF_8), Charsets.UTF_8).buffered()

    val headerLine = reader.readLine()?.removePrefix("\uFEFF") ?: return emptyList()
    val delimiterChar = if (headerLine.contains('\t')) '\t' else ','

    val rawHeaders = parseCsvLine(headerLine, delimiterChar).map { it.trim() }
    val headers = rawHeaders.map { normalizeFieldName(it) }

    Logger.log("CSV 表头原始: $rawHeaders")
    Logger.log("CSV 表头规范化: $headers")
    Logger.log("CSV 分隔符: ${if (delimiterChar == '\t') "\\t" else delimiterChar}")

    validateHeaders(importType, headers)

    val columnIndex = headers.withIndex().associate { it.value to it.index }

    var lineNumber = 2
    reader.forEachLine { line ->
        try {
            if (line.isBlank()) {
                lineNumber++
                return@forEachLine
            }

            val values = parseCsvLine(line, delimiterChar).map { it.trim() }

            when (importType) {
                ImportType.NR -> {
                    val data = NrCellParam(
                        gcellId = values.valueOf(columnIndex, NrKeys.GCELL_ID),
                        cellName = values.valueOfOrNull(columnIndex, NrKeys.CELL_NAME),
                        longitude = values.valueOfOrNull(columnIndex, NrKeys.LONGITUDE)?.toDoubleOrNull(),
                        latitude = values.valueOfOrNull(columnIndex, NrKeys.LATITUDE)?.toDoubleOrNull(),
                        azimuth = values.valueOfOrNull(columnIndex, NrKeys.AZIMUTH)?.toIntOrNull(),
                        nrPci = values.valueOfOrNull(columnIndex, NrKeys.NR_PCI)?.toIntOrNull(),
                        nrArfcn = values.valueOfOrNull(columnIndex, NrKeys.NR_ARFCN)?.toIntOrNull(),
                        nrTac = values.valueOfOrNull(columnIndex, NrKeys.NR_TAC)?.toIntOrNull(),
                        siteType = values.valueOfOrNull(columnIndex, NrKeys.SITE_TYPE),
                        antennaHeight = values.valueOfOrNull(columnIndex, NrKeys.ANTENNA_HEIGHT)?.toDoubleOrNull(),
                        source = values.valueOfOrNull(columnIndex, NrKeys.SOURCE)
                    )
                    result.add(data)
                }

                ImportType.LTE -> {
                    val data = LteCellParam(
                        id = values.valueOfOrNull(columnIndex, LteKeys.ID)?.toLongOrNull(),
                        tac = values.valueOfOrNull(columnIndex, LteKeys.TAC)?.toIntOrNull(),
                        pci = values.valueOfOrNull(columnIndex, LteKeys.PCI)?.toIntOrNull(),
                        enodeb_id = values.valueOfOrNull(columnIndex, LteKeys.ENODEB_ID)?.toIntOrNull(),
                        earfcn = values.valueOfOrNull(columnIndex, LteKeys.EARFCN)?.toIntOrNull(),
                        cell_id = values.valueOfOrNull(columnIndex, LteKeys.CELL_ID)?.toIntOrNull(),
                        eci = values.valueOfOrNull(columnIndex, LteKeys.ECI)?.toLongOrNull(),
                        local_cell_id = values.valueOfOrNull(columnIndex, LteKeys.LOCAL_CELL_ID)?.toIntOrNull(),
                        cell_name = values.valueOfOrNull(columnIndex, LteKeys.CELL_NAME),
                        sector_id = values.valueOfOrNull(columnIndex, LteKeys.SECTOR_ID)?.toIntOrNull(),
                        longitude = values.valueOfOrNull(columnIndex, LteKeys.LONGITUDE)?.toDoubleOrNull(),
                        latitude = values.valueOfOrNull(columnIndex, LteKeys.LATITUDE)?.toDoubleOrNull(),
                        azimuth = values.valueOfOrNull(columnIndex, LteKeys.AZIMUTH)?.toIntOrNull(),
                        site_type = values.valueOfOrNull(columnIndex, LteKeys.SITE_TYPE),
                        antenna_height = values.valueOfOrNull(columnIndex, LteKeys.ANTENNA_HEIGHT)?.toDoubleOrNull(),
                        source = values.valueOfOrNull(columnIndex, LteKeys.SOURCE),
                        created_at = values.valueOfOrNull(columnIndex, LteKeys.CREATED_AT)
                    )
                    result.add(data)
                }
            }
        } catch (e: Exception) {
            Logger.logE("解析第 $lineNumber 行失败: $line", e)
        }
        lineNumber++
    }

    Logger.log("解析完成，共 ${result.size} 条")
    return result
}

/**
 * 表头规范化：
 * - trim
 * - lowercase
 * - 去空格
 * - 去横杠
 * - 去下划线
 *
 * 例如：
 * - GCellID -> gcellid
 * - NR_PCI -> nrpci
 * - enodeb_id -> enodebid
 * - cell-name -> cellname
 */
private fun normalizeFieldName(value: String): String {
    return value
        .trim()
        .lowercase()
        .replace(" ", "")
        .replace("-", "")
        .replace("_", "")
}

/**
 * 严格校验表头
 */
private fun validateHeaders(importType: ImportType, headers: List<String>) {
    val allowedHeaders = when (importType) {
        ImportType.NR -> NrKeys.ALL
        ImportType.LTE -> LteKeys.ALL
    }

    val requiredHeaders = when (importType) {
        ImportType.NR -> NrKeys.REQUIRED
        ImportType.LTE -> LteKeys.REQUIRED
    }

    val unknownHeaders = headers.filter { it !in allowedHeaders }
    if (unknownHeaders.isNotEmpty()) {
        throw IllegalArgumentException("检测到非法表头: $unknownHeaders，请使用模板中的标准表头")
    }

    val missingHeaders = requiredHeaders.filter { it !in headers }
    if (missingHeaders.isNotEmpty()) {
        throw IllegalArgumentException("缺少必要表头: $missingHeaders")
    }
}

private fun List<String>.valueOf(columnIndex: Map<String, Int>, key: String): String {
    val index = columnIndex[key] ?: return ""
    return getOrNull(index)?.trim().orEmpty()
}

private fun List<String>.valueOfOrNull(columnIndex: Map<String, Int>, key: String): String? {
    val index = columnIndex[key] ?: return null
    return getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * 解析 CSV 行，支持逗号 / Tab，支持双引号包裹
 */
private fun parseCsvLine(line: String, delimiterChar: Char): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0

    while (i < line.length) {
        val char = line[i]
        when {
            char == '"' -> {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            }

            char == delimiterChar && !inQuotes -> {
                result.add(current.toString())
                current.clear()
            }

            else -> current.append(char)
        }
        i++
    }

    result.add(current.toString())
    return result
}

/**
 * 自动识别常见编码
 */
private fun detectCharset(bytes: ByteArray): Charset {
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    ) {
        return Charsets.UTF_8
    }

    if (bytes.size >= 2 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xFE.toByte()
    ) {
        return Charsets.UTF_16LE
    }

    if (bytes.size >= 2 &&
        bytes[0] == 0xFE.toByte() &&
        bytes[1] == 0xFF.toByte()
    ) {
        return Charsets.UTF_16BE
    }

    val utf8Text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull().orEmpty()
    return if (looksLikeValidUtf8(bytes) && !looksLikeMojibake(utf8Text)) {
        Charsets.UTF_8
    } else {
        Charset.forName("GB18030")
    }
}

private fun decodeBytes(bytes: ByteArray, charset: Charset): String {
    return try {
        String(bytes, charset)
    } catch (e: Exception) {
        Logger.logE("按 ${charset.name()} 解码失败，回退 UTF-8", e)
        String(bytes, Charsets.UTF_8)
    }
}

private fun looksLikeValidUtf8(bytes: ByteArray): Boolean {
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        when {
            b <= 0x7F -> i++
            b shr 5 == 0b110 -> {
                if (i + 1 >= bytes.size) return false
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (b2 shr 6 != 0b10) return false
                i += 2
            }
            b shr 4 == 0b1110 -> {
                if (i + 2 >= bytes.size) return false
                val b2 = bytes[i + 1].toInt() and 0xFF
                val b3 = bytes[i + 2].toInt() and 0xFF
                if (b2 shr 6 != 0b10 || b3 shr 6 != 0b10) return false
                i += 3
            }
            b shr 3 == 0b11110 -> {
                if (i + 3 >= bytes.size) return false
                val b2 = bytes[i + 1].toInt() and 0xFF
                val b3 = bytes[i + 2].toInt() and 0xFF
                val b4 = bytes[i + 3].toInt() and 0xFF
                if (b2 shr 6 != 0b10 || b3 shr 6 != 0b10 || b4 shr 6 != 0b10) return false
                i += 4
            }
            else -> return false
        }
    }
    return true
}

private fun looksLikeMojibake(text: String): Boolean {
    if (text.isBlank()) return false
    val badChars = listOf("�", "锟", "鈥", "銆", "鏂", "闂", "�?")
    if (badChars.any { text.contains(it) }) return true
    val suspiciousCount = text.count { it.code in 0x80..0x9F || it == '�' }
    return suspiciousCount > 3
}

/**
 * 获取文件扩展名（根据 MIME 或文件名）
 */
private fun getFileExtension(context: android.content.Context, uri: Uri): String {
    val mime = context.contentResolver.getType(uri)
    return when (mime) {
        "application/vnd.ms-excel" -> "xls"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "text/csv" -> "csv"
        "text/plain" -> "txt"
        else -> {
            val name = getFileName(context, uri)
            name.substringAfterLast('.', "")
        }
    }
}

/**
 * 获取文件名（从 Uri）
 */
private fun getFileName(context: android.content.Context, uri: Uri): String {
    var fileName = ""
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
    }
    return fileName.ifEmpty { uri.lastPathSegment ?: "unknown" }
}

/* ===================== 规范化字段键定义 ===================== */

private object NrKeys {
    const val GCELL_ID = "gcellid"
    const val CELL_NAME = "cellname"
    const val LONGITUDE = "longitude"
    const val LATITUDE = "latitude"
    const val AZIMUTH = "azimuth"
    const val NR_PCI = "nrpci"
    const val NR_ARFCN = "nrarfcn"
    const val NR_TAC = "nrtac"
    const val SITE_TYPE = "sitetype"
    const val ANTENNA_HEIGHT = "antennaheight"
    const val SOURCE = "source"

    val ALL = setOf(
        GCELL_ID,
        CELL_NAME,
        LONGITUDE,
        LATITUDE,
        AZIMUTH,
        NR_PCI,
        NR_ARFCN,
        NR_TAC,
        SITE_TYPE,
        ANTENNA_HEIGHT,
        SOURCE
    )

    val REQUIRED = setOf(
        GCELL_ID,
        CELL_NAME,
        LONGITUDE,
        LATITUDE,
        AZIMUTH,
        NR_PCI,
        NR_ARFCN
    )
}

private object LteKeys {
    const val ID = "id"
    const val TAC = "tac"
    const val PCI = "pci"
    const val ENODEB_ID = "enodebid"
    const val EARFCN = "earfcn"
    const val CELL_ID = "cellid"
    const val ECI = "eci"
    const val LOCAL_CELL_ID = "localcellid"
    const val CELL_NAME = "cellname"
    const val SECTOR_ID = "sectorid"
    const val LONGITUDE = "longitude"
    const val LATITUDE = "latitude"
    const val AZIMUTH = "azimuth"
    const val SITE_TYPE = "sitetype"
    const val ANTENNA_HEIGHT = "antennaheight"
    const val SOURCE = "source"
    const val CREATED_AT = "createdat"

    val ALL = setOf(
        ID,
        TAC,
        PCI,
        ENODEB_ID,
        EARFCN,
        CELL_ID,
        ECI,
        LOCAL_CELL_ID,
        CELL_NAME,
        SECTOR_ID,
        LONGITUDE,
        LATITUDE,
        AZIMUTH,
        SITE_TYPE,
        ANTENNA_HEIGHT,
        SOURCE,
        CREATED_AT
    )

    val REQUIRED = setOf(
        TAC,
        PCI,
        ENODEB_ID,
        EARFCN,
        CELL_ID,
        ECI,
        LOCAL_CELL_ID,
        CELL_NAME,
        LONGITUDE,
        LATITUDE,
        AZIMUTH
    )
}