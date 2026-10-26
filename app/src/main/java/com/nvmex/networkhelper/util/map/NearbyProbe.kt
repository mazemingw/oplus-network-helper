package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.network.model.NearbyCellsResponse
import java.util.Locale

/** Uses the existing single-band endpoint, including its semantics for unprefixed band numbers. */
fun nearbyProbeBands(input: String?, rat: String?): List<String?> {
    if (input.isNullOrBlank()) return listOf(null)
    val tokens = input.split(',', '，').map(String::trim).filter(String::isNotEmpty)
    require(tokens.isNotEmpty()) { "invalid_band" }
    val bands = tokens.map { token ->
        val match = Regex("^([bBnN])?([1-9]\\d{0,2})$").matchEntire(token)
            ?: throw IllegalArgumentException("invalid_band")
        val prefix = match.groupValues[1].lowercase(Locale.ROOT)
        val inferredRat = when (prefix) { "b" -> "LTE"; "n" -> "NR"; else -> null }
        require(rat == null || inferredRat == null || rat == inferredRat) { "band_rat_mismatch" }
        (when (inferredRat ?: rat) { "LTE" -> "B"; "NR" -> "n"; else -> "" }) + match.groupValues[2]
    }.distinct()
    require(bands.size <= 32) { "invalid_band" }
    return bands
}

fun mergeNearbyProbeResults(results: List<NearbyCellsResponse>, latitude: Double, longitude: Double): NearbyCellsResponse {
    fun distance(lat: Double?, lng: Double?) = if (lat != null && lng != null)
        driveDistanceMeters(latitude, longitude, lat, lng).takeIf { it.isFinite() } ?: Double.POSITIVE_INFINITY
        else Double.POSITIVE_INFINITY
    return NearbyCellsResponse(ok = true,
        lte = results.flatMap { it.lte }.distinctBy { row -> row.id?.let { "id:$it" }
            ?: "${row.eci}|${row.tac}|${row.earfcn}|${row.pci}|${row.latitude}|${row.longitude}" }
            .sortedBy { distance(it.latitude, it.longitude) },
        nr = results.flatMap { it.nr }.distinctBy { row -> row.id?.let { "id:$it" }
            ?: "${row.gcell_id}|${row.nr_tac}|${row.nr_arfcn}|${row.nr_pci}|${row.latitude}|${row.longitude}" }
            .sortedBy { distance(it.latitude, it.longitude) },
        truncated = results.any { it.truncated })
}
