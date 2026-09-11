package com.nvmex.networkhelper.model.menu

data class LteCellQueryResp(
    val match_level: String,
    val count: Int,
    val data: List<LteCellParamRow>
)

data class LteCellParamRow(
    val id: Long? = null,
    val tac: Int? = null,
    val pci: Int? = null,
    val enodeb_id: Int? = null,
    val cell_id: Int? = null,
    val eci: Long? = null,
    val earfcn: Int? = null,
    val cell_name: String? = null,
    val sector_id: Int? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val azimuth: Int? = null,
    val site_type: String? = null,
    val antenna_height: Double? = null,
    val source: String? = null
)
