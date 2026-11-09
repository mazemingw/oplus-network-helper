package dev.ririxi.bunnylock.root

import android.system.Os
import android.telephony.SubscriptionManager
import dev.ririxi.bunnylock.core.toIntList
import dev.ririxi.bunnylock.modem.ImsFeature
import dev.ririxi.bunnylock.modem.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Started via su as:
 *   CLASSPATH=<base.apk> app_process /system/bin dev.ririxi.bunnylock.root.RootServer <nativeLibraryDir>
 *
 * One JSON object per line on stdin and stdout:
 *   request  {"id":1,"cmd":"qc.state","sim":1,...}
 *   response {"id":1,"ok":true,"data":...} | {"id":1,"ok":false,"error":"...","text":{...}}
 */
object RootServer {
    const val VERSION = 1

    private const val DEFAULT_AT_TIMEOUT_MS = 4000

    /** AT+EMMCHLCK mode 3: the lock holds in idle and in connected state (see [Mediatek]). */
    private const val MTK_CELL_LOCK_IDLE_AND_CONNECTED = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val protocolOut = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
        // Keep stray framework prints away from the protocol stream.
        System.setOut(System.err)

        val nativeLibraryDir = args.getOrNull(0)
        protocolOut.println(hello(nativeLibraryDir).put("ready", true))

