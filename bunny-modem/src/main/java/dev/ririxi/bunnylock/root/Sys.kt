package dev.ririxi.bunnylock.root

import android.os.Build
import android.system.Os
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** System properties and shell commands, and what they reveal about the phone's modem. */
object Sys {
    // A command's children can keep its output open after it has ended or been killed. They only get this long.
    private const val OUTPUT_DRAIN_MS = 1000L

    fun prop(key: String): String =
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
        }.getOrDefault("")

    /** Runs a shell command and returns what it printed. A command still running after [timeoutSec] is killed. */
    fun sh(
        command: String,
        timeoutSec: Long = 15,
    ): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        val output = ByteArrayOutputStream()
        // Reading on this thread would wait for the command to close its output, so the timeout could never cut in.
        val reader = thread(name = "bunny-sh", isDaemon = true) { runCatching { process.inputStream.copyTo(output) } }
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) process.destroyForcibly()
        reader.join(OUTPUT_DRAIN_MS)
        return output.toString(Charsets.UTF_8.name()).trim()
    }

    fun detect(): JSONObject {
        val socManufacturer = prop("ro.soc.manufacturer")
        val socModel = prop("ro.soc.model")
        val hardware = prop("ro.hardware")
        val platform = prop("ro.board.platform")
        val ril = prop("gsm.version.ril-impl")
        val baseband = prop("gsm.version.baseband").ifEmpty { prop("ro.baseband") }
        val chipsetHints = listOf(socManufacturer, hardware, platform, ril, Build.HARDWARE).joinToString(" ").lowercase()

        val qrtr = Qualcomm.available()
        val vendor = chipsetVendor(qrtr, socManufacturer, chipsetHints)

        val knownAtNodes =
            Mediatek.CANDIDATES + listOf(Pixel.NODE, "/dev/smd7", "/dev/smd11", "/dev/at_mdm0", "/dev/stty_lte0", "/dev/stty_nr0")
        val atNodes = JSONArray(knownAtNodes.distinct().filter { File(it).exists() })

        val samsungMenu =
            Os.getuid() == 0 &&
                SAMSUNG_MENUS.any { component ->
                    val packageName = component.substringBefore('/')
                    sh("pm path $packageName").startsWith("package:")
                }

        return JSONObject()
            .put("vendor", vendor)
            .put("qrtr", qrtr)
            .put("socManufacturer", socManufacturer)
            .put("socModel", socModel)
            .put("hardware", hardware)
            .put("platform", platform)
            .put("ril", ril)
            .put("baseband", baseband)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("atNodes", atNodes)
            .put("pixelRouter", Pixel.available())
            .put("samsungMenu", samsungMenu)
            .put("aospChannels", runCatching { Aosp.hasMethod("setSystemSelectionChannels", 3) }.getOrDefault(false))
            .put("aospNrDc", runCatching { Aosp.nrDcConfigurable() }.getOrDefault(false))
            .put("mtkOemHook", runCatching { OemHook.available() }.getOrDefault(false))
            .put("samsungNrMode", runCatching { Samsung.nrModeAvailable() }.getOrDefault(false))
            .put("mtkOemHookStatus", runCatching { OemHook.status() }.getOrElse { it.toText() }.toJson())
            .put("mtkOemHookChecked", JSONArray(runCatching { OemHook.diagnostics() }.getOrDefault(emptyList()).map { it.toJson() }))
    }

    /** [qrtr] settles it: a modem that answers QMI over QRTR is Qualcomm's, whatever the properties say. [hints] is lowercase. */
    private fun chipsetVendor(
        qrtr: Boolean,
        socManufacturer: String,
        hints: String,
    ): String =
        when {
            qrtr || "qti" in hints || "qcom" in hints || "qualcomm" in hints -> "qualcomm"
            "mediatek" in hints || Regex("\\bmt\\d{4}").containsMatchIn(hints) -> "mediatek"
            "google" in socManufacturer.lowercase() || Regex("gs\\d{3}|zuma|laguna|malibu").containsMatchIn(hints) -> "tensor"
            "samsung" in socManufacturer.lowercase() || "exynos" in hints || Regex("s5e\\d{4}|erd\\d{4}").containsMatchIn(hints) -> "exynos"
            "unisoc" in hints || "spreadtrum" in hints || Regex("\\bums\\d{3}|\\bsp\\d{4}").containsMatchIn(hints) -> "unisoc"
            else -> "unknown"
        }

    /** One UI 6+ first, then the older standalone package. Root only. */
    private val SAMSUNG_MENUS =
        listOf(
            "com.samsung.android.app.telephonyui/.hiddennetworksetting.MainActivity",
            "com.samsung.hiddennetworksetting/.MainActivity",
        )

    // A refused launch prints a SecurityException, not an "Error" line.
    private val AM_START_FAILURE_MARKERS = listOf("Error", "Exception", "does not exist")

    fun openSamsungMenu(): JSONObject {
        val failures = ArrayList<String>()
        for (component in SAMSUNG_MENUS) {
            val output = sh("am start -n $component")
            if (AM_START_FAILURE_MARKERS.none { it in output }) return JSONObject().put("started", component)
            failures += failureLine(output)
        }
        val denied = failures.firstOrNull { "Permission Denial" in it }
        if (denied !== null) fail(R.string.aosp_with_details, Text(R.string.sys_samsung_menu_denied), denied)
        val details = failures.filter { it.isNotEmpty() }.joinToString(" / ")
        if (details.isEmpty()) fail(R.string.sys_samsung_menu_missing)
        fail(R.string.aosp_with_details, Text(R.string.sys_samsung_menu_missing), details)
    }

    private fun failureLine(amStartOutput: String): String {
        val lines = amStartOutput.lines().map { it.trim() }
        return lines.firstOrNull { "Permission Denial" in it || it.startsWith("Error:") } ?: lines.lastOrNull { it.isNotEmpty() }.orEmpty()
    }
}
