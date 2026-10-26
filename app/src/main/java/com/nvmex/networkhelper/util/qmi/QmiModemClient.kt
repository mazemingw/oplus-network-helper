package com.nvmex.networkhelper.util.qmi

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.nvmex.networkhelper.util.shell.SuShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** One owner, one authenticated root helper. All multi-command SIM sequences are serialized. */
class QmiModemClient(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val socketName = "nh_qmi_${Process.myUid()}_$id"
    private val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val tokenFile = File(app.filesDir, "qmi/token-$id")
    private val pidFile = File(app.filesDir, "qmi/pid-$id")
    private val stderrFile = File(app.cacheDir, "qmi-$id.log")
    private var socket: LocalSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var sequence = 0
    private var disposed = false
    private var bindings: QmiSimBindings? = null
    private var bindingsAt = 0L
    private var socketRuleTried = false
    private val nrModeReadOnly = mutableSetOf<Int>()

    suspend fun readHardware(): Map<QmiBand, Set<Int>> = transaction {
        val response = request(qmiCommand("query_hardware"))
        val hardware = QmiDeviceState.hardwareOnly(response.getJSONObject("state"), 1)?.hardware
            ?: throw IOException("Hardware capabilities unavailable")
        QmiHardwareCapabilities.record(hardware)
        hardware
    }

    suspend fun read(sim: Int): QmiDeviceState = transaction {
        // Hardware capabilities are device-wide and remain useful even if SIM binding fails.
        val capabilities = request(qmiCommand("query_hardware"), allowRejected = true)
        QmiHardwareCapabilities.record(QmiDeviceState.hardwareOnly(capabilities.getJSONObject("state"), sim)?.hardware)
        try { select(sim) } catch (e: QmiCommandException) {
            throw QmiReadException(e, QmiDeviceState.hardwareOnly(capabilities.getJSONObject("state"), sim))
        }
        parseState(request(qmiCommand("query")), sim)
    }

    suspend fun execute(sim: Int, commands: List<JSONObject>): QmiDeviceState = transaction {
        select(sim)
        // Never retry a write after a timeout: it may already have reached the modem.
        commands.forEach { request(it) }
        request(qmiCommand("query_lte_cell_lock"), allowRejected = true)
        request(qmiCommand("query_nr_cell_lock"), allowRejected = true)
        parseState(request(qmiCommand("query")), sim)
    }

    suspend fun executeBands(sim: Int, commands: List<JSONObject>): QmiBandExecution = transaction {
        select(sim)
        val rejected = qmiExecuteBandCommands(commands, sim !in nrModeReadOnly,
            onModeRejected = { nrModeReadOnly += sim }) { request(it) }
        request(qmiCommand("query_lte_cell_lock"), allowRejected = true)
        request(qmiCommand("query_nr_cell_lock"), allowRejected = true)
        QmiBandExecution(parseState(request(qmiCommand("query")), sim), rejected)
    }

    private fun parseState(response: JSONObject, sim: Int) = QmiDeviceState.parse(response.getJSONObject("state"))
        .copy(sim = sim, nrModeSettable = sim !in nrModeReadOnly)

    suspend fun resetSession(sim: Int, onConflictingAppStopped: () -> Unit = {}): QmiDeviceState = transaction {
        qmiResetWithRecovery(reset = {
            bindings = null
            val subscription = subscriptionFor(sim, fresh = true)
            val response = request(qmiCommand("session_reset").put("sim", subscription))
            QmiDeviceState.parse(response.getJSONObject("state")).also {
                check(it.sim == subscription) { "SIM $sim unavailable" }
            }.copy(sim = sim, nrModeSettable = sim !in nrModeReadOnly)
        }, recover = {
            // Release this owner's clients first; never terminate other Network Helper owners.
            disconnect()
            stopOwnedProcess()
            if (QmiSessionRecovery(app).releaseBandMenu()) onConflictingAppStopped()
            start()
        })
    }

    suspend fun rx(sim: Int): List<QmiRxGroup> = transaction {
        require(sim in 1..2)
        val subscription = subscriptionFor(sim)
        val response = request(qmiCommand("nas_read").put("sim", subscription).put("reads", JSONArray(QmiRxDecoder.reads)))
            .getJSONObject("nas_read")
        require(response.getInt("sim") == subscription) { "SIM mismatch" }
        val reads = response.getJSONArray("reads")
        QmiRxDecoder.rats.mapIndexed { index, rat ->
            val raw = reads.optJSONObject(index)
            if (raw?.optString("status") != "ok") QmiRxGroup(rat, emptyList(), raw?.optString("status") ?: "missing")
            else try {
                QmiRxGroup(rat, QmiRxDecoder.decode(raw.getString("rx"), rat))
            } catch (e: IllegalArgumentException) {
                QmiRxGroup(rat, emptyList(), e.message)
            }
        }
    }

    suspend fun radio(sim: Int): QmiRadioSnapshot = transaction {
        val subscription = subscriptionFor(sim)
        val result = request(qmiCommand("nas_read").put("sim", subscription)
            .put("reads", JSONArray(QmiRadioDecoder.reads))).getJSONObject("nas_read")
        check(result.getInt("sim") == subscription) { "SIM mismatch" }
        val reads = result.getJSONArray("reads")
        val replies = QmiRadioDecoder.reads.mapIndexedNotNull { index, msg ->
            reads.optJSONObject(index)?.takeIf { it.optString("status") == "ok" }
                ?.let { msg.toInt(16) to it.getString("rx") }
        }.toMap()
        QmiRadioDecoder.decode(sim - 1, System.currentTimeMillis(), replies)
    }

    private fun select(sim: Int) {
        require(sim in 1..2)
        val subscription = subscriptionFor(sim, fresh = true)
        val state = request(qmiCommand("sim_set").put("sim", subscription)).getJSONObject("state")
        check(state.optInt("sim") == subscription && state.optBoolean("valid")) { "SIM $sim unavailable" }
    }

    private fun subscriptionFor(physicalSim: Int, fresh: Boolean = false): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        if (fresh || bindings == null || now - bindingsAt > 5000) {
            val raw = request(qmiCommand("uim_card_status")).getJSONObject("uim_card_status")
            check(raw.optString("status") == "ok") { "Cannot read physical SIM mapping" }
            bindings = QmiSimBindings.decode(raw.getString("rx"))
            bindingsAt = now
        }
        return bindings!!.subscription(physicalSim)
    }

    private suspend fun <T> transaction(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!disposed) { "QMI session closed" }
            try {
                if (socket == null) start()
                block()
            } catch (e: IOException) {
                if (e !is QmiCommandException && e !is QmiReadException) disconnect()
                throw e
            }
        }
    }

    private fun start() {
        check(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "ARM64 required" }
        if (pidFile.isFile && connect()) return
        stopOwnedProcess()
        val dir = File(app.filesDir, "qmi").apply { mkdirs() }
        val expected = app.assets.open("qmi/nh-qmi.sha256").bufferedReader().use { it.readText().trim() }
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid helper digest" }
        // Immutable versioned paths allow the lock page and RX panel to coexist.
        val binary = File(dir, "nh-qmi-${expected.take(16)}")
        if (!binary.exists() || sha256(binary) != expected) {
            val temp = File(dir, "install-$id")
            try {
                app.assets.open("qmi/nh-qmi").use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                check(sha256(temp) == expected) { "QMI helper checksum mismatch" }
                temp.setReadable(false, false); temp.setReadable(true, true)
                check(temp.setExecutable(true, true)) { "Cannot prepare QMI helper" }
                if (!temp.renameTo(binary)) check(binary.exists() && sha256(binary) == expected)
            } finally { temp.delete() }
        }
        tokenFile.writeText(token)
        tokenFile.setReadable(false, false); tokenFile.setReadable(true, true)
        tokenFile.setWritable(false, false); tokenFile.setWritable(true, true)
        val launch = "setsid ${quote(binary.absolutePath)} -uid ${Process.myUid()} -name ${quote(socketName)} " +
            "-tokenfile ${quote(tokenFile.absolutePath)} </dev/null >/dev/null 2>${quote(stderrFile.absolutePath)} & echo \$! >${quote(pidFile.absolutePath)}"
        val result = SuShellRunner().execSu(launch, 30_000)
        if (result.code != 0) throw IOException(result.err.ifBlank { "Root access required" })
        var lastError: Exception? = null
        repeat(120) {
            try {
                if (connect()) return
            } catch (e: Exception) {
                lastError = e
                // A connected peer failing authentication must never be trusted/retried.
                if (e is SecurityException) throw e
            }
            Thread.sleep(100)
        }
        val detail = stderrFile.takeIf { it.isFile }?.readText()?.takeLast(1200).orEmpty()
        throw IOException(detail.ifBlank { lastError?.message ?: "Cannot connect to QMI helper" })
    }

    private fun connect(): Boolean {
        val candidate = LocalSocket()
        try {
            candidate.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            candidate.close()
            if (!socketRuleTried && e.message?.contains("Permission denied", ignoreCase = true) == true) {
                socketRuleTried = true
                allowRootSocket()
            }
            return false
        }
        try {
            candidate.soTimeout = 30_000
            if (candidate.peerCredentials.uid != 0) throw SecurityException("QMI helper is not root")
            socket = candidate
            reader = candidate.inputStream.bufferedReader()
            writer = candidate.outputStream.bufferedWriter()
            request(qmiCommand("version"))
            return true
        } catch (e: Exception) { candidate.close(); disconnect(); throw e }
    }

    private fun request(command: JSONObject, allowRejected: Boolean = false): JSONObject {
        val requestId = ++sequence
        command.put("id", requestId).put("token", token)
        val w = writer ?: throw IOException("QMI disconnected")
        w.write(command.toString()); w.newLine(); w.flush()
        val r = reader ?: throw IOException("QMI disconnected")
        val line = StringBuilder()
        while (true) {
            val c = r.read()
            if (c < 0) throw IOException("QMI helper disconnected; refresh before retrying")
            if (c == '\n'.code) break
            if (line.length >= 65536) throw IOException("QMI response too large")
            line.append(c.toChar())
        }
        val response = try { JSONObject(line.toString()) } catch (e: Exception) { throw IOException("Malformed QMI response", e) }
        if (response.optString("auth") != token) { disconnect(); throw SecurityException("QMI authentication failed") }
        if (response.optInt("id", -1) != requestId || response.optString("cmd") != command.getString("cmd") || response.optString("version") != "5.0.0-nh3") {
            throw IOException("QMI protocol mismatch")
        }
        if (!allowRejected && !response.optBoolean("ok")) {
            val error = response.optJSONObject("error")
            throw QmiCommandException(command.getString("cmd"), error?.optString("message") ?: "Modem rejected command",
                error?.optInt("code", -1) ?: -1, error?.optString("stage").orEmpty())
        }
        return response
    }

    /** Close from a background thread/coroutine; never blocks the main thread. */
    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (disposed) return@withLock
            disposed = true
            try { if (socket != null) request(qmiCommand("shutdown")) } catch (_: Exception) { }
            disconnect()
            // Only kill our unique command line if startup failed or the socket was lost.
            stopOwnedProcess()
            tokenFile.delete(); pidFile.delete(); stderrFile.delete()
        }
    }

    private fun disconnect() {
        runCatching { socket?.close() }
        socket = null; reader = null; writer = null
    }

    private fun stopOwnedProcess() {
        val pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (pid != null && pid > 1) {
            SuShellRunner().execSu("if [ -r /proc/$pid/cmdline ] && dd if=/proc/$pid/cmdline bs=4096 count=1 2>/dev/null | tr '\\000' ' ' | grep -F -- ${quote(socketName)} >/dev/null; then kill $pid; fi", 3000)
        }
    }

    /** A narrow connectto rule only; never switch SELinux to permissive mode. */
    private fun allowRootSocket() {
        val appDomain = runCatching { File("/proc/self/attr/current").readText().split(':')[2] }.getOrNull() ?: return
        val rootDomain = SuShellRunner().execSu("cat /proc/self/attr/current", 3000).out.trim().split(':').getOrNull(2) ?: return
        if (!appDomain.matches(Regex("untrusted_app[0-9_]*")) || !rootDomain.matches(Regex("[a-zA-Z0-9_]+"))) return
        val rule = quote("allow $appDomain $rootDomain unix_stream_socket connectto")
        SuShellRunner().execSu(
            "(for t in ksud /data/adb/ksud /data/adb/ksu/bin/ksud; do " +
                "command -v \$t >/dev/null 2>&1 && \$t sepolicy patch $rule >/dev/null 2>&1 && exit 0; done; " +
                "for t in magiskpolicy /data/adb/magisk/magiskpolicy; do " +
                "command -v \$t >/dev/null 2>&1 && \$t --live $rule >/dev/null 2>&1 && exit 0; done; exit 1)", 4000)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
}

class QmiCommandException(val command: String, message: String, val modemCode: Int, val stage: String = "") :
    IOException("$command: $message" + (if (stage.isNotBlank()) " [$stage]" else "") + if (modemCode >= 0) " (QMI $modemCode)" else "")

class QmiReadException(cause: QmiCommandException, val hardware: QmiDeviceState?) : IOException(cause.message, cause)
