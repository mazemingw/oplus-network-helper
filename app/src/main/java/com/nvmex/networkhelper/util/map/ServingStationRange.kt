package com.nvmex.networkhelper.util.map

/** Product guard against incorrect/reused cell identifiers, not a theoretical radio coverage limit. */
internal const val SERVING_STATION_MAX_DISTANCE_METERS = 50_000.0

/** Engineering coordinates and this reference are both WGS84. */
internal data class ServingStationReference(val latitude: Double, val longitude: Double)

internal fun servingStationReference(mapLatitude: Double?, mapLongitude: Double?): ServingStationReference? {
    if (!validStationCoordinates(mapLatitude, mapLongitude)) return null
    val (latitude, longitude) = Gcj02.gcj02ToWgs84(mapLatitude!!, mapLongitude!!)
    return ServingStationReference(latitude, longitude)
}

internal fun servingStationInRange(latitude: Double?, longitude: Double?, reference: ServingStationReference?): Boolean {
    if (reference == null || !validStationCoordinates(reference.latitude, reference.longitude) ||
        !validStationCoordinates(latitude, longitude)) return false
    return driveDistanceMeters(reference.latitude, reference.longitude, latitude!!, longitude!!) <=
        SERVING_STATION_MAX_DISTANCE_METERS
}
