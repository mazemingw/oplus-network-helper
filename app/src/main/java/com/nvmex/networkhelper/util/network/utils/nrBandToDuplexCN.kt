package com.nvmex.networkhelper.util.network.utils

 fun nrBandToDuplexCN(band: Int): String {
    val tdd = setOf(41, 78, 79)
    val fdd = setOf(1, 3, 5, 8, 28)
    return when {
        band in tdd -> "TDD"
        band in fdd -> "FDD"
        else -> "-"
    }
}