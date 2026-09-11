package com.nvmex.networkhelper.util.network.utils

 fun lteEarfcnToFreqMhzCN(band: Int, earfcn: Int): Pair<Double, Double?>? {
    data class LteBandDef(
        val fdlLow: Double,
        val ndlOff: Int,
        val fulLow: Double? // TDD 用 null
    )

    val def: LteBandDef = when (band) {
        // FDD
        1 -> LteBandDef(2110.0, 0, 1920.0)
        3 -> LteBandDef(1805.0, 1200, 1710.0)
        5 -> LteBandDef(869.0, 2400, 824.0)
        8 -> LteBandDef(925.0, 3450, 880.0)

        // TDD（UL 不存在/不显示）
        34 -> LteBandDef(2010.0, 36200, null)
        38 -> LteBandDef(2570.0, 37750, null)
        39 -> LteBandDef(1880.0, 38250, null)
        40 -> LteBandDef(2300.0, 38650, null)
        41 -> LteBandDef(2496.0, 39650, null)

        else -> return null
    }

    // ✅ DL：FDL = FDL_low + 0.1*(Ndl - Ndl_off)
    val dl = def.fdlLow + 0.1 * (earfcn - def.ndlOff)

    // ✅ UL：FDD 场景用“上下行间隔”从 DL 反推（不依赖 UL EARFCN）
    val ul = def.fulLow?.let { fulLow ->
        val spacing = def.fdlLow - fulLow // 例如 B3: 1805-1710=95MHz
        val u = dl - spacing
        // 防御：算出负数/离谱值就直接不给显示
        if (u.isFinite() && u > 0.0) u else null
    }

    return dl to ul
}