package com.nvmex.networkhelper.util.home

import android.os.Build

fun detectOplusVendor(): OplusVendor {
    val m = Build.MANUFACTURER.lowercase()
    val b = Build.BRAND.lowercase()
    val d = Build.DEVICE.lowercase()
    val f = Build.FINGERPRINT.lowercase()

    return when {
        "oneplus" in m || "oneplus" in b || "oneplus" in f ->
            OplusVendor.ONEPLUS

        "realme" in m || "realme" in b || "realme" in f ->
            OplusVendor.REALME

        "oppo" in m || "oppo" in b || "oppo" in f ->
            OplusVendor.OPPO

        // 有些 realme / 一加会显示 oppo 底层
        "oplus" in f ->
            OplusVendor.OPLUS_GENERIC

        else ->
            OplusVendor.OTHER
    }
}

enum class OplusVendor {
    OPPO,
    REALME,
    ONEPLUS,
    OPLUS_GENERIC,
    OTHER
}
