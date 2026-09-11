package com.nvmex.networkhelper.ui.network

import androidx.annotation.StringRes
import com.nvmex.networkhelper.R

enum class FollowVendor(
    val code: String,
    @param:StringRes val labelRes: Int
) {
    ZTE("Z", R.string.follow_vendor_zte),
    HUAWEI("H", R.string.follow_vendor_huawei),
    ERICSSON("E", R.string.follow_vendor_ericsson)
}

enum class FollowSiteType(
    val code: String,
    val serverValue: String,
    @param:StringRes val labelRes: Int
) {
    MACRO("H", "宏站", R.string.follow_site_macro),
    MICRO("W", "微站", R.string.follow_site_micro)
}

data class FollowRecordUiState(
    val isLoadingLocation: Boolean = false,
    val isSubmitting: Boolean = false,

    // 最终入库坐标：统一为 WGS84
    val longitude: Double? = null,
    val latitude: Double? = null,
    val coordType: String? = null, // 固定显示为最终坐标系，通常是 WGS84

    // 原始高德返回，便于调试
    val rawLongitude: Double? = null,
    val rawLatitude: Double? = null,
    val rawCoordType: String? = null,

    val cellName: String = "",
    val poiName: String? = null,
    val address: String? = null,

    val selectedVendor: FollowVendor? = null,
    val selectedSiteType: FollowSiteType? = null,

    val submitSuccess: Boolean = false,
    val submitMessage: String? = null,
    val errorMessage: String? = null
)
