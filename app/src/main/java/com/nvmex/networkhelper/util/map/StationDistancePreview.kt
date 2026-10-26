package com.nvmex.networkhelper.util.map

import android.graphics.Point
import com.amap.api.maps.AMap
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.maps.model.Text
import com.amap.api.maps.model.TextOptions
import kotlin.math.hypot

/** A temporary manual measurement. It never changes the matched serving station or saved records. */
internal class StationDistancePreview(private val map: AMap, private val density: Float,
    private val caption: (Double) -> String) {
    private var origin: LatLng? = null
    private var site: LatLng? = null
    private var line: Polyline? = null
    private var middleLabel: Text? = null
    private var nearLabel: Text? = null
    private var distanceMeters: Double? = null
    private val color = 0xFF198FEC.toInt()

    fun select(position: LatLng) { site = position; redraw() }
    fun updatePosition(position: LatLng?) { origin = position; redraw() }

    fun clear() {
        site = null
        clearDrawing()
    }

    private fun clearDrawing() {
        line?.remove(); line = null
        middleLabel?.remove(); middleLabel = null
        nearLabel?.remove(); nearLabel = null
        distanceMeters = null
    }

    private fun redraw() {
        val from = origin
        val to = site
        val a = servingStationReference(from?.latitude, from?.longitude)
        val b = servingStationReference(to?.latitude, to?.longitude)
        if (from == null || to == null || a == null || b == null) { clearDrawing(); return }
        distanceMeters = driveDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        if (line == null) line = map.addPolyline(PolylineOptions().add(from, to).color(color)
            .width(3f * density).setDottedLine(true).zIndex(55f))
        else line?.points = listOf(from, to)
        middleLabel = label(middleLabel, LatLng((from.latitude + to.latitude) / 2, (from.longitude + to.longitude) / 2))
        onCameraChanged()
    }

    private fun label(existing: Text?, position: LatLng): Text? {
        val text = caption(distanceMeters ?: return null)
        return existing?.apply { this.position = position; this.text = text; isVisible = true }
            ?: map.addText(TextOptions().position(position).text(text).fontColor(color)
                .backgroundColor(0xEFFFFFFF.toInt()).fontSize(26).zIndex(56f))
    }

    fun onCameraChanged() {
        val from = origin ?: return
        val to = site ?: return
        if (distanceMeters == null) return
        val projection = map.projection
        val a = projection.toScreenLocation(from)
        val b = projection.toScreenLocation(to)
        val pixels = hypot(b.x.toDouble() - a.x, b.y.toDouble() - a.y)
        if (!pixels.isFinite() || pixels < 180 * density) { nearLabel?.isVisible = false; return }
        val fraction = 64 * density / pixels
        val position = projection.fromScreenLocation(Point((a.x + (b.x - a.x) * fraction).toInt(),
            (a.y + (b.y - a.y) * fraction).toInt()))
        nearLabel = label(nearLabel, position)
    }
}
