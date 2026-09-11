package com.nvmex.networkhelper.network.model

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class VersionUpdateResponse(
    val id: Int? = null,

    @SerializedName("version_code")
    val versionCode: Int,

    @SerializedName("version_name")
    val versionName: String,

    @SerializedName("update_content")
    val updateContent: String,

    @SerializedName("release_date")
    val releaseDate: String? = null,

    @SerializedName("is_mandatory")
    val isMandatory: Int = 0,

    @SerializedName("download_url")
    val downloadUrl: String? = null,

    @SerializedName("view_count")
    val viewCount: Int = 0
) {

    /** 是否强制更新（后端用 0/1） */
    val mandatory: Boolean
        get() = isMandatory == 1

    /** 下载链接是否有效 */
    fun hasValidDownloadUrl(): Boolean {
        val u = downloadUrl?.trim().orEmpty()
        return u.isNotEmpty() && u != "无" && u != "-" && u.lowercase() != "null"
    }

    /**
     * ✅ 人类可读的发布时间
     *
     * 后端示例：2025-12-26T21:27:46.000Z（UTC）
     * 本地显示：2025-12-27 06:27（按系统时区）
     */
    fun readableReleaseDate(
        zoneId: ZoneId = ZoneId.systemDefault(),
        pattern: String = "yyyy-MM-dd HH:mm"
    ): String {
        val raw = releaseDate?.trim().orEmpty()
        if (raw.isBlank()) return "-"

        val outFmt = DateTimeFormatter
            .ofPattern(pattern, Locale.getDefault())
            .withZone(zoneId)

        // 1) 标准 UTC/Z：2025-12-26T21:27:46.000Z
        runCatching {
            val instant = Instant.parse(raw)
            val formatted = outFmt.format(instant)
//            Log.i("UpdateDate", "PARSE_OK raw=$raw -> $formatted zone=$zoneId")
            return formatted
        }.onFailure {
//            Log.w("UpdateDate", "Instant.parse failed: raw=$raw", it)
        }


        // 2) 带偏移：2025-12-26T21:27:46+08:00
        runCatching {
            val odt = OffsetDateTime.parse(raw)
            return odt.atZoneSameInstant(zoneId)
                .format(DateTimeFormatter.ofPattern(pattern))
        }.onFailure {
//            Log.w("UpdateDate", "OffsetDateTime.parse failed: raw=$raw", it)
        }

        // 3) 不带时区：2025-12-26T21:27:46.000（当作 UTC 或服务器本地时间，你自己选）
        runCatching {
            val ldt = LocalDateTime.parse(raw)
            // 这里我按“当作 UTC”处理：更接近你现在的 Z 语义
            return ldt.atZone(ZoneId.of("UTC"))
                .withZoneSameInstant(zoneId)
                .format(DateTimeFormatter.ofPattern(pattern))
        }.onFailure {
//            Log.w("UpdateDate", "LocalDateTime.parse failed: raw=$raw", it)
        }

        // 兜底：真的解析不了就原样
        return raw
    }
}
