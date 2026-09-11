package com.nvmex.networkhelper.network.model

data class NrCellQueryResp(
    val match_level: String? = null,
    val count: Int = 0,
    val total_count: Int = 0,
    val truncated: Boolean = false,
    val data: List<NrCellParamItem> = emptyList(),
    val reason: String? = null
)