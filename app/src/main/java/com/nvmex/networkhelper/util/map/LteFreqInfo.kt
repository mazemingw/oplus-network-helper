package com.nvmex.networkhelper.util.map

data class LteFreqInfo(
    val band: Int,
    val dlMhz: Double,
    val ulMhz: Double?,   // TDD 为 null
    val isTdd: Boolean
)

private data class LteBandDef(
    val band: Int,
    val ndlStart: Int,    // DL EARFCN 起始（含）
    val ndlEnd: Int,      // DL EARFCN 结束（含）
    val fdlLow: Double,   // DL 低端频率 (MHz)
    val ndlOff: Int,      // NDL offset
    val fulLow: Double?   // FDD UL 低端频率 (MHz)，TDD = null
)

/**
 * CN 常见 LTE band 的 NDL 区间 + 频率定义（用于 earfcn -> band -> freq）
 * 说明：
 * - DL: FDL = FDL_low + 0.1 * (Ndl - Ndl_off)
 * - UL: FDD 用上下行间隔反推：UL = DL - (FDL_low - FUL_low)
 */
private val LTE_CN_BANDS: List<LteBandDef> = listOf(
    // FDD
    LteBandDef(band = 1, ndlStart = 0,    ndlEnd = 599,  fdlLow = 2110.0, ndlOff = 0,    fulLow = 1920.0),
    LteBandDef(band = 3, ndlStart = 1200, ndlEnd = 1949, fdlLow = 1805.0, ndlOff = 1200, fulLow = 1710.0),
    LteBandDef(band = 5, ndlStart = 2400, ndlEnd = 2649, fdlLow = 869.0,  ndlOff = 2400, fulLow = 824.0),
    LteBandDef(band = 8, ndlStart = 3450, ndlEnd = 3799, fdlLow = 925.0,  ndlOff = 3450, fulLow = 880.0),

    // TDD
    LteBandDef(band = 34, ndlStart = 36200, ndlEnd = 36349, fdlLow = 2010.0, ndlOff = 36200, fulLow = null),
    LteBandDef(band = 38, ndlStart = 37750, ndlEnd = 38249, fdlLow = 2570.0, ndlOff = 37750, fulLow = null),
    LteBandDef(band = 39, ndlStart = 38250, ndlEnd = 38649, fdlLow = 1880.0, ndlOff = 38250, fulLow = null),
    LteBandDef(band = 40, ndlStart = 38650, ndlEnd = 39649, fdlLow = 2300.0, ndlOff = 38650, fulLow = null),

    // Band 41：常见到 41589（不同资料上限可能有差异，但对国内常用 earfcn 足够）
    LteBandDef(band = 41, ndlStart = 39650, ndlEnd = 41589, fdlLow = 2496.0, ndlOff = 39650, fulLow = null),
)

/**
 * 仅用 earfcn 推断 LTE band 并计算 DL/UL 频率（MHz）。
 * 返回 null 表示不在当前内置 band 范围内（可自行扩展 LTE_CN_BANDS）。
 */
fun lteEarfcnToFreqMhzCN(earfcn: Int): LteFreqInfo? {
    val def = LTE_CN_BANDS.firstOrNull { earfcn in it.ndlStart..it.ndlEnd } ?: return null

    // DL：FDL = FDL_low + 0.1*(Ndl - Ndl_off)
    val dl = def.fdlLow + 0.1 * (earfcn - def.ndlOff)

    val isTdd = def.fulLow == null

    // UL：FDD 用“上下行间隔”从 DL 反推
    val ul = def.fulLow?.let { fulLow ->
        val spacing = def.fdlLow - fulLow // e.g. B3: 1805-1710=95MHz
        val u = dl - spacing
        if (u.isFinite() && u > 0.0) u else null
    }

    return LteFreqInfo(
        band = def.band,
        dlMhz = dl,
        ulMhz = ul,
        isTdd = isTdd
    )
}