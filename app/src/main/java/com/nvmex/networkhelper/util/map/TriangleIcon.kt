package com.nvmex.networkhelper.util.map

import android.graphics.*
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory

//绘制红色三角形 基站标
fun makeRedTriangleIcon(sizePx: Int = 54): BitmapDescriptor = makeTriangleIcon(sizePx, Color.RED)

fun makeCircleIcon(sizePx: Int = 54, color: Int = Color.RED): BitmapDescriptor {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val center = sizePx / 2f
    val radius = sizePx * .36f
    canvas.drawCircle(center, center, radius, paint)
    paint.style = Paint.Style.STROKE
    paint.color = Color.WHITE
    paint.strokeWidth = (sizePx * .07f).coerceAtLeast(2f)
    canvas.drawCircle(center, center, radius, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

fun makeTriangleIcon(sizePx: Int = 54, color: Int): BitmapDescriptor {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = Color.WHITE
        strokeWidth = (sizePx * 0.08f).coerceAtLeast(2f)
    }

    val path = Path().apply {
        moveTo(sizePx / 2f, 0f)              // 顶点
        lineTo(0f, sizePx.toFloat())         // 左下
        lineTo(sizePx.toFloat(), sizePx.toFloat()) // 右下
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, stroke)

    return BitmapDescriptorFactory.fromBitmap(bmp)
}
