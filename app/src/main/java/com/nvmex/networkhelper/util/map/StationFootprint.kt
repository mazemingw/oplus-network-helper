package com.nvmex.networkhelper.util.map

/** Explicit site types take precedence; zero alone must never turn a north-facing macro into an indoor site. */
internal fun isOmnidirectionalStation(
    siteType: String?, cellName: String?, azimuth: Int?, lteEarfcn: Int? = null
): Boolean {
    val type = siteType.orEmpty().lowercase().replace(" ", "").replace("-", "")
    val omniHints = listOf("室分", "室内", "微站", "微蜂窝", "皮站", "indoor", "micro", "pico", "femto", "smallcell", "全向", "omni")
    if (omniHints.any(type::contains)) return true
    if (listOf("宏", "室外", "macro", "outdoor").any(type::contains)) return false
    val name = cellName.orEmpty().lowercase()
    if (listOf("室分", "室内分布", "微站", "微蜂窝", "indoor", "pico", "femto").any(name::contains)) return true
    val indoorBand = lteEarfcn != null && (lteEarfcn in 36200..36349 || lteEarfcn in 38650..39649)
    return indoorBand && (azimuth == null || azimuth !in 1..359)
}
