package com.nvmex.networkhelper.util.network.utils

 fun lteFddDlUlSpacingMhzCN(band: Int): Double? = when (band) {
    1 -> 190.0
    3 -> 95.0
    5 -> 45.0
    8 -> 45.0
    28 -> 55.0 // 如果你后面加了 LTE B28，可留着；没有也无所谓
    else -> null
}