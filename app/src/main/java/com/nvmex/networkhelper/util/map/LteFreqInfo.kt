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
    LteBandDef(1, 0, 599, 2110.0, 0, 1920.0),
    LteBandDef(2, 600, 1199, 1930.0, 600, 1850.0),
    LteBandDef(3, 1200, 1949, 1805.0, 1200, 1710.0),
    LteBandDef(4, 1950, 2399, 2110.0, 1950, 1710.0),
    LteBandDef(5, 2400, 2649, 869.0, 2400, 824.0),
    LteBandDef(6, 2650, 2749, 875.0, 2650, 830.0),
    LteBandDef(7, 2750, 3449, 2620.0, 2750, 2500.0),
    LteBandDef(8, 3450, 3799, 925.0, 3450, 880.0),
    LteBandDef(9, 3800, 4149, 1844.9, 3800, 1749.9),
    LteBandDef(10, 4150, 4749, 2110.0, 4150, 1710.0),
    LteBandDef(11, 4750, 4949, 1475.9, 4750, 1427.9),
    LteBandDef(12, 5010, 5179, 729.0, 5010, 699.0),
    LteBandDef(13, 5180, 5279, 746.0, 5180, 777.0),
    LteBandDef(14, 5280, 5379, 758.0, 5280, 788.0),
    LteBandDef(17, 5730, 5849, 734.0, 5730, 704.0),
    LteBandDef(18, 5850, 5999, 860.0, 5850, 815.0),
    LteBandDef(19, 6000, 6149, 875.0, 6000, 830.0),
    LteBandDef(20, 6150, 6449, 791.0, 6150, 832.0),
    LteBandDef(21, 6450, 6599, 1495.9, 6450, 1447.9),
    LteBandDef(22, 6600, 7399, 3510.0, 6600, 3410.0),
    LteBandDef(23, 7500, 7699, 2180.0, 7500, 2000.0),
    LteBandDef(24, 7700, 8039, 1525.0, 7700, 1626.5),
    LteBandDef(25, 8040, 8689, 1930.0, 8040, 1850.0),
    LteBandDef(26, 8690, 9039, 859.0, 8690, 814.0),
    LteBandDef(27, 9040, 9209, 852.0, 9040, 807.0),
    LteBandDef(28, 9210, 9659, 758.0, 9210, 703.0),
    LteBandDef(29, 9660, 9769, 717.0, 9660, null),
    LteBandDef(30, 9770, 9869, 2350.0, 9770, 2305.0),
    LteBandDef(31, 9870, 9919, 462.5, 9870, 452.5),
    LteBandDef(32, 9920, 10359, 1452.0, 9920, null),
    LteBandDef(33, 36000, 36199, 1900.0, 36000, null),
    LteBandDef(34, 36200, 36349, 2010.0, 36200, null),
    LteBandDef(35, 36350, 36949, 1850.0, 36350, null),
    LteBandDef(36, 36950, 37549, 1930.0, 36950, null),
    LteBandDef(37, 37550, 37749, 1910.0, 37550, null),
    LteBandDef(38, 37750, 38249, 2570.0, 37750, null),
    LteBandDef(39, 38250, 38649, 1880.0, 38250, null),
    LteBandDef(40, 38650, 39649, 2300.0, 38650, null),
    LteBandDef(41, 39650, 41589, 2496.0, 39650, null),
    LteBandDef(42, 41590, 43589, 3400.0, 41590, null),
    LteBandDef(43, 43590, 45589, 3600.0, 43590, null),
    LteBandDef(44, 45590, 46589, 703.0, 45590, null),
    LteBandDef(46, 46790, 54539, 5150.0, 46790, null),
    LteBandDef(47, 54540, 55239, 5855.0, 54540, null),
    LteBandDef(48, 55240, 56739, 3550.0, 55240, null),
    LteBandDef(49, 56740, 58239, 3550.0, 56740, null),
    LteBandDef(50, 58240, 59089, 1432.0, 58240, null),
    LteBandDef(51, 59090, 59139, 1427.0, 59090, null),
    LteBandDef(52, 59140, 60139, 3300.0, 59140, null),
    LteBandDef(53, 60140, 60254, 2483.5, 60140, null),
    LteBandDef(65, 65536, 66435, 2110.0, 65536, 1920.0),
    LteBandDef(66, 66436, 67335, 2110.0, 66436, 1710.0),
    LteBandDef(67, 67336, 67535, 738.0, 67336, null),
    LteBandDef(68, 67536, 67835, 753.0, 67536, 698.0),
    LteBandDef(69, 67836, 68335, 2570.0, 67836, null),
    LteBandDef(70, 68336, 68585, 1995.0, 68336, 1695.0),
    LteBandDef(71, 68586, 68935, 617.0, 68586, 663.0),
    LteBandDef(72, 68936, 68985, 461.0, 68936, 451.0),
    LteBandDef(73, 68986, 69035, 460.0, 68986, 450.0),
    LteBandDef(74, 69036, 69465, 1475.0, 69036, 1427.0),
    LteBandDef(85, 70366, 70545, 728.0, 70366, 698.0),
    LteBandDef(87, 70546, 70595, 420.0, 70546, 410.0),
    LteBandDef(88, 70596, 70645, 422.0, 70596, 412.0),
)

/**
 * 仅用 earfcn 推断 LTE band 并计算 DL/UL 频率（MHz）。
 * 返回 null 表示不在当前内置 band 范围内（可自行扩展 LTE_CN_BANDS）。
 */
fun lteEarfcnToFreqMhzCN(earfcn: Int): LteFreqInfo? {
    val def = LTE_CN_BANDS.firstOrNull { earfcn in it.ndlStart..it.ndlEnd } ?: return null

    // DL：FDL = FDL_low + 0.1*(Ndl - Ndl_off)
    val dl = def.fdlLow + 0.1 * (earfcn - def.ndlOff)

    val isTdd = def.band in 33..53

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
