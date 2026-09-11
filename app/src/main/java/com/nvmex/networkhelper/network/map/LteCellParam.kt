package com.nvmex.networkhelper.network.map

//旧的 已有重复的 将会DELELTE
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