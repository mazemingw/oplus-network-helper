package com.nvmex.networkhelper.util.network.utils

 fun nrFddDlUlSpacingMhzCN(band: Int): Double? = when (band) {
    1 -> 190.0
    3 -> 95.0
    5 -> 45.0
    8 -> 45.0
    28 -> 55.0
    else -> null
}