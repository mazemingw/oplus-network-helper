package com.nvmex.networkhelper.model.cellparam

// 5G NR 小区参数
data class NrCellParam(
    var gcellId: String = "",
    var cellName: String? = null,
    var longitude: Double? = null,
    var latitude: Double? = null,
    var azimuth: Int? = null,
    var nrPci: Int? = null,
    var nrArfcn: Int? = null,
    // 扩展字段
    var nrTac: Int? = null,
    var siteType: String? = null,
    var antennaHeight: Double? = null,
    var source: String? = null
)

// 4G LTE 小区参数
data class LteCellParam(
    val id: Long? = null,
    val tac: Int? = null,
    val pci: Int? = null,
    val enodeb_id: Int? = null,
    val earfcn: Int? = null,
    val cell_id: Int? = null,
    val eci: Long? = null,
    val local_cell_id: Int? = null,
    val cell_name: String? = null,
    val sector_id: Int? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val azimuth: Int? = null,
    val site_type: String? = null,
    val antenna_height: Double? = null,
    val source: String? = null,
    val created_at: String? = null
)

sealed class CellParam {
    data class NR(val data: NrCellParam) : CellParam()
    data class LTE(val data: LteCellParam) : CellParam()
}