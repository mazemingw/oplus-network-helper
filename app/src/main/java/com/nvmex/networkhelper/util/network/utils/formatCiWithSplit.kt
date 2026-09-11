package com.nvmex.networkhelper.util.network.utils

//拆分长号
private const val NR_CELL_BITS_CZ = 12
private const val NR_CELL_MASK_CZ = (1L shl NR_CELL_BITS_CZ) - 1

fun formatCiWithSplit(cellType: String, ciRaw: String): String {
    val v = ciRaw.toLongOrNull() ?: return ciRaw

    return when (cellType.uppercase()) {
        "LTE" -> {
            // LTE ECI：eNB + sector（8bit）
            val enb = v shr 8
            val cell = v and 0xFF
            "$ciRaw ($enb-$cell)"
        }

        "NR" -> {
            // NR NCI 拆分存在多种口径（Cell-ID 位宽可变）
            // 此处默认采用 12bit，对齐 Cellular-Z 等主流工具，保证显示一致性
            // NR NCI：对齐 Cellular-Z 口径：低 12bit 作为 cell
            val gnb = v shr NR_CELL_BITS_CZ
            val cell = v and NR_CELL_MASK_CZ
            "$ciRaw ($gnb-$cell)"
        }

        else -> ciRaw
    }
}

/**
 * 反向合成长号
 *
 * LTE:
 *   longCi = enb << 8 | cell
 *
 * NR:
 *   longCi = gnb << 12 | cell
 */
fun mergeCiFromSplit(cellType: String, majorId: Long, minorId: Int): Long? {
    return when (cellType.uppercase()) {
        "LTE" -> {
            if (majorId < 0 || minorId !in 0..0xFF) return null
            (majorId shl 8) or (minorId.toLong() and 0xFF)
        }

        "NR" -> {
            if (majorId < 0 || minorId !in 0..NR_CELL_MASK_CZ.toInt()) return null
            (majorId shl NR_CELL_BITS_CZ) or (minorId.toLong() and NR_CELL_MASK_CZ)
        }

        else -> null
    }
}

fun mergeCiFromSplitText(cellType: String, splitText: String): Long? {
    val parts = splitText.split("-")
    if (parts.size != 2) return null

    val majorId = parts[0].trim().toLongOrNull() ?: return null
    val minorId = parts[1].trim().toIntOrNull() ?: return null

    return mergeCiFromSplit(cellType, majorId, minorId)
}