package com.nvmex.networkhelper.util.network.telephony

/**
 * 根据 NR 下行中心频率（MHz）推断 NR Band（中国常用 + 指定频段）
 *
 * ⚠️ 仅用于 id.bands 为空时的兜底推断
 * ⚠️ 范围偏保守，避免误判
 */
fun nrGuessBandByDlMhzCN(dlMhz: Double?): Int? {
    if (dlMhz == null) return null

    return when {

        // ===== FDD =====

        // n1: 2100 MHz (DL 2110–2170)
        dlMhz in 2110.0..2170.0 -> 1

        // n3: 1800 MHz (DL 1805–1880)
        dlMhz in 1805.0..1880.0 -> 3

        // n5: 850 MHz (DL 869–894)
        dlMhz in 869.0..894.0 -> 5

        // n8: 900 MHz (DL 925–960)
        dlMhz in 925.0..960.0 -> 8

        // n28: 700 MHz (DL 758–803)
        dlMhz in 758.0..803.0 -> 28


        // ===== TDD =====

        // n41: 2.6 GHz (DL ~2496–2690)
        dlMhz in 2496.0..2690.0 -> 41

        // n77: 3.7 GHz (DL 3700–4200)
        dlMhz in 3700.0..4200.0 -> 77

        // n78: 3.5 GHz (DL 3300–3800)
        dlMhz in 3300.0..3800.0 -> 78

        // n79: 4.9 GHz (DL 4400–5000)
        dlMhz in 4400.0..5000.0 -> 79

        // n83: 700 MHz SUL（上行增强，DL 703–748）
        // ⚠️ 注意：n83 本质是 SUL，但 DL 频率仍然落在这里
        dlMhz in 703.0..748.0 -> 83

        else -> null
    }
}

