package com.nvmex.networkhelper.hotspot.utils

import android.graphics.Bitmap
import android.graphics.Color as AColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

 fun buildWifiQrPayload(
    ssid: String,
    password: String?,
    hidden: Boolean = false,
    security: String // "WPA" / "WEP" / "nopass"
): String {
    fun esc(s: String) = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace(":", "\\:")

    val s = esc(ssid)
    val p = password?.let { esc(it) }

    return buildString {
        append("WIFI:")
        append("T:").append(security).append(';')
        append("S:").append(s).append(';')
        if (security != "nopass") append("P:").append(p ?: "").append(';')
        append("H:").append(if (hidden) "true" else "false").append(';')
        append(';')
    }
}

 fun generateQrBitmap(
    content: String,
    sizePx: Int
): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix[x, y]) AColor.BLACK else AColor.WHITE)
        }
    }
    return bmp
}
