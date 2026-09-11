package com.nvmex.networkhelper.network.model

data class NrSiteAnchor(
    val id: Long? = null,
    val gcell_id: String? = null,
    val cell_name: String? = null,
    val nr_pci: Int? = null,
    val nr_arfcn: Int? = null,
    val nr_tac: Int? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val azimuth: Int? = null,
    val site_type: String? = null,
    val source: String? = null
)