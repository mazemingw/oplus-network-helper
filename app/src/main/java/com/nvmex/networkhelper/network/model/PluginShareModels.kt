package com.nvmex.networkhelper.network.model

data class PluginOssSignReq(
    val brand: String,
    val model: String,
    val buildDisplayId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val fileMd5: String? = null
)

data class PluginOssSignResp(
    val requestId: String,
    val host: String,
    val key: String,
    val policy: String,
    val signature: String,
    val OSSAccessKeyId: String,
    val securityToken: String? = null,
    val expireAt: String? = null
)

data class PluginUploadReportReq(
    val requestId: String,
    val status: String,
    val uploadedBytes: Long? = null,
    val durationMs: Long? = null,
    val avgSpeedBps: Double? = null,
    val ossEtag: String? = null,
    val ossObjectKey: String? = null,
    val errorMessage: String? = null
)

data class PluginUploadReportResp(
    val ok: Boolean = false,
    val error: String? = null
)

data class PluginDownloadCandidatesReq(
    val brand: String,
    val model: String,
    val buildDisplayId: String? = null
)

data class PluginDownloadCandidatesResp(
    val brand: String? = null,
    val model: String = "",
    val currentBuildDisplayId: String? = null,
    val fallbackUsed: Boolean = false,
    val expiresInSeconds: Int = 0,
    val count: Int = 0,
    val items: List<PluginDownloadCandidateItem> = emptyList()
)

data class PluginDownloadCandidateItem(
    val brand: String = "",
    val model: String = "",
    val buildDisplayId: String = "",
    val fileName: String = "plugin-release.zip",
    val fileSizeBytes: Long = 0,
    val fileMd5: String? = null,
    val ossObjectKey: String = "",
    val updatedAt: String? = null,
    val downloadUrl: String = "",
    val expiresAt: String? = null
)
