package com.nvmex.networkhelper.network.model

data class NrSiteQueryResp(
    val match_level: String? = null,
    val site_match: String? = null,
    val anchor: NrSiteAnchor? = null,
    val count: Int = 0,
    val total_count: Int = 0,
    val truncated: Boolean = false,
    val data: List<NrCellParamItem> = emptyList()
)