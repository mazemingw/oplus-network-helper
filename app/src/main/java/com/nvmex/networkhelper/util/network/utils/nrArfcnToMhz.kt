package com.nvmex.networkhelper.util.network.utils

 fun nrArfcnToMhz(n: Int): Double {
    return when {
        n in 0..599_999 -> 0.005 * n
        n in 600_000..2_016_666 -> 3000.0 + 0.015 * (n - 600_000)
        else -> 0.0
    }
}