package com.nvmex.networkhelper.network.base

import retrofit2.Response

suspend fun <T : Any> safeApiCall(call: suspend () -> Response<T>): ApiResult<T> {
    return try {
        val resp = call()
        if (resp.isSuccessful) {
            val body = resp.body()
            if (body != null) ApiResult.Ok(body)
            else ApiResult.NetworkError("响应成功但 body 为空")
        } else {
            val err = runCatching { resp.errorBody()?.string() }.getOrNull()
            ApiResult.HttpError(resp.code(), err)
        }
    } catch (e: Exception) {
        ApiResult.NetworkError(e.message ?: "网络异常", e)
    }
}
