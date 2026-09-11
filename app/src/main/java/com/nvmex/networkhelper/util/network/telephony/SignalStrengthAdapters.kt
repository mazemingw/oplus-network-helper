package com.nvmex.networkhelper.util.network.telephony

import android.annotation.SuppressLint
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthLte
import android.telephony.SignalStrength

/**
 * 这里放“从 SignalStrength 补齐各制式缺失字段”的兼容逻辑
 */
object SignalStrengthAdapters {

    @SuppressLint("NewApi")
    fun lteRssnrCompat(sig: SignalStrength?): String? {
        if (sig == null) return null

        return runCatching {
            val lte = sig.getCellSignalStrengths(CellSignalStrengthLte::class.java).firstOrNull()
                ?: return@runCatching null

            val value = runCatching { lte.rssnr }.getOrNull()
                ?: runCatching {
                    val m = lte.javaClass.methods.firstOrNull { it.name == "getRssnr" }
                    (m?.invoke(lte) as? Int)
                }.getOrNull()

            value?.takeIf { it != CellInfo.UNAVAILABLE && it != Int.MAX_VALUE }
                ?.let { raw ->
                    val real = if (raw > 50 || raw < -50) {
                        raw / 10.0
                    } else {
                        raw.toDouble()
                    }
                    "${"%.1f".format(real)} dB"
                }
        }.getOrNull()
    }
}