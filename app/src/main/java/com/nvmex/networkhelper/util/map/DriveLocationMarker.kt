package com.nvmex.networkhelper.util.map

import android.animation.ValueAnimator
import android.graphics.*
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import com.amap.api.maps.AMap
import com.amap.api.maps.model.*

/** Smooth presentation only; raw recording positions and timestamps are never interpolated. */
internal class DriveLocationMarker(private val map: AMap, private val density: Float) {
    private var marker: Marker? = null
    private var animator: ValueAnimator? = null
    private var displayedBearing = 0.0
    private var lastUpdateAt: Long? = null
    var onFrame: ((LatLng, Double) -> Unit)? = null
    fun displayedPosition(): LatLng? = marker?.position
    private val icon by lazy {
        val side = (26 * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply {
            moveTo(side * .5f, side * .08f); lineTo(side * .83f, side * .87f)
            lineTo(side * .5f, side * .7f); lineTo(side * .17f, side * .87f); close()
        }
        paint.color = 0xFFFFFFFF.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * density
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL; paint.color = 0xFF1296F3.toInt(); canvas.drawPath(path, paint)
        BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    fun update(target: LatLng, bearing: Double?, estimated: Boolean, visible: Boolean) {
        animator?.cancel()
        val now = SystemClock.elapsedRealtime()
        val durationMillis = drivePresentationDuration(lastUpdateAt?.let { now - it }, estimated)
        lastUpdateAt = now
        if (marker == null) {
            marker = map.addMarker(MarkerOptions().position(target).icon(icon).anchor(.5f, .5f)
                .zIndex(80f).setFlat(true).infoWindowEnable(false))?.apply { isClickable = false }
            displayedBearing = bearing ?: 0.0
        }
        val point = marker ?: return
        point.isVisible = visible
        point.alpha = if (estimated) .8f else 1f
        val from = point.position
        val oldBearing = displayedBearing
        val turn = driveAngleDelta(oldBearing, bearing ?: oldBearing)
        val distance = driveDistanceMeters(from.latitude, from.longitude, target.latitude, target.longitude)
        if (!visible || distance > 250 || distance < .15 && kotlin.math.abs(turn) < .5) {
            point.position = target
            displayedBearing = driveNormalizeAngle(oldBearing + turn)
            point.rotateAngle = (360 - displayedBearing).toFloat()
            if (visible) onFrame?.invoke(point.position, displayedBearing)
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMillis
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = (it.animatedValue as Float).toDouble()
                point.position = LatLng(from.latitude + (target.latitude - from.latitude) * t,
                    from.longitude + (target.longitude - from.longitude) * t)
                displayedBearing = driveNormalizeAngle(oldBearing + turn * t)
                point.rotateAngle = (360 - displayedBearing).toFloat()
                if (visible) onFrame?.invoke(point.position, displayedBearing)
            }
            start()
        }
    }
    fun setVisible(visible: Boolean) { marker?.isVisible = visible }
    fun pause() { animator?.cancel() }
    fun close() { pause(); marker?.remove(); marker = null }
}
