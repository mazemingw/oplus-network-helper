package com.nvmex.networkhelper.network.map

data class LteSiteQueryResp(
    val match_level: String = "none",
    val anchor: LteCellParam? = null,
    val enodeb_id: Int? = null,
    val sector_count: Int = 0,
    val count: Int = 0,
    val truncated: Boolean = false,
    val data: List<LteCellParam> = emptyList()
)