        val input = System.`in`.bufferedReader()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val command = runCatching { JSONObject(line).optString("cmd") }.getOrNull()
            if (command == "exit") break
            protocolOut.println(respond(line))
        }
        exitProcess(0)
    }

    private var libError: Throwable? = null
    private var libLoaded = false

    private fun loadNative(libDir: String?) {
        if (libLoaded || libDir.isNullOrEmpty()) return
        libError = runCatching { System.load("$libDir/libbunnyqrtr.so") }.exceptionOrNull()
        libLoaded = libError === null
    }

    private fun hello(libDir: String?): JSONObject {
        loadNative(libDir)
        return JSONObject()
            .put("version", VERSION)
            .put("uid", Os.getuid())
            .put("qrtrLib", libLoaded)
            .put("libError", libError?.message ?: JSONObject.NULL)
    }

    /** Handles one request line and returns the response line. Shared by the su helper and the Shizuku service. */
    @Synchronized
    fun respond(line: String): String {
        val response = JSONObject()
        try {
            val request = JSONObject(line)
            response.put("id", request.optInt("id"))
            val data = if (request.optString("cmd") == "hello") hello(request.optString("libDir")) else handle(request)
            response.put("data", data ?: JSONObject.NULL).put("ok", true)
        } catch (error: Throwable) {
            response.put("ok", false).put("error", error.message ?: error.javaClass.simpleName)
            if (error is HelperFailure) response.put("text", error.toTextJson())
        }
        return response.toString()
    }

    private fun JSONObject.isPresent(key: String): Boolean = has(key) && !isNull(key)

    private fun JSONObject.ints(key: String): List<Int> = optJSONArray(key)?.toIntList() ?: emptyList()

    private fun JSONObject.intsOrNull(key: String): List<Int>? = if (isPresent(key)) ints(key) else null

    private fun JSONObject.intOrNull(key: String): Int? = if (isPresent(key)) getInt(key) else null

    private fun JSONObject.dataSubId(): Int = optInt("dataSubId", SubscriptionManager.INVALID_SUBSCRIPTION_ID)

    private fun handle(request: JSONObject): Any? {
        val command = request.getString("cmd")
        if (command == "host.disconnectWifi") return JSONObject().put("accepted", Aosp.disconnectWifi())
        val sim = request.optInt("sim", 1)
        val simSlot = sim - 1
        val requestedSubId = request.optInt("subId", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val subId = Aosp.resolveSubId(requestedSubId, simSlot)
        val persist = request.optBoolean("persist", true)
        return when (command) {
            "ping" -> JSONObject().put("uid", Os.getuid()).put("version", VERSION)

            "detect" -> Sys.detect()
            "host.cleanupQmiOwners" -> BackendCleanup.run(request)

            "aosp.state" -> Aosp.state(subId)

            "aosp.carriers" -> PhysicalChannels.report(simSlot)

            "aosp.setAllowed" -> Aosp.setAllowed(subId, request.getLong("mask"), request.optInt("slot", simSlot))

            "aosp.reapplyAllowed" -> Aosp.reapplyAllowed(subId, request.getLong("mask"))

            "aosp.setNrDc" -> Aosp.setNrDc(subId, request.getInt("state"))

            "aosp.ims" -> {
                if (subId < 0) fail(R.string.server_unknown_subscription)
                Aosp.ims(subId)
            }

            "aosp.setIms" -> {
                if (subId < 0) fail(R.string.server_unknown_subscription)
                val feature = ImsFeature.entries.first { it.key == request.getString("feature") }
                Aosp.setIms(subId, feature, request.getBoolean("enabled"))
            }

            "aosp.setChannels" -> Aosp.setChannels(subId, request.optJSONArray("specs") ?: JSONArray(), request.optBoolean("split", true))

            "aosp.setPlmn" -> Aosp.setPlmn(subId, request.getString("plmn"), persist)

            "aosp.setPlmnAuto" -> Aosp.setPlmnAutomatic(subId)

            "aosp.rebootModem" -> {
                val slot = request.optInt("slot", simSlot)
                Aosp.rebootModem(slot, Aosp.resolveSubId(requestedSubId, slot))
            }

            "radio.cycle" -> Aosp.radioCycle()

            "radio.cycleSim" -> {
                if (subId < 0) fail(R.string.server_unknown_subscription)
                Aosp.cycleSubscriberRadio(subId)
            }

            "qc.state" -> Qualcomm.state(sim)

            "qc.serving" -> Qualcomm.serving(sim)

            "qc.setBands" -> Qualcomm.setBands(sim, request.getString("kind"), request.ints("bands"), persist)

            "qc.reset" -> Qualcomm.reset(sim, persist)

            "qc.setRat" -> Qualcomm.setRat(sim, request.getInt("mask"), persist)

            "qc.setNrMode" -> Qualcomm.setNrMode(sim, request.getInt("mode"), persist)

            "qc.setPlmn" -> Qualcomm.setPlmn(sim, request.getInt("mcc"), request.getInt("mnc"), request.optBoolean("threeDigit"), persist)

            "qc.clearPlmn" -> Qualcomm.clearPlmn(sim, persist)

            "qc.lteLock" -> Qualcomm.lteLock(sim, request.getLong("earfcn"), request.ints("pcis"))

            "qc.lteUnlock" -> Qualcomm.lteUnlock(sim)

            "qc.nrLock" -> Qualcomm.nrLock(sim, request)

            "qc.nrUnlock" -> Qualcomm.nrUnlock(sim)

            "qc.reboot" -> Qualcomm.reboot()

            // A failed QMI detach/attach falls back to power-cycling the radio through ITelephony, but only when the subscription is known.
            "qc.reattach" ->
                try {
                    Qualcomm.reattach(sim)
                } catch (e: Exception) {
                    if (subId < 0) throw e
                    Aosp.cycleSubscriberRadio(subId).put("qmiError", e.toTextJson())
                }

            "qc.raw" -> Qualcomm.raw(sim, request.getInt("service"), request.getInt("msg"), request.optString("payload").unhex())

            "mtk.findNode" -> JSONObject().put("node", Mediatek.findNode(sim) ?: JSONObject.NULL)

            "mtk.state" -> Mediatek.state(request.getString("node"))

            "mtk.setBands" ->
                Mediatek.setBands(
                    request.getString("node"),
                    request.intsOrNull("gsm"),
                    request.intsOrNull("wcdma"),
                    request.intsOrNull("lte"),
                    request.intsOrNull("nr"),
                )

            "mtk.cellLock" ->
                Mediatek.cellLock(
                    request.getString("node"),
                    request.getInt("act"),
                    request.getLong("arfcn"),
                    request.intOrNull("pci"),
                    request.optInt("mode", MTK_CELL_LOCK_IDLE_AND_CONNECTED),
                )

            "mtk.cellUnlock" -> Mediatek.cellUnlock(request.getString("node"))

            "mtk.setRat" -> Mediatek.setRat(request.getString("node"), request.getLong("mask"))

            "mtk.setCa" -> Mediatek.setCarrierAggregation(request.getString("node"), request.getBoolean("enabled"))

            "pixel.state" -> Pixel.state(sim)

            "pixel.setLte" -> Pixel.setLte(request.ints("bands"))

            "pixel.setNr" -> Pixel.setNr(request.ints("bands"))

            "pixel.setNrMode" -> Pixel.setNrMode(sim, request.getString("value"))

            "samsung.menu" -> Sys.openSamsungMenu()

            "samsung.nrMode" -> Samsung.nrMode(simSlot, subId, request.dataSubId())

            "samsung.setNrMode" -> Samsung.setNrMode(simSlot, subId, request.dataSubId(), request.getInt("mode"))

            "at" -> {
                val timeoutMs = request.optInt("timeout", DEFAULT_AT_TIMEOUT_MS)
                JSONObject().put("reply", At.send(request.getString("node"), request.getString("command"), timeoutMs))
            }

            else -> fail(R.string.server_unknown_command, command)
        }
    }
}
