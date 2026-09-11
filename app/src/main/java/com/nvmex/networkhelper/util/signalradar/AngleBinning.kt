package com.nvmex.networkhelper.util.signalradar

import kotlin.math.floor

private const val BIN_COUNT = 12
private const val BIN_SIZE = 360f / BIN_COUNT // 30°

fun binIndexOf(headingDeg: Float): Int {
    val h = ((headingDeg % 360f) + 360f) % 360f
    return floor(h / BIN_SIZE).toInt().coerceIn(0, BIN_COUNT - 1)
}

fun binCenterDeg(bin: Int): Float = bin * BIN_SIZE + BIN_SIZE / 2f

fun medianInt(values: List<Int>): Int? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    return s[s.size / 2]
}

fun medianFloat(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    return s[s.size / 2]
}
