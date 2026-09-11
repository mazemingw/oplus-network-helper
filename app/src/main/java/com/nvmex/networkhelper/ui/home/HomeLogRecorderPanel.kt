package com.nvmex.networkhelper.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.GlobalQosEvent
import com.nvmex.networkhelper.model.network.NrCaCarrier
import com.nvmex.networkhelper.model.network.NrCaInfo
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.model.network.QosData
import com.nvmex.networkhelper.ui.base.toast
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HomePlaybackBarState(
    val progress: Float,
    val label: String,
    val isPlaying: Boolean,
    val onReplay: () -> Unit,
    val onSeekToProgress: (Float) -> Unit,
    val onSeekBySeconds: (Int) -> Unit,
    val onTogglePause: () -> Unit,
    val onExit: () -> Unit
)

data class HomeLogSample(
    val ts: Long,
    val panelBySubId: Map<Int, NetworkPanelUiState>,
    val qosBySubId: Map<Int, QosData>,
    val globalEvent: GlobalQosEvent?,
    val playbackIndex: Int = -1,
    val playbackSession: Int = 0
) {
    val panel: NetworkPanelUiState
        get() = panelBySubId.values.sortedBy { it.subId }.firstOrNull() ?: NetworkPanelUiState()
}

@Composable
fun HomeLogRecorderPanel(
    panelState: NetworkPanelUiState,
    panelStates: Map<Int, NetworkPanelUiState>,
    qosBySubId: Map<Int, QosData>,
    globalEvent: GlobalQosEvent?,
    onPlaybackSampleChange: (HomeLogSample?) -> Unit,
    onPlaybackBarChange: (HomePlaybackBarState?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val samples = remember { mutableStateListOf<HomeLogSample>() }
    var recording by remember { mutableStateOf(false) }
    var playbackActive by remember { mutableStateOf(false) }
    var playbackRunning by remember { mutableStateOf(false) }
    var playbackIndex by remember { mutableIntStateOf(0) }
    var playbackSession by remember { mutableIntStateOf(0) }
    var askCsvExport by remember { mutableStateOf(false) }
    val latestPanel by rememberUpdatedState(panelState)
    val latestPanelStates by rememberUpdatedState(panelStates)
    val latestQos by rememberUpdatedState(qosBySubId)
    val latestGlobalEvent by rememberUpdatedState(globalEvent)

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val imported = readRecordingFromUri(context, uri)
        if (imported.isEmpty()) {
            toast(context, context.getString(R.string.home_log_import_empty_or_failed))
        } else {
            samples.clear()
            samples += imported
            playbackSession += 1
            playbackIndex = 0
            playbackActive = true
            playbackRunning = true
        }
    }

    fun stopPlayback() {
        playbackActive = false
        playbackRunning = false
        playbackIndex = 0
        onPlaybackSampleChange(null)
        onPlaybackBarChange(null)
    }

    fun startPlayback(items: List<HomeLogSample>) {
        if (items.isEmpty()) {
            toast(context, context.getString(R.string.home_log_no_playback_record))
            return
        }
        samples.clear()
        samples += items
        playbackSession += 1
        playbackIndex = 0
        playbackActive = true
        playbackRunning = true
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (recording) {
            samples += HomeLogSample(
                ts = System.currentTimeMillis(),
                panelBySubId = latestPanelStates.ifEmpty {
                    latestPanel.subId.takeIf { it >= 0 }?.let { mapOf(it to latestPanel) } ?: emptyMap()
                }.toMap(),
                qosBySubId = latestQos.toMap(),
                globalEvent = latestGlobalEvent
            )
            delay(1000L)
        }
    }

    LaunchedEffect(playbackActive, playbackRunning, playbackIndex, playbackSession, samples.size) {
        if (!playbackActive) return@LaunchedEffect
        onPlaybackSampleChange(
            samples.getOrNull(playbackIndex)?.copy(
                playbackIndex = playbackIndex,
                playbackSession = playbackSession
            )
        )
        if (!playbackRunning) return@LaunchedEffect
        delay(1000L)
        if (playbackIndex < samples.lastIndex) {
            playbackIndex += 1
        } else {
            playbackRunning = false
        }
    }

    LaunchedEffect(playbackActive, playbackRunning, playbackIndex, playbackSession, samples.size) {
        if (!playbackActive || samples.isEmpty()) {
            onPlaybackBarChange(null)
            return@LaunchedEffect
        }
        onPlaybackBarChange(
            HomePlaybackBarState(
                progress = if (samples.size <= 1) {
                    1f
                } else {
                    playbackIndex.toFloat() / samples.lastIndex.toFloat()
                }.coerceIn(0f, 1f),
                label = "${playbackIndex + 1}/${samples.size}  ${formatLogTime(samples[playbackIndex].ts)}",
                isPlaying = playbackRunning,
                onReplay = {
                    playbackSession += 1
                    playbackIndex = 0
                    playbackRunning = true
                },
                onSeekToProgress = { progress ->
                    val target = (progress.coerceIn(0f, 1f) * (samples.size - 1)).toInt()
                    playbackIndex = target.coerceIn(0, samples.lastIndex)
                },
                onSeekBySeconds = { delta ->
                    playbackIndex = (playbackIndex + delta).coerceIn(0, samples.lastIndex)
                },
                onTogglePause = { playbackRunning = !playbackRunning },
                onExit = { stopPlayback() }
            )
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            onPlaybackSampleChange(null)
            onPlaybackBarChange(null)
        }
    }

    val playback = samples.getOrNull(playbackIndex)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(stringResource(R.string.home_log_title), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.home_log_sample_summary, samples.size, qosBySubId.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    when {
                        recording -> stringResource(R.string.home_log_state_recording)
                        playbackActive -> stringResource(R.string.home_log_state_playback)
                        else -> stringResource(R.string.home_log_state_ready)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    enabled = !playbackActive,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    onClick = {
                        if (!recording) {
                            samples.clear()
                            playbackIndex = 0
                            recording = true
                        } else {
                            recording = false
                            saveRecording(context, samples)
                            askCsvExport = true
                        }
                    }
                ) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                    Text(
                        if (recording) stringResource(R.string.action_stop) else stringResource(R.string.home_log_action_record),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    enabled = !recording && !playbackActive,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    onClick = { startPlayback(loadLastRecording(context)) }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(
                        stringResource(R.string.home_log_action_replay_last),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    enabled = !recording && !playbackActive,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    onClick = { importLauncher.launch("*/*") }
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Text(
                        stringResource(R.string.action_import),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            playback?.let {
                val panels = it.panelBySubId.values.sortedBy { panel -> panel.subId }
                val text = panels.joinToString("  ") { p ->
                    "Sub${panels.indexOf(p) + 1} ${p.cellType} TAC=${p.tac} PCI=${p.pci} CI=${p.ci}"
                }
                Text(
                    stringResource(R.string.home_log_current_sample, text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (askCsvExport) {
        AlertDialog(
            onDismissRequest = { askCsvExport = false },
            title = { Text(stringResource(R.string.home_log_save_csv_title)) },
            text = { Text(stringResource(R.string.home_log_save_csv_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askCsvExport = false
                        val csv = exportHomeLogCsv(context, samples)
                        shareFile(context, csv, "text/csv")
                    }
                ) {
                    Text(stringResource(R.string.home_log_action_save_csv))
                }
            },
            dismissButton = {
                TextButton(onClick = { askCsvExport = false }) {
                    Text(stringResource(R.string.home_log_action_skip_csv))
                }
            }
        )
    }
}

private fun recordingFile(context: Context): File {
    return File(context.filesDir, "home_log_recording.json")
}

private fun saveRecording(context: Context, samples: List<HomeLogSample>): File {
    val file = recordingFile(context)
    file.writeText(samplesToJson(samples).toString(), Charsets.UTF_8)
    toast(context, context.getString(R.string.home_log_record_saved))
    return file
}

private fun loadLastRecording(context: Context): List<HomeLogSample> {
    val file = recordingFile(context)
    if (!file.exists()) return emptyList()
    return runCatching { samplesFromJson(JSONArray(file.readText(Charsets.UTF_8))) }.getOrDefault(emptyList())
}

private fun readRecordingFromUri(context: Context, uri: Uri): List<HomeLogSample> {
    return runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return@runCatching emptyList()
        samplesFromJson(JSONArray(text))
    }.getOrDefault(emptyList())
}

private fun samplesToJson(samples: List<HomeLogSample>): JSONArray {
    return JSONArray().apply {
        samples.forEach { sample ->
            put(JSONObject().apply {
                put("ts", sample.ts)
                put("panels", JSONObject().apply {
                    sample.panelBySubId.forEach { (subId, panel) -> put(subId.toString(), panelToJson(panel)) }
                })
                put("qos", JSONObject().apply {
                    sample.qosBySubId.forEach { (subId, qos) -> put(subId.toString(), JSONObject(qos.toJson())) }
                })
                sample.globalEvent?.let { put("globalEvent", JSONObject(it.toJson())) }
            })
        }
    }
}

private fun samplesFromJson(array: JSONArray): List<HomeLogSample> {
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val panelsObj = o.optJSONObject("panels")
            val panels = if (panelsObj != null) {
                buildMap {
                    panelsObj.keys().forEach { key ->
                        val subId = key.toIntOrNull() ?: return@forEach
                        put(subId, panelFromJson(panelsObj.optJSONObject(key) ?: JSONObject()))
                    }
                }
            } else {
                val panel = panelFromJson(o.optJSONObject("panel") ?: JSONObject())
                panel.subId.takeIf { it >= 0 }?.let { mapOf(it to panel) } ?: emptyMap()
            }
            val qosObj = o.optJSONObject("qos") ?: JSONObject()
            val qos = buildMap {
                qosObj.keys().forEach { key ->
                    val subId = key.toIntOrNull() ?: return@forEach
                    put(subId, QosData.fromJson(qosObj.optJSONObject(key)?.toString() ?: return@forEach))
                }
            }
            val global = o.optJSONObject("globalEvent")?.let { GlobalQosEvent.fromJson(it.toString()) }
            add(HomeLogSample(o.optLong("ts", System.currentTimeMillis()), panels, qos, global))
        }
    }
}

private fun panelToJson(p: NetworkPanelUiState): JSONObject {
    return JSONObject().apply {
        put("operatorName", p.operatorName)
        put("mcc", p.mcc)
        put("mnc", p.mnc)
        put("dataNetworkType", p.dataNetworkType)
        put("nrMode", p.nrMode)
        put("cellType", p.cellType)
        put("duplex", p.duplex)
        put("tac", p.tac)
        put("pci", p.pci)
        put("ci", p.ci)
        put("arfcn", p.arfcn)
        put("band", p.band)
        put("freqDl", p.freqDl)
        put("freqUl", p.freqUl)
        put("rssi", p.rssi)
        put("rsrp", p.rsrp)
        put("rsrq", p.rsrq)
        put("sinr", p.sinr)
        put("ssRsrp", p.ssRsrp)
        put("ssRsrq", p.ssRsrq)
        put("ssSinr", p.ssSinr)
        put("linkDownstreamKbps", p.linkDownstreamKbps ?: JSONObject.NULL)
        put("linkUpstreamKbps", p.linkUpstreamKbps ?: JSONObject.NULL)
        p.nrCaInfo?.let { put("nrCaInfo", nrCaInfoToJson(it)) }
        put("updatedAt", p.updatedAt)
        put("subId", p.subId)
    }
}

private fun panelFromJson(o: JSONObject): NetworkPanelUiState {
    return NetworkPanelUiState(
        operatorName = o.optString("operatorName", "-"),
        mcc = o.optString("mcc", "-"),
        mnc = o.optString("mnc", "-"),
        dataNetworkType = o.optString("dataNetworkType", "-"),
        nrMode = o.optString("nrMode", "-"),
        cellType = o.optString("cellType", "-"),
        duplex = o.optString("duplex", "-"),
        tac = o.optString("tac", "-"),
        pci = o.optString("pci", "-"),
        ci = o.optString("ci", "-"),
        arfcn = o.optString("arfcn", "-"),
        band = o.optString("band", "-"),
        freqDl = o.optString("freqDl", "-"),
        freqUl = o.optString("freqUl", "-"),
        rssi = o.optString("rssi", "-"),
        rsrp = o.optString("rsrp", "-"),
        rsrq = o.optString("rsrq", "-"),
        sinr = o.optString("sinr", "-"),
        ssRsrp = o.optString("ssRsrp", "-"),
        ssRsrq = o.optString("ssRsrq", "-"),
        ssSinr = o.optString("ssSinr", "-"),
        linkDownstreamKbps = o.optNullableInt("linkDownstreamKbps"),
        linkUpstreamKbps = o.optNullableInt("linkUpstreamKbps"),
        nrCaInfo = o.optJSONObject("nrCaInfo")?.let(::nrCaInfoFromJson),
        updatedAt = o.optLong("updatedAt", 0L),
        subId = o.optInt("subId", -1)
    )
}

private fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

private fun nrCaInfoToJson(info: NrCaInfo): JSONObject {
    return JSONObject().apply {
        put("slot", info.slot)
        put("type", info.type)
        put("updatedAt", info.updatedAt)
        put("carriers", JSONArray().apply {
            info.carriers.forEach { c ->
                put(JSONObject().apply {
                    put("ccId", c.ccId)
                    put("sccId", c.sccId)
                    put("pci", c.pci)
                    put("bandRaw", c.bandRaw)
                    put("bandText", c.bandText)
                    put("dlArfcn", c.dlArfcn)
                    put("dlStateRaw", c.dlStateRaw)
                    put("dlStateText", c.dlStateText)
                    put("dlBwRaw", c.dlBwRaw)
                    put("dlBwText", c.dlBwText)
                    put("ulStateRaw", c.ulStateRaw)
                    put("ulStateText", c.ulStateText)
                    put("ulBwRaw", c.ulBwRaw)
                    put("ulBwText", c.ulBwText)
                })
            }
        })
    }
}

private fun nrCaInfoFromJson(o: JSONObject): NrCaInfo {
    val carriersArray = o.optJSONArray("carriers") ?: JSONArray()
    val carriers = buildList {
        for (i in 0 until carriersArray.length()) {
            val c = carriersArray.optJSONObject(i) ?: continue
            add(
                NrCaCarrier(
                    ccId = c.optInt("ccId"),
                    sccId = c.optInt("sccId"),
                    pci = c.optInt("pci"),
                    bandRaw = c.optInt("bandRaw"),
                    bandText = c.optString("bandText", ""),
                    dlArfcn = c.optInt("dlArfcn"),
                    dlStateRaw = c.optInt("dlStateRaw"),
                    dlStateText = c.optString("dlStateText", ""),
                    dlBwRaw = c.optInt("dlBwRaw"),
                    dlBwText = c.optString("dlBwText", ""),
                    ulStateRaw = c.optInt("ulStateRaw"),
                    ulStateText = c.optString("ulStateText", ""),
                    ulBwRaw = c.optInt("ulBwRaw"),
                    ulBwText = c.optString("ulBwText", "")
                )
            )
        }
    }
    return NrCaInfo(
        slot = o.optInt("slot"),
        type = o.optInt("type"),
        carriers = carriers,
        updatedAt = o.optLong("updatedAt")
    )
}

private fun exportHomeLogCsv(context: Context, samples: List<HomeLogSample>): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "home_log_${System.currentTimeMillis()}.csv")
    file.outputStream().writer(Charsets.UTF_8).use { out ->
        out.write("\uFEFF")
        val headers = mutableListOf("timestamp")
        (1..2).forEach { idx ->
            headers += panelCsvHeaders("Sub$idx")
            headers += qosCsvHeaders("Sub$idx")
        }
        headers += globalCsvHeaders()
        out.appendLine(
            headers.joinToString(",") { csvEscape(it) }
        )
        samples.forEach { s ->
            val panels = s.panelBySubId.values.sortedBy { it.subId }.take(2)
            val subIds = panels.map { it.subId }
            val row = mutableListOf(s.ts.toString())
            (0 until 2).forEach { index ->
                val panel = panels.getOrNull(index)
                row += panelCsvValues(panel)
                row += qosCsvValues(subIds.getOrNull(index)?.let { s.qosBySubId[it] })
            }
            row += globalCsvValues(s.globalEvent)
            out.appendLine(
                row.joinToString(",") { csvEscape(it) }
            )
        }
    }
    toast(context, context.getString(R.string.toast_exported_file, file.name))
    return file
}

private fun panelCsvHeaders(prefix: String): List<String> = listOf(
    "subId", "operator", "dataNetwork", "nrMode", "cellType", "duplex",
    "tac", "pci", "ci", "arfcn", "band", "freqDl", "freqUl",
    "linkDownKbps", "linkUpKbps", "linkRate", "nrTotalActiveBw", "nrCa",
    "rssi", "rsrp", "rsrq", "sinr", "ssRsrp", "ssRsrq", "ssSinr"
).map { "${prefix}_$it" }

private fun panelCsvValues(p: NetworkPanelUiState?): List<String> {
    if (p == null) return List(panelCsvHeaders("x").size) { "" }
    return listOf(
        p.subId.toString(),
        p.operatorName,
        p.dataNetworkType,
        p.nrMode,
        p.cellType,
        p.duplex,
        p.tac,
        p.pci,
        p.ci,
        p.arfcn,
        p.band,
        p.freqDl,
        p.freqUl,
        p.linkDownstreamKbps?.toString().orEmpty(),
        p.linkUpstreamKbps?.toString().orEmpty(),
        formatCsvLinkRate(p),
        formatCsvNrBandwidth(p),
        formatCsvNrCa(p),
        p.rssi,
        p.rsrp,
        p.rsrq,
        p.sinr,
        p.ssRsrp,
        p.ssRsrq,
        p.ssSinr
    )
}

private val qosFields = listOf(
    "rat", "endcState", "arfcn", "pci", "band", "dlBw", "rsrp", "rsrq", "snr", "svcStatus",
    "ulTimeStamp", "ulPdcpNumDataPdu", "ulPdcpNumDropPdu", "ulPdcpTput", "ulRlcNumDataPdu",
    "ulRlcRetx", "ulGrant", "ulBsr", "ulBler", "dlTimeStamp", "dlPdcpNumDataPdu",
    "dlPdcpTput", "dlRlcNumDataPdu", "dlRlcRetx", "dlRlcDrop", "dlMacPaddingBytes",
    "dlPdcpNumMissToUppPdu", "dlBler", "latency", "cellId", "nr5gScs", "cellLoad",
    "rachCount", "rachAbortCount", "sub1RrcState", "sub2RrcState", "isDualSimConflict",
    "calcPower", "mtpl", "pathLoss", "fbrxCount", "linkReport", "limitSpeedFlag", "limitSpeedRate"
)

private fun qosCsvHeaders(prefix: String): List<String> = qosFields.map { "${prefix}_qos_$it" }

private fun qosCsvValues(q: QosData?): List<String> {
    if (q == null) return List(qosFields.size) { "" }
    return listOf(
        q.rat, q.endcState, q.arfcn, q.pci, q.band, q.dlBw, q.rsrp, q.rsrq, q.snr, q.svcStatus,
        q.ulTimeStamp, q.ulPdcpNumDataPdu, q.ulPdcpNumDropPdu, q.ulPdcpTput, q.ulRlcNumDataPdu,
        q.ulRlcRetx, q.ulGrant, q.ulBsr, q.ulBler, q.dlTimeStamp, q.dlPdcpNumDataPdu,
        q.dlPdcpTput, q.dlRlcNumDataPdu, q.dlRlcRetx, q.dlRlcDrop, q.dlMacPaddingBytes,
        q.dlPdcpNumMissToUppPdu, q.dlBler, q.latency, q.cellId, q.nr5gScs, q.cellLoad,
        q.rachCount, q.rachAbortCount, q.sub1RrcState, q.sub2RrcState, q.isDualSimConflict,
        q.calcPower, q.mtpl, q.pathLoss, q.fbrxCount, q.linkReport, q.limitSpeedFlag, q.limitSpeedRate
    ).map { it.toString() }
}

private fun globalCsvHeaders(): List<String> = listOf(
    "global_rlfCount", "global_rachWithUlGrantCount", "global_cellChangeCount",
    "global_isRedirectionOccur", "global_mobilitySysMode", "global_mobilityType",
    "global_mobilityStatus", "global_mobilitySourceRat", "global_mobilityTargetRat",
    "global_pagingSysMode", "global_nasSysMode", "global_emmState", "global_emmSubState",
    "global_mm5gState", "global_mm5gSubState", "global_plmnId"
)

private fun globalCsvValues(g: GlobalQosEvent?): List<String> {
    if (g == null) return List(globalCsvHeaders().size) { "" }
    return listOf(
        g.rlfCount, g.rachWithUlGrantCount, g.cellChangeCount, g.isRedirectionOccur,
        g.mobilitySysMode, g.mobilityType, g.mobilityStatus, g.mobilitySourceRat,
        g.mobilityTargetRat, g.pagingSysMode, g.nasSysMode, g.emmState, g.emmSubState,
        g.mm5gState, g.mm5gSubState, g.plmnId
    ).map { it.toString() }
}

private fun formatCsvLinkRate(p: NetworkPanelUiState): String {
    val down = p.linkDownstreamKbps?.let { "${it}Kbps" } ?: "-"
    val up = p.linkUpstreamKbps?.let { "${it}Kbps" } ?: "-"
    return "$down/$up"
}

private fun formatCsvNrBandwidth(p: NetworkPanelUiState): String {
    val carriers = p.nrCaInfo?.carriers.orEmpty()
    if (carriers.isEmpty()) return ""
    fun bw(c: NrCaCarrier): Int = when (c.dlBwRaw) {
        1 -> 5
        2 -> 10
        3 -> 15
        4 -> 20
        5 -> 25
        6 -> 30
        7 -> 40
        8 -> 50
        9 -> 60
        10 -> 70
        11 -> 80
        12 -> 90
        13 -> 100
        14 -> 200
        else -> 0
    }
    val configured = carriers.filter { it.dlStateRaw == 1 || it.dlStateRaw == 2 }
    val active = carriers.filter { it.dlStateRaw == 2 }
    return "${configured.sumOf(::bw)}MHz/${active.sumOf(::bw)}MHz"
}

private fun formatCsvNrCa(p: NetworkPanelUiState): String {
    return p.nrCaInfo?.carriers.orEmpty().joinToString("|") { c ->
        "cc=${c.ccId};pci=${c.pci};band=${c.bandText.ifBlank { c.bandRaw.toString() }};arfcn=${c.dlArfcn};dlState=${c.dlStateRaw};dlBw=${c.dlBwText}"
    }
}

private fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_export_file)))
}

private fun csvEscape(value: String): String {
    val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    val escaped = value.replace("\"", "\"\"")
    return if (needsQuote) "\"$escaped\"" else escaped
}

private fun formatLogTime(ts: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
}
