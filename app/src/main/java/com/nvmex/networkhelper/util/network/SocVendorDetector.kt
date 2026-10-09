package com.nvmex.networkhelper.util.network

import android.os.Build
import java.util.concurrent.TimeUnit

enum class SocVendor { QUALCOMM, MEDIATEK, OTHER, UNKNOWN }

/** Detects the modem/SoC family without requiring root. */
object SocVendorDetector {
    val vendor: SocVendor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val fromBuild = classify(buildIdentifiers())
        if (fromBuild == SocVendor.QUALCOMM || fromBuild == SocVendor.MEDIATEK) {
            fromBuild
        } else {
            classify(buildIdentifiers() + readableSystemProperties())
        }
    }

    fun isQualcomm(): Boolean = vendor == SocVendor.QUALCOMM

    internal fun classify(values: List<String?>): SocVendor {
        val text = values.filterNotNull().joinToString(" ").lowercase()
        if (text.isBlank()) return SocVendor.UNKNOWN
        if (
            listOf("qualcomm", "qcom", " qti", "snapdragon").any(text::contains) ||
            Regex("(^|[^a-z0-9])(msm|sdm|sm|qcs|qcm|sa)[0-9]{3,5}([^a-z0-9]|$)").containsMatchIn(text) ||
            listOf("kona", "lahaina", "taro", "kalama", "pineapple", "waipio", "bengal", "holi", "atoll", "lito", "trinket").any(text::contains)
        ) return SocVendor.QUALCOMM
        if (
            listOf("mediatek", "dimensity", "helio").any(text::contains) ||
            Regex("(^|[^a-z0-9])mt[0-9]{4}([^a-z0-9]|$)").containsMatchIn(text)
        ) return SocVendor.MEDIATEK
        return SocVendor.OTHER
    }

    private fun buildIdentifiers(): List<String?> = buildList {
        add(Build.HARDWARE)
        add(Build.BOARD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Build.SOC_MANUFACTURER)
            add(Build.SOC_MODEL)
        }
    }

    private fun readableSystemProperties(): List<String> = runCatching {
        val command = listOf(
            "ro.soc.manufacturer", "ro.soc.model", "ro.board.platform",
            "ro.hardware", "ro.hardware.chipname"
        ).joinToString("; ") { "getprop $it" }
        val process = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
            process.destroy()
            return@runCatching emptyList()
        }
        process.inputStream.bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
    }.getOrDefault(emptyList())
}
