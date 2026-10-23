package com.nvmex.networkhelper.util.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class ServingStationLookup<T>(val rows: List<T>, val anchor: T?, val matchedWithoutPosition: Boolean)

/** A site endpoint may be absent, incomplete or fail while the home panel's cell endpoint works. */
internal suspend fun <T> lookupServingStation(
    local: suspend () -> List<T>,
    site: suspend () -> List<T>,
    cell: suspend () -> List<T>,
    match: (List<T>) -> T?,
    candidates: (List<T>) -> List<T>,
    hasPosition: (T) -> Boolean
): ServingStationLookup<T> {
    var missingPosition = false
    var remoteSucceeded = false
    var remoteError: Exception? = null
    for ((index, query) in listOf(local, site, cell).withIndex()) {
        val rows = try {
            query().also { if (index > 0) remoteSucceeded = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (index > 0) remoteError = error
            continue
        }
        coroutineContext.ensureActive()
        val anchor = match(rows)
        if (anchor != null) return ServingStationLookup(rows, anchor, false)
        if (candidates(rows).any { !hasPosition(it) }) missingPosition = true
    }
    if (!remoteSucceeded && !missingPosition) remoteError?.let { throw it }
    return ServingStationLookup(emptyList(), null, missingPosition)
}
