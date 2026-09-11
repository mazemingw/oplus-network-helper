package com.nvmex.networkhelper.network.model

data class NrBatchUpsertReq(
    val source: String? = null,
    val items: List<NrCellParamUploadItem>
)

data class NrCellParamUploadItem(
    val gcellId: String,
    val cellName: String? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val azimuth: Int? = null,
    val nrPci: Int? = null,
    val nrArfcn: Int? = null,
    val nrTac: Int? = null,
    val siteType: String? = null,
    val antennaHeight: Double? = null,
    val source: String? = null
)