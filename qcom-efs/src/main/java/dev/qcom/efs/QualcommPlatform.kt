package dev.qcom.efs

import android.os.Build
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Positive identification only. OEM/phone brand is deliberately not a chipset signal. */
object QualcommPlatform {
    fun confirmed(vendor: String, hardware: String, soc: String): Boolean {
        val v = vendor.lowercase(); val h = hardware.lowercase(); val s = soc.lowercase()
        val all = "$v $h $s"
        if (listOf("mediatek", "mtk", "exynos", "unisoc", "spreadtrum", "kirin", "tensor").any { it in all } ||
            Regex("(^|[^a-z])mt[0-9]{4}").containsMatchIn(all)) return false
        return v.trim() in setOf("qualcomm", "qualcomm technologies, inc", "qualcomm technologies, inc.", "qti", "qcom") ||
            h.trim() in setOf("qcom", "qti") || Regex("^(sm|msm|apq|qcm|qcs)[0-9]{3,5}[a-z0-9-]*$").matches(s.trim())
    }

    @Volatile private var cached: Boolean? = null
    fun isQualcomm(): Boolean = cached ?: synchronized(this) {
        cached ?: run {
            fun prop(key: String) = runCatching {
                ProcessBuilder("getprop", key).start().inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            val vendor = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else prop("ro.soc.manufacturer")
            val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else prop("ro.soc.model")
            confirmed(vendor, Build.HARDWARE, model).also { cached = it }
        }
    }
}

@Composable fun rememberQualcommPlatform(): Boolean {
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { confirmed = withContext(Dispatchers.IO) { QualcommPlatform.isQualcomm() } }
    return confirmed
}
