package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.util.network.utils.nrArfcnToMhz
import java.util.Locale

/** Append physical frequencies without replacing the original channel number. Never guess NR UL from DL. */
fun mapChannelFrequency(rat: String, channel: Int?): String {
    if (channel == null || channel < 0) return "频率=—"
    fun mhz(value: Double) = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.') + " MHz"
    return when (rat.uppercase(Locale.ROOT)) {
        "LTE" -> lteEarfcnToFreqMhzCN(channel)?.let { info ->
            if (info.isTdd) "频率=${mhz(info.dlMhz)} (TDD)"
            else "DL=${mhz(info.dlMhz)}" + (info.ulMhz?.let { "  UL=${mhz(it)}" } ?: "")
        } ?: "频率=—"
        "NR" -> nrArfcnToMhz(channel).takeIf { it.isFinite() && it > 0 }?.let { "频率=${mhz(it)}" } ?: "频率=—"
        else -> "频率=—"
    }
}
