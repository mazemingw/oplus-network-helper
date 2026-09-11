package com.nvmex.networkhelper.network.model

data class CellAdminActionResp(
    val ok: Boolean = false,
    val affectedRows: Int? = null,
    val id: Long? = null
)

data class LteCellAdminUpdateReq(
    val auth_token: String,
    val id: Long? = null,
    val eci: Long? = null,
    val cell_name: String? = null,
    val site_type: String? = null,
    val azimuth: Int? = null,
    val antenna_height: Double? = null,
    val longitude: Double? = null,
    val latitude: Double? = null
)

data class LteCellAdminDeleteReq(
    val auth_token: String,
    val id: Long? = null,
    val eci: Long? = null
)

data class NrCellAdminUpdateReq(
    val auth_token: String,
    val id: Long? = null,
    val gcell_id: String? = null,
    val cell_name: String? = null,
    val site_type: String? = null,
    val azimuth: Int? = null,
    val antenna_height: Double? = null,
    val longitude: Double? = null,
    val latitude: Double? = null
)

data class NrCellAdminDeleteReq(
    val auth_token: String,
    val id: Long? = null,
    val gcell_id: String? = null
)
