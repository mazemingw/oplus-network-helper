package com.nvmex.networkhelper.util.network

import android.content.Context
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.ModemQosInfo
import com.nvmex.networkhelper.model.network.ModemQosSessions
import com.nvmex.networkhelper.model.network.ModemMmInfo
import com.nvmex.networkhelper.model.network.ModemQosUiState
import com.nvmex.networkhelper.util.shell.SuShellRunner
import com.nvmex.networkhelper.util.network.telephony.NetworkTypeResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads Qualcomm NAS QoS messages through Oplus/Qualcomm's diag_socket_log.
 * No raw DIAG or NAS packet is written to disk.
 */
@Singleton
class ModemQosRepository @Inject constructor(@ApplicationContext context: Context) {
    private val appContext = context.applicationContext
    private val su = SuShellRunner()
    private val mtkReader = MtkQosReader(su, appContext.cacheDir)
    private val qosCache = ModemQosCache(appContext)
    private val started = AtomicBoolean(false)
    private val nasLogCount = AtomicInteger(0)

    private val _state = MutableStateFlow(ModemQosUiState())
    val state: StateFlow<ModemQosUiState> = _state.asStateFlow()

    init {
        // Delete retired, subId-based formats. The current cache is keyed by a
        // SHA-256 digest of ICCID so SIM changes cannot inherit another card's values.
        listOf("modem_qos_cache", "modem_qos_cache_v2", "modem_qos_cache_v3").forEach {
            appContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var loggerPid: Int? = null
    private var collectorJob: Job? = null
    private var refreshJob: Job? = null
    private var cleanupJob: Job? = null
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownerLock = Any()
    @Volatile private var owners = 0
    private var attachJob: Job? = null
    private var networkMonitorJob: Job? = null
    private val actionMutex = Mutex()
    private val networkTracker = ModemQosNetworkTracker()
    private val maskRefreshRequested = AtomicBoolean(false)
    @Volatile private var radioResetting = false
    @Volatile private var readingSubId: Int? = null

    // Multiple activities/back-stack entries must share one vendor DIAG connection.
    fun acquire() = synchronized(ownerLock) {
        owners += 1
        if (owners == 1) {
            attachJob = processScope.launch {
                restoreCachedSessions()
                collectorJob?.join()
                synchronized(ownerLock) {
                    if (owners > 0) {
                        start(processScope)
                        networkMonitorJob = processScope.launch { monitorNetworks() }
                    }
                }
            }
        }
    }

    fun release() = synchronized(ownerLock) {
        if (owners > 0) owners -= 1
        if (owners == 0) {
            attachJob?.cancel()
            networkMonitorJob?.cancel()
            stop()
        }
    }

    private fun start(scope: CoroutineScope) {
        if (owners == 0) return
        if (!started.compareAndSet(false, true)) return
        collectorJob = scope.launch(Dispatchers.IO) { collectDiag() }
    }

    private fun stop() {
        started.set(false)
        clientSocket.closeQuietly()
        serverSocket.closeQuietly()
        collectorJob?.cancel()
        refreshJob?.cancel()
        cleanupJob?.cancel()
    }

    private fun restoreCachedSessions() {
        val restored = qosCache.restoreActive()
        if (restored.isEmpty()) return
        updateState { old ->
            val merged = old.valuesBySubId.toMutableMap()
            restored.forEach { (subId, sessions) -> merged.putIfAbsent(subId, sessions) }
            old.copy(
                valuesBySubId = merged,
                noticeBySubId = old.noticeBySubId + restored.keys.associateWith {
                    "已从本地缓存恢复 QoS；读取成功后会自动更新"
                }
            )
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.network_qos_cache_restored_toast, restored.size),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun refreshSession(subId: Int) = synchronized(ownerLock) {
        val scope = processScope
        if (refreshJob?.isActive == true || cleanupJob?.isActive == true) return@synchronized
        readingSubId = subId
        updateState {
            it.copy(
                refreshing = true,
                refreshingSubId = subId,
                lastError = null,
                lastNotice = null,
                errorBySubId = it.errorBySubId - subId,
                noticeBySubId = it.noticeBySubId - subId
            )
        }
        refreshJob = scope.launch(Dispatchers.IO) {
            try {
                withTimeout(READ_TIMEOUT_MS) {
                    actionMutex.withLock {
                        assertRadioActionSafe()
                        check(subId in readNetworkSources()) { "所选 SIM 已不可用" }
                        val source = awaitStableSource(subId)
                        if (mtkReader.isPlatform()) {
                            val subscriptions = appContext.getSystemService(SubscriptionManager::class.java)
                                ?.activeSubscriptionInfoList.orEmpty()
                            val slot = subscriptions.firstOrNull { it.subscriptionId == subId }?.simSlotIndex
                                ?: error("所选 SIM 已不可用")
                            val results = mtkReader.read(subId, slot, source, subscriptions.size == 1)
                            currentCoroutineContext().ensureActive()
                            check(currentDataSource(subId) == source) { "读取期间网络已变化，已丢弃本次 AT 结果" }
                            var sessions = _state.value.valuesBySubId[subId] ?: ModemQosSessions()
                            results.forEach { sessions = sessions.withInfo(it) }
                            val cached = results.isNotEmpty() && qosCache.save(subId, sessions)
                            val notice = if (results.isEmpty()) {
                                "AT 未返回可解析的 QoS，未使用 GBR / MBR 代替 AMBR"
                            } else if (!cached) {
                                "QoS 已读取，但无法取得 ICCID，未写入本地缓存"
                            } else {
                                "已通过实验性 MTK AT 通道读取并更新本地缓存"
                            }
                            updateState { old -> old.copy(
                                valuesBySubId = old.valuesBySubId + (subId to sessions),
                                noticeBySubId = old.noticeBySubId + (subId to notice),
                                errorBySubId = old.errorBySubId - subId
                            ) }
                            return@withLock
                        }
                        stopCollectorAndJoin()
                        start(scope)
                        ensureCollector(scope)
                        currentCoroutineContext().ensureActive()
                        radioResetting = true
                        updateState { it.copy(mmBySubId = it.mmBySubId - subId) }
                        val startedAt = System.currentTimeMillis()
                        reregisterForQos(su::execSu, ::assertRadioActionSafe)
                        updateState { it.copy(collectorReady = false) }
                        maskRefreshRequested.set(true)
                        ensureCollector(scope)
                        check(awaitStableSource(subId) == source) { "读取期间网络已切换，请在新网络上重新读取" }
                        networkTracker.reset(readNetworkSources(), SystemClock.elapsedRealtime())
                        radioResetting = false
                        var dataCaptured = false
                        var imsCaptured = false
                        for (attempt in 0 until 160) {
                            val sources = readNetworkSources()
                            check(subId in sources) { "所选 SIM 已不可用" }
                            check(sources[subId] == null || sources[subId] == source) { "读取期间网络已切换，请在新网络上重新读取" }
                            check(_state.value.collectorReady) { "DIAG 采集通道已断开，可先清理 DIAG 再重读" }
                            val sessions = _state.value.valuesBySubId[subId]
                            dataCaptured = sources[subId] == source && sessions?.dataFor(source)?.updatedAt?.let { it >= startedAt } == true
                            imsCaptured = sources[subId] == source && sessions?.imsFor(source)?.updatedAt?.let { it >= startedAt } == true
                            if (dataCaptured && imsCaptured) break
                            delay(250)
                        }
                        val cached = if (dataCaptured || imsCaptured) {
                            _state.value.valuesBySubId[subId]?.let { qosCache.save(subId, it) } == true
                        } else false
                        val notice = when {
                            (dataCaptured || imsCaptured) && !cached -> "QoS 已读取，但无法取得 ICCID，未写入本地缓存"
                            dataCaptured && imsCaptured -> "已收到 DATA 和 IMS QoS 日志，并更新本地缓存"
                            dataCaptured -> "DATA 已读取并缓存；尚未收到 IMS QoS 日志"
                            imsCaptured -> "IMS 已读取并缓存；尚未收到当前网络的 DATA QoS 日志"
                            else -> "本次注册未收到可解析的 QoS 日志，可清理 DIAG 后重试"
                        }
                        updateState { old -> old.copy(
                            noticeBySubId = old.noticeBySubId + (subId to notice),
                            errorBySubId = old.errorBySubId - subId
                        ) }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                updateState { old -> old.copy(
                    errorBySubId = old.errorBySubId + (subId to "读取超时（60 秒），已停止本次读取"),
                    noticeBySubId = old.noticeBySubId - subId
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateState { old -> old.copy(
                    errorBySubId = old.errorBySubId + (subId to (error.message ?: "QoS 重读失败")),
                    noticeBySubId = old.noticeBySubId - subId
                ) }
            } finally {
                radioResetting = false
                readingSubId = null
                if (owners > 0 && collectorJob?.isActive != true && !mtkReader.isPlatform()) start(scope)
                updateState { it.copy(refreshing = false, refreshingSubId = null) }
            }
        }
    }

    private fun readNetworkSources(): Map<Int, ModemQosInfo.Source?> = runCatching {
        appContext.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList.orEmpty().associate { it.subscriptionId to currentDataSource(it.subscriptionId) }
    }.getOrDefault(emptyMap())

    private suspend fun awaitStableSource(subId: Int): ModemQosInfo.Source {
        var candidate: ModemQosInfo.Source? = null
        var since = SystemClock.elapsedRealtime()
        repeat(80) {
            val sources = readNetworkSources()
            check(subId in sources) { "所选 SIM 已不可用" }
            val source = sources[subId]
            if (source != candidate) {
                candidate = source
                since = SystemClock.elapsedRealtime()
            }
            if (source != null && SystemClock.elapsedRealtime() - since >= 2_000L) return source
            delay(250)
        }
        error("所选 SIM 的 4G/5G 网络尚未稳定，请稍后再读取")
    }

    private suspend fun monitorNetworks() {
        networkTracker.reset(readNetworkSources(), SystemClock.elapsedRealtime())
        while (currentCoroutineContext().isActive) {
            delay(500)
            if (radioResetting) continue // Ignore our own bounded airplane cycle.
            val changes = networkTracker.observe(readNetworkSources(), SystemClock.elapsedRealtime())
            if (changes.invalidated.isNotEmpty()) {
                if (readingSubId in changes.invalidated) refreshJob?.cancelAndJoin()
                updateState { old ->
                    val notices = changes.invalidated.associateWith {
                        "网络已变化；已保留该 SIM 的 4G/5G 缓存，等待新网络稳定"
                    }
                    old.copy(
                        mmBySubId = old.mmBySubId - changes.invalidated,
                        errorBySubId = old.errorBySubId - changes.invalidated,
                        noticeBySubId = old.noticeBySubId + notices,
                        lastError = null
                    )
                }
            }
            if (changes.stabilized.isNotEmpty()) {
                actionMutex.withLock {
                    if (owners == 0 || radioResetting) return@withLock
                    if (mtkReader.isPlatform()) {
                        updateState { it.copy(lastError = null, lastNotice = "网络已稳定，可通过 MTK AT 重新读取") }
                        return@withLock
                    }
                    try {
                        stopCollectorAndJoin()
                        start(processScope)
                        ensureCollector(processScope)
                        updateState { it.copy(lastError = null, lastNotice = "网络已稳定，DIAG 已重新订阅；可重新读取") }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        updateState { it.copy(lastError = error.message ?: "DIAG 重连失败") }
                    }
                }
            }
        }
    }

    fun cleanupDiag() = synchronized(ownerLock) {
        if (mtkReader.isPlatform()) {
            updateState { it.copy(lastError = "MTK 使用 AT 通道，不执行高通 DIAG 清理") }
            return@synchronized
        }
        val scope = processScope
        if (cleanupJob?.isActive == true || refreshJob?.isActive == true) return@synchronized
        updateState { it.copy(cleaning = true, cleanupOutput = null, lastError = null, lastNotice = null) }
        cleanupJob = scope.launch(Dispatchers.IO) {
            try {
                actionMutex.withLock {
                    var paused = false
                    try {
                        assertRadioActionSafe()
                        paused = true
                        stopCollectorAndJoin()
                        currentCoroutineContext().ensureActive()
                        val output = DiagCleanup.run(su::execSu)
                        updateState {
                            it.copy(cleanupOutput = output, lastNotice = "已执行一轮手动清理；diag-router 是否重启由系统决定，未主动重启或循环清理")
                        }
                    } finally {
                        if (paused && scope.isActive) start(scope)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateState { it.copy(lastError = error.message ?: "DIAG 清理失败") }
            } finally {
                updateState { it.copy(cleaning = false) }
            }
        }
    }

    private fun assertRadioActionSafe() {
        check(su.hasSu(3_000)) { "未获取 Root 权限" }
        val telecom = appContext.getSystemService(TelecomManager::class.java)
            ?: error("无法确认通话状态，取消操作")
        val inCall = runCatching { telecom.isInCall }.getOrElse { error("无法确认通话状态，取消操作") }
        check(!inCall) { "通话中不执行网络重注册或 DIAG 清理" }
    }

    private suspend fun stopCollectorAndJoin() {
        started.set(false)
        clientSocket.closeQuietly()
        serverSocket.closeQuietly()
        collectorJob?.cancelAndJoin()
        collectorJob = null
    }

    private suspend fun ensureCollector(scope: CoroutineScope) {
        if (_state.value.collectorReady) return
        if (collectorJob?.isActive != true) {
            stopCollectorAndJoin()
            start(scope)
        }
        for (attempt in 0 until 120) {
            if (_state.value.collectorReady) return
            if (collectorJob?.isActive != true) break
            delay(250)
        }
        error(_state.value.lastError ?: "DIAG 采集器尚未就绪，请先清理 DIAG 后重试")
    }

    private suspend fun collectDiag() {
        var transport: DiagTransport? = null
        val activeMasks = mutableMapOf<Int, Int>()
        try {
            if (mtkReader.isPlatform()) return // MTK uses on-demand AT, never Qualcomm DIAG.
            maskRefreshRequested.set(false)
            nasLogCount.set(0)
            if (!su.hasSu(1_500)) error("Root is unavailable")
            val probe = su.execSu("test -x /vendor/bin/diag_socket_log", 2_000)
            if (probe.code != 0) error("diag_socket_log is unavailable")

            // diag_socket_log only opens an IPv4 socket on current Oplus builds.
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also {
                it.soTimeout = 10_000
                serverSocket = it
            }
            val command = "/vendor/bin/diag_socket_log -a 127.0.0.1 -p ${server.localPort} -r 1 " +
                "</dev/null >/dev/null 2>&1 & echo \$!"
            val launch = su.execSu(command, 4_000)
            if (launch.code != 0) error(launch.err.ifBlank { "Unable to start diag_socket_log" })
            loggerPid = launch.out.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull()
                ?: error("diag_socket_log did not return a process id")
            Log.i(TAG, "diag_socket_log started (pid=$loggerPid)")

            val socket = server.accept().also {
                it.soTimeout = 1_000
                it.tcpNoDelay = true
                clientSocket = it
            }
            transport = DiagTransport(socket)

            configureNasLogs(transport, activeMasks)
            while (currentCoroutineContext().isActive && started.get()) {
                if (maskRefreshRequested.getAndSet(false)) configureNasLogs(transport, activeMasks)
                val frame = transport.readFrame() ?: continue
                handleDiagPacket(frame, null)
            }
        } catch (t: Throwable) {
            if (started.get() && t !is CancellationException) {
                Log.w(TAG, "DIAG collector stopped: ${t.message}", t)
                updateState { it.copy(collectorReady = false, lastError = t.message) }
            } else Log.i(TAG, "DIAG collector paused")
        } finally {
            activeMasks.forEach { (equipment, maskBits) ->
                runCatching {
                    transport?.sendFrame(
                        buildSetMaskRequest(equipment, maskBits, ByteArray((maskBits + 7) / 8))
                    )
                }
            }
            transport.closeQuietly()
            clientSocket.closeQuietly()
            serverSocket.closeQuietly()
            clientSocket = null
            serverSocket = null
            loggerPid?.let(::killLogger)
            loggerPid = null
            started.set(false)
            updateState { it.copy(collectorReady = false) }
        }
    }

    private fun configureNasLogs(transport: DiagTransport, activeMasks: MutableMap<Int, Int>) {
        val ranges = transport.sendAndWait(buildRetrieveRangesRequest(), DIAG_LOG_CONFIG, 1) {
            handleDiagPacket(it, null)
        } ?: error("DIAG 未返回日志范围")
        check(ranges.size >= 12 + 16 * 4 && ranges.u32le(8) == 0L) { "DIAG 日志范围响应无效" }
        var lteNrEnabled = false
        LOG_CODES_BY_EQUIPMENT.forEach { (equipment, codes) ->
            val maskBits = ranges.u32le(12 + equipment * 4).toInt()
            if (maskBits !in 1..4096 || codes.none { (it and 0x0fff) < maskBits }) return@forEach
            val enabled = transport.sendAndWait(
                buildSetMaskRequest(equipment, maskBits, buildLogMask(equipment, maskBits, codes)),
                DIAG_LOG_CONFIG, 3
            ) { handleDiagPacket(it, null) }
            if (enabled != null && enabled.isSuccessfulLogConfigResponse()) {
                activeMasks[equipment] = maskBits
                if (equipment == LOG_EQUIPMENT_LTE_NR) lteNrEnabled = true
                Log.i(TAG, "Enabled NAS logs for equipment=$equipment")
            }
        }
        check(lteNrEnabled) { "LTE/NR NAS DIAG 日志订阅失败" }
        updateState { it.copy(collectorReady = true) }
    }

    private fun handleDiagPacket(packet: ByteArray, radioSlot: Int?) {
        if (packet.isEmpty()) return
        if (packet[0].u8() == DIAG_MULTI_RADIO) {
            if (packet.size < 9) return
            val radioId = packet.u32le(4).toInt()
            val slot = when {
                radioId <= 0 -> 0
                radioId > 2 -> 1
                else -> radioId - 1
            }
            handleDiagPacket(packet.copyOfRange(8, packet.size), slot)
            return
        }
        if (packet[0].u8() != DIAG_LOG || packet.size < 16) return

        val logType = packet.u16le(6)
        val declaredLength = packet.u16le(4)
        val payloadEnd = (4 + declaredLength).coerceAtMost(packet.size)
        if (payloadEnd <= 16) return
        val payload = packet.copyOfRange(16, payloadEnd)

        val parsed = when (logType) {
            LOG_5GSM_DL, LOG_5GMM_DL -> ModemQosParser.parse5gDiagPayload(payload)
            LOG_LTE_ESM_DL -> ModemQosParser.parseLteDiagPayload(payload)
            else -> null
        }
        if (logType == LOG_5GMM_STATE) {
            val mm = ModemQosParser.parse5gMmStateDiagPayload(payload) ?: return
            val subId = resolveSubId(radioSlot) ?: return
            if (!radioResetting && currentDataSource(subId) != ModemQosInfo.Source.NR_5GSM) return
            val info = ModemMmInfo(
                subId = subId,
                slotIndex = radioSlot ?: resolveSlotIndex(subId),
                mmState = mm.mmState,
                mmSubState = mm.mmSubState,
                fiveGTmsi = mm.fiveGTmsi
            )
            updateState { old -> old.copy(mmBySubId = old.mmBySubId + (subId to info)) }
            Log.i(TAG, "5GMM state=${mm.mmState}/${mm.mmSubState} subId=$subId")
            return
        }
        if (logType in PARSED_LOG_CODES && nasLogCount.getAndIncrement() < 40) {
            Log.i(TAG, "NAS log=0x${logType.toString(16)} slot=$radioSlot bytes=${payload.size} parsed=${parsed != null}")
        }
        parsed ?: return

        val subId = resolveSubId(radioSlot) ?: return
        // Late NR frames must never repopulate a SIM that has already moved to LTE (or vice versa).
        if (!radioResetting && currentDataSource(subId) != parsed.source) return
        val sessionKind = classifySession(parsed.dnn) ?: return
        val info = ModemQosInfo(
            subId = subId,
            slotIndex = radioSlot ?: resolveSlotIndex(subId),
            sessionKind = sessionKind,
            dnn = parsed.dnn,
            logCode = logType,
            qci = parsed.qci,
            fiveQi = parsed.fiveQi,
            qfi = parsed.qfi,
            downlinkAmbrKbps = parsed.downlinkAmbrKbps,
            uplinkAmbrKbps = parsed.uplinkAmbrKbps,
            source = parsed.source
        )
        // Store EPS and NR sessions separately. UI selects by the current RAT;
        // arrival order must never make an old 5G value masquerade as 4G.
        Log.i(
            TAG,
            "Captured ${info.source}: subId=$subId kind=$sessionKind qci=${info.qci} 5qi=${info.fiveQi} " +
                "dlKbps=${info.downlinkAmbrKbps} ulKbps=${info.uplinkAmbrKbps}"
        )
        updateState { old ->
            val current = old.valuesBySubId[subId] ?: ModemQosSessions()
            val updated = current.withInfo(info)
            old.copy(valuesBySubId = old.valuesBySubId + (subId to updated))
        }
    }

    private fun classifySession(dnn: String?): ModemQosInfo.SessionKind? {
        val normalized = dnn?.trim()?.lowercase() ?: return null
        return if (normalized == "ims" || normalized.startsWith("ims.")) {
            ModemQosInfo.SessionKind.IMS
        } else {
            ModemQosInfo.SessionKind.DATA
        }
    }

    private fun resolveSubId(slotIndex: Int?): Int? {
        if (slotIndex == null) {
            // A packet without radio attribution cannot safely be borrowed by either of two SIMs.
            return runCatching {
                appContext.getSystemService(SubscriptionManager::class.java)
                    ?.activeSubscriptionInfoList?.singleOrNull()?.subscriptionId
            }.getOrNull()
        }
        return runCatching {
            appContext.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                ?.firstOrNull { it.simSlotIndex == slotIndex }
                ?.subscriptionId
        }.getOrNull()
    }

    private fun resolveSlotIndex(subId: Int): Int? = runCatching {
        appContext.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList
            ?.firstOrNull { it.subscriptionId == subId }
            ?.simSlotIndex
    }.getOrNull()

    private fun currentDataSource(subId: Int): ModemQosInfo.Source? = runCatching {
        val tm = appContext.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(subId) ?: return@runCatching null
        // Non-DDS dataNetworkType can be UNKNOWN while its PS registration is known.
        val resolved = NetworkTypeResolver().resolve(tm, tm.serviceState, null)
        ModemQosInfo.Source.forNetwork(resolved.dataType, resolved.nrMode)
    }.getOrNull()

    private fun killLogger(pid: Int) {
        if (pid > 1) su.execSu("kill $pid", 2_000)
    }

    private inline fun updateState(transform: (ModemQosUiState) -> ModemQosUiState) {
        _state.update(transform)
    }

    private class DiagTransport(private val socket: Socket) : Closeable {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()
        private val pending = ByteArrayOutputStream()

        fun sendAndWait(
            request: ByteArray,
            responseOpcode: Int,
            operation: Int,
            onUnrelatedPacket: (ByteArray) -> Unit
        ): ByteArray? {
            sendFrame(request)
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val frame = readFrame() ?: continue
                if (frame.size >= 12 && frame[0].u8() == responseOpcode && frame.u32le(4) == operation.toLong()) return frame
                if (
                    frame.size > 8 &&
                    frame[0].u8() == DIAG_MULTI_RADIO &&
                    frame.size >= 20 && frame[8].u8() == responseOpcode && frame.u32le(12) == operation.toLong()
                ) {
                    return frame.copyOfRange(8, frame.size)
                }
                onUnrelatedPacket(frame)
            }
            return null
        }

        fun sendFrame(payload: ByteArray) {
            val crc = crc16X25(payload)
            val framed = ByteArrayOutputStream(payload.size + 8)
            payload.forEach { framed.writeEscaped(it.u8()) }
            framed.writeEscaped(crc and 0xff)
            framed.writeEscaped((crc ushr 8) and 0xff)
            framed.write(HDLC_TRAILER)
            output.write(framed.toByteArray())
            output.flush()
        }

        fun readFrame(): ByteArray? {
            while (true) {
                val next = try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    return null
                }
                if (next < 0) error("DIAG socket closed")
                if (next != HDLC_TRAILER) {
                    if (pending.size() < MAX_FRAME_SIZE) pending.write(next) else pending.reset()
                    continue
                }
                val encoded = pending.toByteArray()
                pending.reset()
                if (encoded.isEmpty()) continue
                val decoded = unescape(encoded) ?: continue
                if (decoded.size < 3) continue
                val payload = decoded.copyOf(decoded.size - 2)
                val receivedCrc = decoded[decoded.size - 2].u8() or (decoded.last().u8() shl 8)
                if (crc16X25(payload) != receivedCrc) continue
                return payload
            }
        }

        override fun close() = socket.close()

        private fun ByteArrayOutputStream.writeEscaped(value: Int) {
            if (value == HDLC_ESCAPE || value == HDLC_TRAILER) {
                write(HDLC_ESCAPE)
                write(value xor 0x20)
            } else {
                write(value)
            }
        }

        private fun unescape(encoded: ByteArray): ByteArray? {
            val out = ByteArrayOutputStream(encoded.size)
            var escaped = false
            encoded.forEach { byte ->
                val value = byte.u8()
                if (escaped) {
                    out.write(value xor 0x20)
                    escaped = false
                } else if (value == HDLC_ESCAPE) {
                    escaped = true
                } else {
                    out.write(value)
                }
            }
            return if (escaped) null else out.toByteArray()
        }
    }

    companion object {
        private const val TAG = "ModemQos"
        private const val READ_TIMEOUT_MS = 60_000L
        private const val DIAG_LOG = 0x10
        private const val DIAG_LOG_CONFIG = 0x73
        private const val DIAG_MULTI_RADIO = 0x98
        private const val LOG_EQUIPMENT_GSM = 0x05
        private const val LOG_EQUIPMENT_UMTS = 0x07
        private const val LOG_EQUIPMENT_LTE_NR = 0x0b
        private const val LOG_GPRS_SM_GMM = 0x5230
        private const val LOG_UMTS_NAS = 0x713a
        private const val LOG_UMTS_DSDS_NAS = 0x7b3a
        private const val LOG_LTE_ESM_DL = 0xb0e2
        private const val LOG_5GSM_DL = 0xb800
        private const val LOG_5GMM_DL = 0xb80a
        private const val LOG_5GMM_STATE = 0xb80c
        private const val HDLC_ESCAPE = 0x7d
        private const val HDLC_TRAILER = 0x7e
        private const val MAX_FRAME_SIZE = 1 shl 20
        private val LOG_CODES_BY_EQUIPMENT = mapOf(
            LOG_EQUIPMENT_GSM to setOf(LOG_GPRS_SM_GMM),
            LOG_EQUIPMENT_UMTS to setOf(LOG_UMTS_NAS, LOG_UMTS_DSDS_NAS),
            LOG_EQUIPMENT_LTE_NR to setOf(
                // Legacy LTE ESM/EMM secured + plain OTA log numbers.
                0xb0e0, 0xb0e1, 0xb0e2, 0xb0e3,
                0xb0ea, 0xb0eb, 0xb0ec, 0xb0ed,
                // Current NR 5GSM/5GMM log numbers.
                0xb800, 0xb801, 0xb808, 0xb809, 0xb80a, 0xb80b, 0xb80c, 0xb814
            )
        )
        private val PARSED_LOG_CODES = setOf(LOG_LTE_ESM_DL, LOG_5GSM_DL, LOG_5GMM_DL)

        internal fun crc16X25(bytes: ByteArray): Int {
            var crc = 0xffff
            bytes.forEach { byte ->
                crc = crc xor byte.u8()
                repeat(8) {
                    crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
                }
            }
            return crc xor 0xffff
        }

        private fun buildRetrieveRangesRequest(): ByteArray = byteArrayOf(
            DIAG_LOG_CONFIG.toByte(), 0, 0, 0, 1, 0, 0, 0
        )

        private fun buildSetMaskRequest(equipment: Int, bitCount: Int, mask: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(16 + mask.size)
            out.write(DIAG_LOG_CONFIG)
            out.write(byteArrayOf(0, 0, 0))
            out.writeU32le(3)
            out.writeU32le(equipment)
            out.writeU32le(bitCount)
            out.write(mask)
            return out.toByteArray()
        }

        private fun buildLogMask(equipment: Int, bitCount: Int, codes: Set<Int>): ByteArray {
            val mask = ByteArray((bitCount + 7) / 8)
            codes.forEach { code ->
                if (code ushr 12 != equipment) return@forEach
                val index = code and 0x0fff
                if (index >= bitCount) return@forEach
                mask[index / 8] = (mask[index / 8].u8() or (1 shl (index % 8))).toByte()
            }
            return mask
        }

        private fun ByteArrayOutputStream.writeU32le(value: Int) {
            write(value and 0xff)
            write((value ushr 8) and 0xff)
            write((value ushr 16) and 0xff)
            write((value ushr 24) and 0xff)
        }

        private fun Byte.u8(): Int = toInt() and 0xff

        private fun ByteArray.u16le(offset: Int): Int {
            if (offset < 0 || offset + 1 >= size) return 0
            return this[offset].u8() or (this[offset + 1].u8() shl 8)
        }

        private fun ByteArray.u32le(offset: Int): Long {
            if (offset < 0 || offset + 3 >= size) return 0
            return this[offset].u8().toLong() or
                (this[offset + 1].u8().toLong() shl 8) or
                (this[offset + 2].u8().toLong() shl 16) or
                (this[offset + 3].u8().toLong() shl 24)
        }

        private fun ByteArray.isSuccessfulLogConfigResponse(): Boolean {
            return size >= 12 && this[0].u8() == DIAG_LOG_CONFIG && u32le(4) == 3L && u32le(8) == 0L
        }

        private fun Closeable?.closeQuietly() {
            runCatching { this?.close() }
        }
    }
}
