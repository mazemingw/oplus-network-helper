package com.nvmex.networkhelper.viewmodel.signalradar

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.model.signalradar.AngleBinResult
import com.nvmex.networkhelper.model.signalradar.SignalSample
import com.nvmex.networkhelper.model.signalradar.SignalScanSession
import com.nvmex.networkhelper.util.network.NetworkPanelRepositoryMultiSim
import com.nvmex.networkhelper.util.network.TelephonySnapshotterMultiSim
import com.nvmex.networkhelper.util.signalradar.HeadingTracker
import com.nvmex.networkhelper.util.signalradar.medianFloat
import com.nvmex.networkhelper.util.signalradar.medianInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONObject


data class SavedSessionItem(
    val uri: Uri,
    val displayName: String,
    val createdAt: Long,
    val sizeBytes: Long
)
data class SimTab(
    val subId: Int,
    val slotIndex: Int,
    val displayName: String
)


data class SignalScanUiState(
    val running: Boolean = false,
    val headingDeg: Float = 0f,
    val sims: List<SimTab> = emptyList(),
    val selectedSubId: Int? = null,

    val coveredBins: Set<Int> = emptySet(),
    val binsPreviewRsrp: Map<Int, Int?> = emptyMap(),
    val binsPreviewSinr: Map<Int, Float?> = emptyMap(),

    val targetBin: Int = 0,
    val dwellMs: Long = 0L,
    val dwellNeedMs: Long = 3000L,

    val progressText: String = "",
    val lastSignal: NetworkPanelUiState = NetworkPanelUiState(),
    val lastSession: SignalScanSession? = null,
    val lastSavedUri: Uri? = null,

    val savedList: List<SavedSessionItem> = emptyList(),
    val error: String? = null
)

class SignalScanViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val repo = NetworkPanelRepositoryMultiSim(ctx, TelephonySnapshotterMultiSim(ctx))

    private val headingTracker = HeadingTracker(ctx)

    private val _ui = MutableStateFlow(SignalScanUiState())
    val ui: StateFlow<SignalScanUiState> = _ui.asStateFlow()

    private var scanJob: Job? = null
    private var liveHeadingJob: Job? = null
    private var liveSignalJob: Job? = null

    // 扫描期间的数据容器（最终 session 用）
    private val binSamples: Array<MutableList<SignalSample>> = Array(12) { mutableListOf() }

    // 当前目标扇区的 3 秒采样窗口
    private val dwellWindowSamples: MutableList<SignalSample> = mutableListOf()

    private var startTime: Long = 0L
    private var lastHeading: Float? = null
    private var accumulatedTurn: Float = 0f

    // === live 状态（一直刷新）===
    private var latestHeading: Float = 0f
    private var latestSignal: NetworkPanelUiState = NetworkPanelUiState()
    private var currentBinLive: Int = 0

    // 已锁定结果（完成的扇区）
    private val lockedRsrp: MutableMap<Int, Int?> = mutableMapOf()
    private val lockedSinr: MutableMap<Int, Float?> = mutableMapOf()

    private val binCount = 12
    private val binSizeDeg = 360f / binCount // 30°

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

    init {
        // ✅ 进来就开始：指针实时动 + 信号实时刷新（不依赖 start()）
        liveHeadingJob = viewModelScope.launch {
            headingTracker.headingDegFlow()
                .distinctUntilChanged()
                .onEach { h ->
                    // 累计转动角（展示用）
                    lastHeading?.let { prev ->
                        var d = h - prev
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        accumulatedTurn += abs(d)
                    }
                    lastHeading = h

                    latestHeading = h
                    currentBinLive = binIndexNorthStart(h)

                    _ui.value = _ui.value.copy(headingDeg = h)
                }
                .collect { }
        }

        viewModelScope.launch {
            repo.observeSims().collect { sims ->
                val tabs = sims.map { info ->
                    SimTab(
                        subId = info.subscriptionId,
                        slotIndex = info.simSlotIndex,
                        displayName = runCatching { info.displayName?.toString() }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: runCatching { info.carrierName?.toString() }.getOrNull()
                                ?.takeIf { it.isNotBlank() }
                            ?: "SIM${info.simSlotIndex + 1}"
                    )
                }
                val defaultSubId = _ui.value.selectedSubId ?: tabs.firstOrNull()?.subId
                _ui.value = _ui.value.copy(sims = tabs, selectedSubId = defaultSubId)
            }
        }


        liveSignalJob = viewModelScope.launch {
            repo.observeStates()
                .onEach { map ->
                    val chosen = _ui.value.selectedSubId ?: map.keys.firstOrNull()
                    val s = chosen?.let { map[it] } ?: NetworkPanelUiState()

                    latestSignal = s
                    _ui.value = _ui.value.copy(lastSignal = s, selectedSubId = chosen)

                    val u = _ui.value
                    if (u.running && currentBinLive == u.targetBin) {
                        dwellWindowSamples.add(buildSample(latestHeading, s))
                    }
                }
                .collect { }
        }


        // 启动时加载一次本地列表
        refreshSavedList()
    }

    private fun binIndexNorthStart(headingDeg: Float): Int {
        val h = ((headingDeg % 360f) + 360f) % 360f
        val shifted = (h + binSizeDeg / 2f) % 360f // +15°
        return floor(shifted / binSizeDeg).toInt().coerceIn(0, 11)
    }

    private fun binCenterNorthStart(bin: Int): Float = (bin * binSizeDeg) % 360f

    fun selectSim(subId: Int) {
        if (_ui.value.running) return // 扫描中不允许切卡，避免把两个卡的数据混进同一 session
        _ui.value = _ui.value.copy(selectedSubId = subId)
    }


    fun start() {
        if (scanJob != null) return

        // 清空扫描容器
        for (i in 0 until 12) binSamples[i].clear()
        dwellWindowSamples.clear()
        lockedRsrp.clear()
        lockedSinr.clear()

        startTime = System.currentTimeMillis()
        accumulatedTurn = 0f

        _ui.value = _ui.value.copy(
            running = true,
            coveredBins = emptySet(),
            binsPreviewRsrp = emptyMap(),
            binsPreviewSinr = emptyMap(),
            targetBin = 0,
            dwellMs = 0L,
            dwellNeedMs = 3000L,
            progressText = ctx.getString(R.string.radar_progress_start_instruction),
            error = null,
            lastSession = null,
            lastSavedUri = null
        )

        scanJob = viewModelScope.launch {
            var lastTick = System.currentTimeMillis()

            while (isActive && _ui.value.running) {
                val now = System.currentTimeMillis()
                val dt = (now - lastTick).coerceAtLeast(0L)
                lastTick = now

                val u = _ui.value
                val target = u.targetBin
                val currentBin = currentBinLive

                if (currentBin == target) {
                    val newDwell = (u.dwellMs + dt).coerceAtMost(u.dwellNeedMs)
                    _ui.value = u.copy(dwellMs = newDwell)

                    // 实时预览
                    updatePreviewMaps(targetBin = target)

                    // 达标：锁定当前扇区并推进
                    if (newDwell >= u.dwellNeedMs) {
                        lockCurrentBin(target)

                        val next = (target + 1) % binCount
                        val covered = lockedRsrp.keys.toSet()
                        val done = covered.size
                        val tip = if (done >= binCount) {
                            ctx.getString(R.string.radar_progress_all_done)
                        } else {
                            ctx.getString(
                                R.string.radar_progress_next_target,
                                next,
                                binCenterNorthStart(next).toInt()
                            )
                        }

                        _ui.value = _ui.value.copy(
                            targetBin = next,
                            dwellMs = 0L,
                            coveredBins = covered,
                            progressText = ctx.getString(
                                R.string.radar_progress_done_turn,
                                done,
                                tip,
                                accumulatedTurn.toInt()
                            )
                        )

                        dwellWindowSamples.clear()
                        updatePreviewMaps(targetBin = next)
                    }
                } else {
                    // 离开目标扇区：计时清零，窗口清空
                    if (u.dwellMs != 0L) _ui.value = u.copy(dwellMs = 0L)
                    if (dwellWindowSamples.isNotEmpty()) {
                        dwellWindowSamples.clear()
                        updatePreviewMaps(targetBin = target)
                    }
                }

                delay(50L)
            }
        }
    }

    fun stopAndBuildSession(scenarioName: String, operatorName: String, cellType: String, band: String) {
        val job = scanJob ?: return
        scanJob = null
        job.cancel()

        val end = System.currentTimeMillis()
        val covered = lockedRsrp.keys.size

        if (covered < 8) {
            _ui.value = _ui.value.copy(
                running = false,
                error = ctx.getString(R.string.radar_error_insufficient, covered),
                progressText = ctx.getString(R.string.radar_progress_not_qualified)
            )
            return
        }

        val last = _ui.value.lastSignal

        val bins = (0 until 12).map { i ->
            val list = binSamples[i]
            val rsrp = medianInt(list.mapNotNull { it.rsrpDbm })
            val rsrq = medianFloat(list.mapNotNull { it.rsrqDb })
            val sinr = medianFloat(list.mapNotNull { it.sinrDb })

            AngleBinResult(
                binIndex = i,
                centerDeg = binCenterNorthStart(i),
                count = list.size,
                rsrpMedian = rsrp,
                rsrqMedian = rsrq,
                sinrMedian = sinr
            )
        }

        val samplesCount = binSamples.sumOf { it.size }

        // 构造文件名，将运营商、网络制式和 Band 信息加入文件名
        val defaultScenarioName = ctx.getString(R.string.radar_unnamed_scenario)
        val fileName = "${operatorName}_${cellType}_Band_${band}_${scenarioName.ifBlank { defaultScenarioName }}.json"

        // 创建 Session 对象
        val session = SignalScanSession(
            startedAt = startTime,
            endedAt = end,
            scenarioName = scenarioName.ifBlank { defaultScenarioName },
            operatorName = operatorName,
            cellType = cellType,
            band = band,
            mcc = last.mcc,
            mnc = last.mnc,
            bins = bins,
            samplesCount = samplesCount
        )

        // ✅ 生成后立刻保存到 Downloads/networkhelper，并使用生成的文件名
        val savedUri = runCatching { saveSessionToDownloads(session, fileName) }.getOrNull()

        _ui.value = _ui.value.copy(
            running = false,
            lastSession = session,
            lastSavedUri = savedUri,
            error = if (savedUri == null) ctx.getString(R.string.radar_error_save_failed) else null,
            progressText = ctx.getString(R.string.radar_progress_completed_samples, covered, samplesCount)
        )

        refreshSavedList()
    }



    fun refreshSavedList() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(savedList = querySavedSessions())
        }
    }

    fun formatTs(ts: Long): String = timeFmt.format(Instant.ofEpochMilli(ts))

    fun loadSavedJson(uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    // ====== 内部：预览/锁定/采样 ======

    private fun updatePreviewMaps(targetBin: Int) {
        val rsrpMap = (0 until 12).associateWith { lockedRsrp[it] }.toMutableMap()
        val sinrMap = (0 until 12).associateWith { lockedSinr[it] }.toMutableMap()

        if (targetBin !in lockedRsrp.keys) {
            rsrpMap[targetBin] = medianInt(dwellWindowSamples.mapNotNull { it.rsrpDbm })
            sinrMap[targetBin] = medianFloat(dwellWindowSamples.mapNotNull { it.sinrDb })
        }

        _ui.value = _ui.value.copy(
            binsPreviewRsrp = rsrpMap,
            binsPreviewSinr = sinrMap
        )
    }

    private fun lockCurrentBin(bin: Int) {
        if (bin in lockedRsrp.keys) return

        val rsrp = medianInt(dwellWindowSamples.mapNotNull { it.rsrpDbm })
        val sinr = medianFloat(dwellWindowSamples.mapNotNull { it.sinrDb })

        lockedRsrp[bin] = rsrp
        lockedSinr[bin] = sinr

        binSamples[bin].addAll(dwellWindowSamples)
        updatePreviewMaps(targetBin = _ui.value.targetBin)
    }

    private fun buildSample(h: Float, s: NetworkPanelUiState): SignalSample {
        val rsrpDbm: Int? = run {
            val raw = if (s.cellType == "NR") s.ssRsrp else s.rsrp
            parseDbmInt(raw)
        }
        val rsrqDb: Float? = parseDbFloat(if (s.cellType == "NR") s.ssRsrq else s.rsrq)
        val sinrDb: Float? = parseDbFloat(if (s.cellType == "NR") s.ssSinr else s.sinr)

        return SignalSample(
            t = System.currentTimeMillis(),
            headingDeg = h,
            operatorName = s.operatorName,
            mcc = s.mcc,
            mnc = s.mnc,
            cellType = s.cellType,
            duplex = s.duplex,
            band = s.band,
            arfcn = s.arfcn,
            rsrpDbm = rsrpDbm,
            rsrqDb = rsrqDb,
            sinrDb = sinrDb
        )
    }

    private fun parseDbmInt(text: String): Int? =
        Regex("""-?\d+""").find(text)?.value?.toIntOrNull()

    private fun parseDbFloat(text: String): Float? =
        Regex("""-?\d+(\.\d+)?""").find(text)?.value?.toFloatOrNull()

    // ====== 保存/读取：Downloads/networkhelper ======

    private fun buildSessionJson(session: SignalScanSession): String {
        // 不引库：手搓 JSON（够用且可控）
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        val binsJson = session.bins.joinToString(prefix = "[", postfix = "]") { b ->
            """{
              "binIndex":${b.binIndex},
              "centerDeg":${b.centerDeg},
              "count":${b.count},
              "rsrpMedian":${b.rsrpMedian?.toString() ?: "null"},
              "rsrqMedian":${b.rsrqMedian?.toString() ?: "null"},
              "sinrMedian":${b.sinrMedian?.toString() ?: "null"}
            }""".trimIndent()
        }

        return """
            {
              "startedAt":${session.startedAt},
              "endedAt":${session.endedAt},
              "startedAtText":"${esc(formatTs(session.startedAt))}",
              "endedAtText":"${esc(formatTs(session.endedAt))}",
              "scenarioName":"${esc(session.scenarioName)}",
              "operatorName":"${esc(session.operatorName)}",
              "mcc":"${esc(session.mcc)}",
              "mnc":"${esc(session.mnc)}",
              "samplesCount":${session.samplesCount},
              "bins":$binsJson
            }
        """.trimIndent()
    }

    private fun saveSessionToDownloads(session: SignalScanSession, fileName: String): Uri? {
        val json = buildSessionJson(session)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用 MediaStore 插入到下载目录，不需要权限
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)  // 使用传入的 fileName
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/networkhelper/") // 保存到 Download/networkhelper/
            }

            // 获取文件的 URI
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

            // 写入文件内容到指定 URI
            ctx.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(json.toByteArray(Charsets.UTF_8))
            }

            uri
        } else {
            // Android 9 以下版本需要手动申请 WRITE_EXTERNAL_STORAGE 权限
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "networkhelper")
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, fileName)  // 使用传入的 fileName
            // 写入文件内容
            file.writeText(json, Charsets.UTF_8)

            // 返回文件的 Uri，适用于低于 Android 10 的版本
            return Uri.fromFile(file)
        }
    }



    private fun querySavedSessions(): List<SavedSessionItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()

        val cr = ctx.contentResolver
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.RELATIVE_PATH
        )

        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf("Download/networkhelper/")

        val list = mutableListOf<SavedSessionItem>()
        cr.query(uri, projection, selection, selectionArgs, "${MediaStore.Downloads.DATE_ADDED} DESC")
            ?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)

                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx) ?: "unknown.json"
                    val dateAddedSec = c.getLong(dateIdx)
                    val size = c.getLong(sizeIdx)

                    val itemUri = ContentUris.withAppendedId(uri, id)
                    list += SavedSessionItem(
                        uri = itemUri,
                        displayName = name,
                        createdAt = dateAddedSec * 1000L,
                        sizeBytes = size
                    )
                }
            }

        return list
    }
    fun loadSessionFromUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = loadSavedJson(uri) ?: error(ctx.getString(R.string.radar_error_read_failed))
                val session = parseSessionJson(json)

                // 回显到图
                val rsrpMap = session.bins.associate { it.binIndex to it.rsrpMedian }
                val sinrMap = session.bins.associate { it.binIndex to it.sinrMedian }

                val covered = session.bins
                    .filter { (it.count > 0) || (it.rsrpMedian != null) || (it.sinrMedian != null) }
                    .map { it.binIndex }
                    .toSet()

                _ui.value = _ui.value.copy(
                    running = false,
                    lastSession = session,
                    lastSavedUri = uri,

                    binsPreviewRsrp = rsrpMap,
                    binsPreviewSinr = sinrMap,
                    coveredBins = covered,

                    // 指针/实时信号本来就常驻刷新，不用动
                    targetBin = 0,
                    dwellMs = 0L,
                    progressText = ctx.getString(
                        R.string.radar_progress_loaded,
                        session.scenarioName,
                        formatTs(session.startedAt)
                    ),
                    error = null
                )
            }.onFailure { e ->
                _ui.value = _ui.value.copy(error = ctx.getString(R.string.radar_error_replay_failed, e.message ?: ""))
            }
        }
    }

    private fun parseSessionJson(json: String): SignalScanSession {
        val root = JSONObject(json)

        val startedAt = root.optLong("startedAt", 0L)
        val endedAt = root.optLong("endedAt", 0L)
        val scenarioName = root.optString("scenarioName", ctx.getString(R.string.radar_unnamed_scenario))
        val operatorName = root.optString("operatorName", "-")
        val cellType = root.optString("cellType", "-") // 获取 cellType
        val band = root.optString("band", "-") // 获取 band
        val mcc = root.optString("mcc", "-")
        val mnc = root.optString("mnc", "-")
        val samplesCount = root.optInt("samplesCount", 0)

        val binsArr = root.optJSONArray("bins") ?: JSONArray()
        val bins = buildList {
            for (i in 0 until binsArr.length()) {
                val b = binsArr.optJSONObject(i) ?: continue

                val idx = b.optInt("binIndex", i)
                val centerDeg = b.optDouble("centerDeg", (idx * 30).toDouble()).toFloat()
                val count = b.optInt("count", 0)

                val rsrpMedian = if (b.isNull("rsrpMedian")) null else b.optInt("rsrpMedian")
                val rsrqMedian = if (b.isNull("rsrqMedian")) null else b.optDouble("rsrqMedian").toFloat()
                val sinrMedian = if (b.isNull("sinrMedian")) null else b.optDouble("sinrMedian").toFloat()

                add(
                    AngleBinResult(
                        binIndex = idx,
                        centerDeg = centerDeg,
                        count = count,
                        rsrpMedian = rsrpMedian,
                        rsrqMedian = rsrqMedian,
                        sinrMedian = sinrMedian
                    )
                )
            }
        }.sortedBy { it.binIndex }

        return SignalScanSession(
            startedAt = startedAt,
            endedAt = endedAt,
            scenarioName = scenarioName,
            operatorName = operatorName,
            cellType = cellType, // 使用提取的 cellType
            band = band,         // 使用提取的 band
            mcc = mcc,
            mnc = mnc,
            bins = bins,
            samplesCount = samplesCount
        )
    }


    fun deleteSaved(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val rows = ctx.contentResolver.delete(uri, null, null)
                if (rows <= 0) error(ctx.getString(R.string.radar_error_delete_failed))
                refreshSavedList()
            }.onFailure { e ->
                _ui.value = _ui.value.copy(error = ctx.getString(R.string.radar_error_delete_failed_with_reason, e.message ?: ""))
            }
        }
    }


}
