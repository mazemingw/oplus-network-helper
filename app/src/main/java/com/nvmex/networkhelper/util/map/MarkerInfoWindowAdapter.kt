package com.nvmex.networkhelper.util.map

import android.view.View
import com.amap.api.maps.AMap
import com.amap.api.maps.InfoWindowParams
import com.amap.api.maps.model.BasePointOverlay
import com.amap.api.maps.model.Marker

/** AMap queries the window type with a null overlay before requesting the actual marker view. */
internal class MarkerInfoWindowAdapter(private val createView: (Marker) -> View) : AMap.CommonInfoWindowAdapter {
    override fun getInfoWindowParams(overlay: BasePointOverlay?): InfoWindowParams = InfoWindowParams().apply {
        setInfoWindowType(InfoWindowParams.INFOWINDOW_TYPE_VIEW)
        if (overlay is Marker) setInfoWindow(createView(overlay))
    }
}
