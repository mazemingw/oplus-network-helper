package com.nvmex.networkhelper.network.model

data class DevAuthVerifyReq(
    val password: String
)

data class DevAuthVerifyResp(
    val ok: Boolean = false,
    val role: String? = null,
    val role_label: String? = null,
    val auth_token: String? = null,
    val expires_at_ms: Long? = null
)
