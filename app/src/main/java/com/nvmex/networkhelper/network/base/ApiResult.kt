package com.nvmex.networkhelper.network.base

sealed interface ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>
    data class HttpError(val code: Int, val body: String?) : ApiResult<Nothing>
    data class NetworkError(val message: String, val throwable: Throwable? = null) : ApiResult<Nothing>
}
