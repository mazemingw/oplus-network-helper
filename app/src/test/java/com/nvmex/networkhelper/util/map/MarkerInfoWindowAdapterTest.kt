package com.nvmex.networkhelper.util.map

import com.amap.api.maps.InfoWindowParams
import org.junit.Assert.*
import org.junit.Test

class MarkerInfoWindowAdapterTest {
    @Test fun sdkNullOverlayProbeReturnsViewTypeWithoutTryingToBuildAMarkerView() {
        val adapter = MarkerInfoWindowAdapter { error("No marker was supplied by the SDK") }
        repeat(2) {
            val params = adapter.getInfoWindowParams(null)
            assertEquals(InfoWindowParams.INFOWINDOW_TYPE_VIEW, params.infoWindowType)
            assertNull(params.infoWindow)
            assertNull(params.infoContents)
        }
    }
}
