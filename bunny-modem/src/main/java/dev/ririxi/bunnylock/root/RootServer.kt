package dev.ririxi.bunnylock.root

import android.system.Os
import dev.ririxi.bunnylock.backend.mediatek.mediatekCommands
import dev.ririxi.bunnylock.backend.pixel.pixelCommands
import dev.ririxi.bunnylock.backend.qualcomm.qualcommCommands
import dev.ririxi.bunnylock.backend.samsung.samsungCommands
import dev.ririxi.bunnylock.backend.unisoc.unisocCommands
import dev.ririxi.bunnylock.modem.R
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

    // Shared by the su helper and the Shizuku service.
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
            if (error is Failure) response.put("text", error.toTextJson())
        }
        return response.toString()
    }

    private val commands: Commands by lazy {
        mapOf("detect" to { _: Request -> detectDevice() }) + androidCommands + qualcommCommands + mediatekCommands + pixelCommands + samsungCommands + unisocCommands
    }

    private fun handle(json: JSONObject): Any? {
        val command = json.getString("cmd")
        when (command) {
            "host.disconnectWifi" -> return JSONObject().put("accepted", Aosp.disconnectWifi())
            "host.cleanupQmiOwners" -> return BackendCleanup.run(json)
        }
        val handler = commands[command] ?: fail(R.string.server_unknown_command, command)
        return handler(Request(json))
    }
}
