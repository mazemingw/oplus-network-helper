package com.nvmex.networkhelper.network.model

data class LteBatchUpsertReq(
    val source: String? = null,
    val items: List<LteCellParamUploadItem>
)

data class LteCellParamUploadItem(
    val tac: Int? = null,
    val pci: Int? = null,
    val enodebId: Int? = null,
    val earfcn: Int? = null,
    val cellId: Int? = null,
    val eci: Long? = null,
    val localCellId: Int? = null,
    val cellName: String? = null,
    val sectorId: Int? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val azimuth: Int? = null,
    val siteType: String? = null,
    val antennaHeight: Double? = null,
    val source: String? = null
)