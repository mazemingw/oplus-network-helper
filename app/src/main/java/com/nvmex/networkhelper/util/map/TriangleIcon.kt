package com.nvmex.networkhelper.util.map

import android.graphics.*
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory

//绘制红色三角形 基站标
fun makeRedTriangleIcon(sizePx: Int = 54): BitmapDescriptor {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
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