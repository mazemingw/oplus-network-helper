package com.nvmex.networkhelper.model.network

data class CellTimelinePoint(
    val ts: Long,
    val cellType: String,
    val tac: String,
    val pci: String,
    val ci: String,
    val band: String,
    val rsrp: String,
    val rsrq: String,
    val sinr: String,
    val arfcn: String = "-",
    val mcc: String = "", val mnc: String = ""
) {
    fun sameCell(other: CellTimelinePoint): Boolean {
        return cellType == other.cellType &&
                tac == other.tac &&
                pci == other.pci &&
                ci == other.ci
    }
}

fun upsertCellTimeline(
    points: List<CellTimelinePoint>,
    point: CellTimelinePoint,
    limit: Int = 20
): List<CellTimelinePoint> = (points.filterNot { it.sameCell(point) } + point).takeLast(limit)
