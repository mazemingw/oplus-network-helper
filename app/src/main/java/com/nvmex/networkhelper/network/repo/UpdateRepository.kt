package com.nvmex.networkhelper.network.repo

import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.base.ApiResult
import com.nvmex.networkhelper.network.base.safeApiCall
import com.nvmex.networkhelper.network.model.VersionUpdateResponse
import javax.inject.Inject

class UpdateRepository @Inject constructor(
    private val api: ApiService
) {
    suspend fun fetchLatest(): ApiResult<VersionUpdateResponse> {
        return safeApiCall { api.getVersionUpdate() }
    }
}
