package com.nvmex.networkhelper.network.model

import com.nvmex.networkhelper.network.map.LteCellParam

data class NearbyCellsRequest(
    val latitude: Double,
    val longitude: Double,
    val radius_km: Int,
    val band: String? = null,
    val rat: String? = null
)

data class NearbyCellsResponse(
    val ok: Boolean = false,
    val lte: List<LteCellParam> = emptyList(),
    val nr: List<NrCellParamItem> = emptyList(),
    val truncated: Boolean = false
)
