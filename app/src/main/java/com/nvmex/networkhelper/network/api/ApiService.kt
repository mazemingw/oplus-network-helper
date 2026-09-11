package com.nvmex.networkhelper.network.api

import com.nvmex.networkhelper.model.menu.LteCellQueryBody
import com.nvmex.networkhelper.model.menu.LteCellQueryResp
import com.nvmex.networkhelper.network.map.LteSiteQueryResp
import com.nvmex.networkhelper.network.model.BatchUpsertResp
import com.nvmex.networkhelper.network.model.LteBatchUpsertReq
import com.nvmex.networkhelper.network.model.NrBatchUpsertReq
import com.nvmex.networkhelper.network.model.NrCellQueryResp
import com.nvmex.networkhelper.network.model.NrQueryReq
import com.nvmex.networkhelper.network.model.NrSiteQueryResp
import com.nvmex.networkhelper.network.model.PluginOssSignReq
import com.nvmex.networkhelper.network.model.PluginOssSignResp
import com.nvmex.networkhelper.network.model.PluginDownloadCandidatesReq
import com.nvmex.networkhelper.network.model.PluginDownloadCandidatesResp
import com.nvmex.networkhelper.network.model.PluginUploadReportReq
import com.nvmex.networkhelper.network.model.PluginUploadReportResp
import com.nvmex.networkhelper.network.model.DevAuthVerifyReq
import com.nvmex.networkhelper.network.model.DevAuthVerifyResp
import com.nvmex.networkhelper.network.model.CellAdminActionResp
import com.nvmex.networkhelper.network.model.LteCellAdminUpdateReq
import com.nvmex.networkhelper.network.model.LteCellAdminDeleteReq
import com.nvmex.networkhelper.network.model.NrCellAdminUpdateReq
import com.nvmex.networkhelper.network.model.NrCellAdminDeleteReq
import com.nvmex.networkhelper.network.model.VersionUpdateResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("networkhelp/version-updates")
    suspend fun getVersionUpdate(): Response<VersionUpdateResponse>

    @POST("networkhelp/lte/cell-params/query")
    suspend fun queryLteCellParams(@Body body: LteCellQueryBody): Response<LteCellQueryResp>

    @POST("networkhelp/lte/site/query")
    suspend fun queryLteSite(@Body body: LteCellQueryBody): Response<LteSiteQueryResp>

    @POST("networkhelp/lte/cell-params/batch-upsert")
    suspend fun batchUpsertLteCellParams(@Body body: LteBatchUpsertReq): Response<BatchUpsertResp>

    @POST("networkhelp/nr/cell-params/batch-upsert")
    suspend fun batchUpsertNrCellParams(@Body body: NrBatchUpsertReq): Response<BatchUpsertResp>

    @POST("networkhelp/nr/cell-params/query")
    suspend fun queryNrCellParams(@Body body: NrQueryReq): Response<NrCellQueryResp>

    @POST("networkhelp/nr/site/query")
    suspend fun queryNrSite(@Body body: NrQueryReq): Response<NrSiteQueryResp>

    @POST("networkhelp/dev-auth/verify-password")
    suspend fun verifyDevAuthPassword(@Body body: DevAuthVerifyReq): Response<DevAuthVerifyResp>

    @POST("networkhelp/lte/cell-params/update")
    suspend fun updateLteCellParams(@Body body: LteCellAdminUpdateReq): Response<CellAdminActionResp>

    @POST("networkhelp/lte/cell-params/delete")
    suspend fun deleteLteCellParams(@Body body: LteCellAdminDeleteReq): Response<CellAdminActionResp>

    @POST("networkhelp/nr/cell-params/update")
    suspend fun updateNrCellParams(@Body body: NrCellAdminUpdateReq): Response<CellAdminActionResp>

    @POST("networkhelp/nr/cell-params/delete")
    suspend fun deleteNrCellParams(@Body body: NrCellAdminDeleteReq): Response<CellAdminActionResp>

    @POST("networkhelp/plugin-share/oss-sign")
    suspend fun getPluginOssSign(@Body body: PluginOssSignReq): Response<PluginOssSignResp>

    @POST("networkhelp/plugin-share/report")
    suspend fun reportPluginUpload(@Body body: PluginUploadReportReq): Response<PluginUploadReportResp>

    @POST("networkhelp/plugin-share/download-candidates")
    suspend fun getPluginDownloadCandidates(@Body body: PluginDownloadCandidatesReq): Response<PluginDownloadCandidatesResp>
}
