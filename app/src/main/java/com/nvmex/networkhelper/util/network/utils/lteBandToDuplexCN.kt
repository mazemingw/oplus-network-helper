package com.nvmex.networkhelper.util.network.utils

 fun lteBandToDuplexCN(band: Int): String {
    val tdd = setOf(34, 38, 39, 40, 41)
    return if (band in tdd) "TDD" else "FDD"
}