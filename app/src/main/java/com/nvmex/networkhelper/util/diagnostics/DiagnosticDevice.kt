package com.nvmex.networkhelper.util.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import dev.qcom.efs.DiagnosticEvents
import dev.qcom.efs.platform.RootPhone
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object DiagnosticDevice {
    fun snapshot(context: Context): JSONObject = JSONObject().apply {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        put("manufacturer", Build.MANUFACTURER); put("brand", Build.BRAND); put("model", Build.MODEL)
        put("device", Build.DEVICE); put("hardware", Build.HARDWARE); put("board", Build.BOARD)
        put("socManufacturer", (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "")
            .takeUnless { it.isBlank() || it == Build.UNKNOWN } ?: property("ro.soc.manufacturer"))
        put("socModel", (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "")
            .takeUnless { it.isBlank() || it == Build.UNKNOWN } ?: property("ro.soc.model"))
        put("platform", property("ro.board.platform")); put("osVersion", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT); put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("display", Build.DISPLAY); put("fingerprint", Build.FINGERPRINT)
        put("baseband", property("gsm.version.baseband")); put("kernel", System.getProperty("os.version"))
        put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())); put("appVersion", info.versionName)
        put("appVersionCode", info.longVersionCode); put("process", android.app.Application.getProcessName())
        put("apkSha256", runCatching { java.io.File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } }.getOrNull())
        put("freeBytes", StatFs(context.filesDir.path).availableBytes)
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        put("availableMemory", memory.availMem); put("lowMemory", memory.lowMemory)
        put("network", runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        }.getOrDefault("unknown"))
    }

    /** Reuse IMEI1/SIM0. Missing identity never prevents an error report. No EFS connection is opened. */
    suspend fun imei(context: Context): Pair<String?, String> {
        val diagnostics = context.getSharedPreferences("diagnostic-reporting", Context.MODE_PRIVATE)
        val own = diagnostics.getString("identity_imei1", null)
        if (diagnostics.getString("identity_model", null) == Build.MODEL && validImei(own)) return own to "diagnostic_cache"
        val cached = context.getSharedPreferences("efs-vault", Context.MODE_PRIVATE).getString("imei", null)
        if (validImei(cached)) return cached to "efs_cache"
        val existing = runCatching { DiagnosticEvents.imeiReader?.invoke() }.getOrNull()
        if (validImei(existing)) return existing to "existing_efs_identity"
        val phone = runCatching { RootPhone.read(context, "identity") }.getOrNull()
        val imei = phone?.optString("imei")
        if (validImei(imei)) {
            diagnostics.edit().putString("identity_imei1", imei).putString("identity_model", Build.MODEL).commit()
            return imei to "root_phone_service"
        }
        return null to "unavailable"
    }

    private fun validImei(value: String?) = value != null && value.matches(Regex("[0-9]{15}")) && value.any { it != '0' }

    private fun property(name: String) = runCatching { command(listOf("getprop", name), 2).trim().take(512) }.getOrDefault("")

    /** Fixed read-only metadata commands; never reads block contents, changes modem state or writes EFS. */
    fun storageTopology(): String = runCatching {
        command(listOf("su", "-c", """
            echo 'by-name:'
            ls -l /dev/block/by-name
            echo 'remote-storage-open-devices:'
            for pid in ${'$'}(pidof rmt_storage rmtfs); do
              for fd in /proc/${'$'}pid/fd/*; do
                [ -b "${'$'}fd" ] || continue
                readlink -f "${'$'}fd"
              done
            done
        """.trimIndent()), 8)
    }.getOrDefault("storage_metadata_unavailable")

    private fun command(args: List<String>, timeout: Long): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val bytes = java.io.ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(2048)
                    while (bytes.size() < 32768) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 32768 - bytes.size()))
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.join(1000)
            return bytes.toString("UTF-8")
        } finally { process.destroyForcibly() }
    }
}
