package com.nvmex.networkhelper.util.network.utils

import kotlin.math.roundToInt

fun formatMhz(mhz: Double): String {
    val v = (mhz * 100.0).roundToInt() / 100.0
    return "$v MHz"
}