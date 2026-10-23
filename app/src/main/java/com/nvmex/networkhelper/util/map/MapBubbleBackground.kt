package com.nvmex.networkhelper.util.map

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** One continuous outline keeps the translucent card and its pointer free of a visible seam. */
internal class MapBubbleBackground(private val density: Float, private val dark: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val inset = density
        val tip = 9 * density
        val radius = 12 * density
        val rect = RectF(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - tip)
        val center = rect.centerX()
        path.reset()
        path.moveTo(rect.left + radius, rect.top)
        path.lineTo(rect.right - radius, rect.top)
        path.quadTo(rect.right, rect.top, rect.right, rect.top + radius)
        path.lineTo(rect.right, rect.bottom - radius)
        path.quadTo(rect.right, rect.bottom, rect.right - radius, rect.bottom)
        path.lineTo(center + tip, rect.bottom)
        path.lineTo(center, bounds.bottom - inset)
        path.lineTo(center - tip, rect.bottom)
        path.lineTo(rect.left + radius, rect.bottom)
        path.quadTo(rect.left, rect.bottom, rect.left, rect.bottom - radius)
        path.lineTo(rect.left, rect.top + radius)
        path.quadTo(rect.left, rect.top, rect.left + radius, rect.top)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = if (dark) 0xE6182331.toInt() else 0xE6FFFFFF.toInt()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = if (dark) 0xAA70B9FF.toInt() else 0xAA2584D6.toInt()
        canvas.drawPath(path, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
