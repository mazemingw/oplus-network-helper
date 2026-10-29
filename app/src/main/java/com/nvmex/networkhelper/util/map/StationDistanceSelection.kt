package com.nvmex.networkhelper.util.map

import com.amap.api.maps.model.LatLng

/** Manual distance previews retain the last valid fix during temporary location gaps. */
internal class StationDistanceSelection {
    var origin: LatLng? = null
        private set
    var station: LatLng? = null
        private set

    fun updatePosition(position: LatLng?) {
        position?.takeIf { servingStationReference(it.latitude, it.longitude) != null }?.let { origin = it }
    }

    fun select(position: LatLng) {
        station = position.takeIf { servingStationReference(it.latitude, it.longitude) != null }
    }

    fun clear() { station = null }
}
