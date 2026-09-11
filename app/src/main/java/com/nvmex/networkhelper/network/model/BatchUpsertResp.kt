package com.nvmex.networkhelper.network.model

data class BatchUpsertResp(
    val message: String,
    val received: Int,
    val success: Int,
    val skipped: Int,
    val affectedRows: Int? = null
)