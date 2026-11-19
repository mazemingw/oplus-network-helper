package dev.ririxi.bunnylock.root

import android.os.Build
import dev.ririxi.bunnylock.backend.mediatek.Mediatek
import dev.ririxi.bunnylock.backend.pixel.Pixel
import dev.ririxi.bunnylock.backend.qualcomm.Qualcomm
import dev.ririxi.bunnylock.backend.samsung.Samsung
import dev.ririxi.bunnylock.modem.Chipset
import dev.ririxi.bunnylock.modem.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Unisoc's RIL owns these ports and takes only one reader, so they are listed but never picked on their own.
private val RIL_OWNED_NODES = listOf("/dev/stty_lte0", "/dev/stty_nr0")

fun detectDevice(): JSONObject {
    val socManufacturer = Sys.prop("ro.soc.manufacturer")
    val platform = Sys.prop("ro.board.platform")
    val ril = Sys.prop("gsm.version.ril-impl")
    val baseband = Sys.prop("gsm.version.baseband").ifEmpty { Sys.prop("ro.baseband") }
    val chipsetHints = listOf(socManufacturer, Sys.prop("ro.hardware"), platform, ril, Build.HARDWARE).joinToString(" ").lowercase()

    val qrtr = Qualcomm.available()

    val knownAtNodes = Mediatek.CANDIDATES + listOf(Pixel.NODE, "/dev/smd7", "/dev/smd11", "/dev/at_mdm0") + RIL_OWNED_NODES
    val atNodes = knownAtNodes.distinct().filter { File(it).exists() }
    val pixelRouter = Pixel.available()
    val rilPassthrough = runCatching { OemHook.available() }.getOrDefault(false)
    val defaultAtNode =
        Pixel.NODE.takeIf { pixelRouter }
            ?: atNodes.firstOrNull { it.startsWith("/dev/radio") }
            ?: atNodes.firstOrNull { it !in RIL_OWNED_NODES }
            ?: rilNode(1).takeIf { rilPassthrough }
            ?: ""

    return JSONObject()
        .put("chipset", chipsetOf(qrtr, socManufacturer, chipsetHints).name)
        .put("qrtr", qrtr)
        .put("socModel", Sys.prop("ro.soc.model"))
        .put("platform", platform)
        .put("ril", ril)
        .put("baseband", baseband)
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("atNodes", JSONArray(atNodes))
        .put("defaultAtNode", defaultAtNode)
        .put("pixelRouter", pixelRouter)
        .put("samsungMenu", Samsung.hasMenu())
        .put("aospChannels", runCatching { Aosp.hasMethod("setSystemSelectionChannels", 3) }.getOrDefault(false))
        .put("aospNrDc", runCatching { Aosp.nrDcConfigurable() }.getOrDefault(false))
        .put("rilPassthrough", rilPassthrough)
        .put("samsungNrMode", runCatching { Samsung.nrModeAvailable() }.getOrDefault(false))
        .put("rilPassthroughStatus", runCatching { OemHook.status() }.getOrElse { it.toText() }.toJson())
        .put("rilPassthroughChecked", JSONArray(runCatching { OemHook.diagnostics() }.getOrDefault(emptyList()).map { it.toJson() }))
}

// A modem that answers QMI over QRTR is Qualcomm's, whatever the properties say.
private fun chipsetOf(
    qrtr: Boolean,
    socManufacturer: String,
    hints: String,
): Chipset =
    when {
        qrtr || "qti" in hints || "qcom" in hints || "qualcomm" in hints -> Chipset.QUALCOMM
        Sys.isMediatek(hints) -> Chipset.MEDIATEK
        "google" in socManufacturer.lowercase() || Regex("gs\\d{3}|zuma|laguna|malibu").containsMatchIn(hints) -> Chipset.TENSOR
        "samsung" in socManufacturer.lowercase() || "exynos" in hints || Regex("s5e\\d{4}|erd\\d{4}").containsMatchIn(hints) -> Chipset.EXYNOS
        "unisoc" in hints || "spreadtrum" in hints || "sprd" in hints || Regex("\\bums\\d{3}|\\bsp\\d{4}|\\bsc\\d{4}").containsMatchIn(hints) -> Chipset.UNISOC
        else -> Chipset.UNKNOWN
    }
