package com.nvmex.networkhelper.util.network

private val nrHighSpeedRailwayArfcns = setOf(507150, 527070, 627744, 634464, 423630)
private val nrMetroArfcns = setOf(520830)

/** Display-only labels. Keep the raw RAT/channel unchanged for matching, sampling and persistence. */
internal fun formatCellType(cellType: String, arfcn: Int?): String {
    val type = cellType.trim().ifEmpty { "-" }
    if (!type.equals("NR", ignoreCase = true)) return type
    return when (arfcn) {
        in nrMetroArfcns -> "$type 地铁专网"
        in nrHighSpeedRailwayArfcns -> "$type 高铁专网"
        else -> type
    }
}
