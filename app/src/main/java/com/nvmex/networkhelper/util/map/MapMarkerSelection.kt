package com.nvmex.networkhelper.util.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.Marker

/** Decorate the actual clicked marker, retaining its signal/serving color and geographical anchor. */
internal class MapMarkerSelection(private val density: Float) {
    private data class Original(val marker: Marker, val icon: BitmapDescriptor?, val u: Float, val v: Float, val z: Float)
    private var original: Original? = null
    private val icons = LruCache<String, BitmapDescriptor>(16)

    fun select(marker: Marker) {
        if (original?.marker == marker) return
        clear()
        val icon = marker.options.icon
        original = Original(marker, icon, marker.anchorU, marker.anchorV, marker.zIndex)
        marker.zIndex = 100f
        if (icon == null) return
        val bitmap = icon.bitmap ?: return
        val padding = (6 * density).toInt().coerceAtLeast(2)
        val width = (bitmap.width * 1.15f).toInt()
        val height = (bitmap.height * 1.15f).toInt()
        val selected = icons.get(icon.id) ?: run {
            val image = Bitmap.createBitmap(width + padding * 2, height + padding * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val ring = RectF(padding / 2f, padding / 2f, image.width - padding / 2f, image.height - padding / 2f)
            paint.color = 0x442D9CFF
            canvas.drawOval(ring, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4 * density; paint.color = 0xFFFFFFFF.toInt()
            canvas.drawOval(ring, paint)
            paint.strokeWidth = 2 * density; paint.color = 0xFF168BEE.toInt()
            canvas.drawOval(ring, paint)
            paint.style = Paint.Style.FILL
            canvas.drawBitmap(bitmap, null, RectF(padding.toFloat(), padding.toFloat(),
                (padding + width).toFloat(), (padding + height).toFloat()), paint)
            BitmapDescriptorFactory.fromBitmap(image).also { icons.put(icon.id, it) }
        }
        val saved = original!!
        marker.setIcon(selected)
        marker.setAnchor((padding + saved.u * width) / selected.width, (padding + saved.v * height) / selected.height)
    }

    fun clear() {
        original?.let { saved ->
            if (!saved.marker.isRemoved) {
                saved.marker.hideInfoWindow()
                saved.icon?.let { saved.marker.setIcon(it) }
                saved.marker.setAnchor(saved.u, saved.v)
                saved.marker.zIndex = saved.z
            }
        }
        original = null
    }
}
