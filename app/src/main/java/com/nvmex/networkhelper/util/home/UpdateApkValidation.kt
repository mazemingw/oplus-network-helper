package com.nvmex.networkhelper.util.home

internal enum class UpdateApkCompatibility { COMPATIBLE, SIGNATURE_MISMATCH }

/** Pure metadata policy shared by fresh downloads and cached APKs. */
internal fun validateUpdateApk(
    packageName: String, expectedPackage: String,
    versionCode: Long, expectedVersion: Long, installedVersion: Long,
    apkSigners: Set<String>, apkSignerHistory: Set<String>, installedSigners: Set<String>
): UpdateApkCompatibility {
    require(packageName == expectedPackage) { "APK 包名不匹配" }
    require(versionCode == expectedVersion && versionCode > installedVersion) { "APK 版本不匹配或不是新版本" }
    require(apkSigners.isNotEmpty() && installedSigners.isNotEmpty()) { "无法读取 APK 签名" }
    val compatible = if (apkSigners.size > 1 || installedSigners.size > 1) {
        apkSigners == installedSigners
    } else apkSignerHistory.containsAll(installedSigners)
    return if (compatible) UpdateApkCompatibility.COMPATIBLE else UpdateApkCompatibility.SIGNATURE_MISMATCH
}
