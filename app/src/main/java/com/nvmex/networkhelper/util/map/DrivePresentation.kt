package com.nvmex.networkhelper.util.map

/** Keep regular updates overlapping rather than pausing at the end of each one-second fix. */
internal fun drivePresentationDuration(intervalMillis: Long?, estimated: Boolean): Long {
    val interval = intervalMillis?.takeIf { it in 100..2500 } ?: if (estimated) 500L else 1000L
    return (interval * 1.2).toLong().coerceIn(250L, 1800L)
}
