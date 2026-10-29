package com.nvmex.networkhelper.util.qmi

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import java.io.IOException

data class QmiMonitoringSnapshot(val radio: QmiRadioSnapshot, val rx: List<QmiRxGroup>)

internal fun qmiMonitoringReads(includeRx: Boolean): List<String> =
    QmiRadioDecoder.reads + if (includeRx) QmiRxDecoder.reads else emptyList()

internal fun qmiDecodeMonitoring(slot: Int, now: Long, reads: JSONArray, includeRx: Boolean): QmiMonitoringSnapshot {
    require(reads.length() == qmiMonitoringReads(includeRx).size) { "Incomplete monitoring response" }
    if ((0 until reads.length()).all { reads.getJSONObject(it).optString("status") in setOf("timeout", "send_failed") })
        throw IOException("Monitoring socket timed out or failed")
    val replies = QmiRadioDecoder.reads.mapIndexedNotNull { index, msg ->
        reads.optJSONObject(index)?.takeIf { it.optString("status") == "ok" }?.let { msg.toInt(16) to it.getString("rx") }
    }.toMap()
    val rx = if (!includeRx) emptyList() else QmiRxDecoder.rats.mapIndexed { index, rat ->
        val raw = reads.getJSONObject(QmiRadioDecoder.reads.size + index)
        if (raw.optString("status") != "ok") QmiRxGroup(rat, emptyList(), raw.optString("status"))
        else try { QmiRxGroup(rat, QmiRxDecoder.decode(raw.getString("rx"), rat)) }
        catch (e: IllegalArgumentException) { QmiRxGroup(rat, emptyList(), e.message) }
    }
    return QmiMonitoringSnapshot(QmiRadioDecoder.decode(slot, now, replies), rx)
}

internal fun qmiMonitoringCanRetry(error: Exception): Boolean = when (error) {
    is CancellationException, is SecurityException -> false
    is QmiCommandException -> (error.command == "nas_read" &&
        (error.modemCode in setOf(5, 7) || (error.stage == "daemon" && error.message.orEmpty().contains("Monitoring:")))) ||
        (error.command == "uim_card_status" && error.modemCode in setOf(5, 7))
    is IOException -> error !is QmiReadException && listOf("socket", "disconnected", "timed out", "timeout", "broken pipe", "connection reset")
        .any { error.message.orEmpty().contains(it, ignoreCase = true) }
    else -> false
}

/** Read-only recovery. Close the owned session and retry twice; never reset locks or other apps. */
internal suspend fun <T> qmiRecoverMonitoring(read: suspend () -> T, reopen: suspend () -> Unit,
                                             pause: suspend (Long) -> Unit): T {
    val backoff = longArrayOf(400, 1200)
    var attempt = 0
    while (true) {
        try { return read() }
        catch (e: Exception) {
            if (!qmiMonitoringCanRetry(e) || attempt >= backoff.size) throw e
            reopen()
            pause(backoff[attempt++])
        }
    }
}
