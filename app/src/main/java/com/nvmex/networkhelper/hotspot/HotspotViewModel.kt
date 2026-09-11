package com.nvmex.networkhelper.hotspot

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.SoftApConfiguration
import android.os.Build
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import be.mygod.vpnhotspot.net.wifi.SoftApCapability
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat
import be.mygod.vpnhotspot.net.wifi.SoftApInfo
import be.mygod.vpnhotspot.net.wifi.WifiApManager
import be.mygod.vpnhotspot.net.wifi.WifiClient
import be.mygod.vpnhotspot.net.wifi.WifiSsidCompat
import be.mygod.vpnhotspot.root.RootManager
import be.mygod.vpnhotspot.root.WifiApCommands
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.utils.HostapdWidthCalibrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

enum class Phase { IDLE, STARTING, RUNNING, STOPPING }

class HotspotViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "HotspotVM"
    }

    /**
     * Wi-Fi tethering type：
     * - API 30+ 用 TetheringManager.TETHERING_WIFI
     * - 低版本用 ConnectivityManager.TETHERING_WIFI
     *
     * 不要硬编码 0（OEM 可能改、compat 可能走不同分支，导致 error=5 不兜底）。
     */
    private val wifiTetherType: Int
        get() = if (Build.VERSION.SDK_INT >= 30) {
            android.net.TetheringManager.TETHERING_WIFI
        } else {
            0 // ✅ 传统常量：TETHERING_WIFI = 0
        }


    private val ctx: Context = app.applicationContext


    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(HotspotUiState())
    val ui: kotlinx.coroutines.flow.StateFlow<HotspotUiState> = _ui

    private val mainExecutor: Executor = Executor { r ->
        if (Looper.myLooper() == Looper.getMainLooper()) r.run()
        else android.os.Handler(Looper.getMainLooper()).post(r)
    }

    private var observing = false
    // 不再使用 softApCallbackKey
    private var rootSoftApCallback: WifiApManager.SoftApCallbackCompat? = null

    //监听logcat
    private val hostapdCalibrator = HostapdWidthCalibrator(TAG)

    // ====== Tether 粘性广播（是否 tethered、接口列表）======
    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TetheringManagerCompat.ACTION_TETHER_STATE_CHANGED) return

            val tethered = intent.getStringArrayListExtra("tetherArray")?.toList().orEmpty()

            val hasWifi = tethered.any {
                it.contains("wlan", ignoreCase = true) ||
                        it.contains("ap", ignoreCase = true) ||
                        it.contains("swlan", ignoreCase = true)
            }

            val cur = _ui.value.phase
            val next = when {
                hasWifi -> Phase.RUNNING
                cur == Phase.STARTING -> Phase.STARTING   // 启动中别被空广播打回去
                cur == Phase.STOPPING -> Phase.STOPPING   // 停止中别乱跳
                else -> Phase.IDLE
            }

            _ui.value = _ui.value.copy(
                tetherIfaces = tethered,
                phase = next,
                lastEvent = "TETHER_STATE_CHANGED tethered=${tethered.joinToString()}"
            )
        }
    }


    fun startObserving() {
        if (observing) return
        observing = true

        runCatching {
            ctx.registerReceiver(
                tetherReceiver,
                IntentFilter(TetheringManagerCompat.ACTION_TETHER_STATE_CHANGED)
            )
        }.onFailure { e ->
            Log.w(TAG, "registerReceiver tether failed", e)
        }

        registerSoftApCallback()
        refreshConfigSnapshot()
        hostapdCalibrator.start(viewModelScope)
    }

    fun stopObserving() {
        if (!observing) return
        observing = false

        runCatching { ctx.unregisterReceiver(tetherReceiver) }.onFailure { /* ignore */ }
        unregisterSoftApCallback()
        hostapdCalibrator.stop()
    }

    // =========================
    // Start/Stop hotspot (tethering)
    // =========================

    fun startHotspot(showProvisioningUi: Boolean = false) {
        val type = wifiTetherType

        _ui.value = _ui.value.copy(
            phase = Phase.STARTING,
            lastError = null,
            lastEvent = "startHotspot click type=$type"
        )

        TetheringManagerCompat.startTethering(
            type,
            showProvisioningUi,
            object : TetheringManagerCompat.StartTetheringCallback {

                override fun onTetheringStarted() {
                    _ui.value = _ui.value.copy(
                        lastEvent = "startTethering started (await broadcast) type=$type"
                    )
                }

                override fun onTetheringFailed(error: Int?) {
                    val errText = startTetherErrorName(error)

                    _ui.value = _ui.value.copy(
                        phase = Phase.IDLE,
                        lastError = "startTethering failed: $errText (root fallback)",
                        lastEvent = "startTethering failed, fallback to root type=$type"
                    )

                    viewModelScope.launch(Dispatchers.IO) {
                        val ok = runCatching {
                            RootManager.use { server ->
                                if (Build.VERSION.SDK_INT >= 30) {
                                    // API30+：StartTethering(type, showUi) -> 返回 ParcelableInt? (null=成功)
                                    @Suppress("NewApi")
                                    val res = server.execute(be.mygod.vpnhotspot.root.StartTethering(type, false))
                                    // res: ParcelableInt?  (null means success)
                                    res == null
                                } else {
                                    // API29：StartTetheringLegacy(cacheDir, type, showUi) -> ParcelableBoolean
                                    @Suppress("DEPRECATION")
                                    val res = server.execute(
                                        be.mygod.vpnhotspot.root.StartTetheringLegacy(
                                            cacheDir = ctx.codeCacheDir,
                                            type = type,
                                            showProvisioningUi = false
                                        )
                                    )
                                    // ParcelableBoolean.value == true 表示成功（按库语义）
                                    res.value
                                }
                            }
                        }.getOrElse { e ->
                            Log.w(TAG, "root StartTethering failed", e)
                            false
                        }

                        withContext(Dispatchers.Main) {
                            _ui.value = _ui.value.copy(
                                phase = if (ok) Phase.STARTING else Phase.IDLE,
                                lastError = if (ok) null else "root StartTethering failed (see logcat)",
                                lastEvent = "root fallback result=$ok type=$type"
                            )
                        }
                    }
                }

                override fun onException(e: Exception) {
                    Log.w(TAG, "startTethering exception", e)
                    _ui.value = _ui.value.copy(
                        phase = Phase.IDLE,
                        lastError = "startTethering exception: ${e.javaClass.simpleName}: ${e.message ?: "<null>"}",
                        lastEvent = "startTethering exception type=$type (root fallback)"
                    )

                    // 异常也尝试 root（很多 ROM 会直接抛 SecurityException）
                    viewModelScope.launch(Dispatchers.IO) {
                        val ok = runCatching {
                            RootManager.use { server ->
                                if (Build.VERSION.SDK_INT >= 30) {
                                    @Suppress("NewApi")
                                    server.execute(be.mygod.vpnhotspot.root.StartTethering(type, false)) == null
                                } else {
                                    @Suppress("DEPRECATION")
                                    server.execute(
                                        be.mygod.vpnhotspot.root.StartTetheringLegacy(
                                            cacheDir = ctx.codeCacheDir,
                                            type = type,
                                            showProvisioningUi = false
                                        )
                                    ).value
                                }
                            }
                        }.getOrElse { false }

                        withContext(Dispatchers.Main) {
                            _ui.value = _ui.value.copy(
                                phase = if (ok) Phase.STARTING else Phase.IDLE,
                                lastEvent = "root fallback after exception result=$ok type=$type",
                                lastError = if (ok) null else _ui.value.lastError
                            )
                        }
                    }
                }
            }
        )
    }


    fun stopHotspot() {
        val type = wifiTetherType

        _ui.value = _ui.value.copy(
            phase = Phase.STOPPING,
            lastError = null,
            lastEvent = "stopHotspot click type=$type"
        )

        TetheringManagerCompat.stopTethering(
            type,
            object : TetheringManagerCompat.StopTetheringCallback {

                override fun onStopTetheringSucceeded() {
                    _ui.value = _ui.value.copy(
                        phase = Phase.IDLE,
                        lastError = null,
                        lastEvent = "stopTethering success (await broadcast) type=$type"
                    )
                }

                override fun onStopTetheringFailed(error: Int) {
                    _ui.value = _ui.value.copy(
                        lastError = "stopTethering failed: $error (root fallback)",
                        lastEvent = "stopTethering failed, fallback to root type=$type"
                    )

                    viewModelScope.launch(Dispatchers.IO) {
                        val ok = runCatching {
                            RootManager.use { server ->
                                if (Build.VERSION.SDK_INT >= 30) {
                                    // API30+：StopTethering(cacheDir, type) -> ParcelableInt? (null=成功)
                                    @Suppress("NewApi")
                                    val res = server.execute(
                                        be.mygod.vpnhotspot.root.StopTethering(
                                            cacheDir = ctx.codeCacheDir,
                                            type = type
                                        )
                                    )
                                    res == null
                                } else {
                                    // API29：StopTetheringLegacy(type) -> no result
                                    server.execute(be.mygod.vpnhotspot.root.StopTetheringLegacy(type))
                                    true
                                }
                            }
                        }.getOrElse { e ->
                            Log.w(TAG, "root StopTethering failed", e)
                            false
                        }

                        withContext(Dispatchers.Main) {
                            _ui.value = _ui.value.copy(
                                phase = Phase.IDLE,
                                lastError = if (ok) null else "root StopTethering failed (see logcat)",
                                lastEvent = "root stop result=$ok type=$type"
                            )
                        }
                    }
                }

                override fun onException(e: Exception) {
                    Log.w(TAG, "stopTethering exception", e)
                    _ui.value = _ui.value.copy(
                        phase = Phase.IDLE,
                        lastError = "stopTethering exception: ${e.javaClass.simpleName}: ${e.message ?: "<null>"}",
                        lastEvent = "stopTethering exception type=$type (root fallback)"
                    )

                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching {
                            RootManager.use { server ->
                                if (Build.VERSION.SDK_INT >= 30) {
                                    @Suppress("NewApi")
                                    server.execute(
                                        be.mygod.vpnhotspot.root.StopTethering(
                                            cacheDir = ctx.codeCacheDir,
                                            type = type
                                        )
                                    )
                                } else {
                                    server.execute(be.mygod.vpnhotspot.root.StopTetheringLegacy(type))
                                }
                            }
                        }
                    }
                }
            }
        )
    }


    fun restartHotspot() {
        viewModelScope.launch {
            stopHotspot()
            // 经验：给系统一点时间落地（避免某些 ROM stop->start race）
            kotlinx.coroutines.delay(600)
            startHotspot(false)
        }
    }

    // =========================
    // Apply config (system -> root fallback)
    // =========================

    private val PSC_6G = setOf(
        5, 21, 37, 53, 69, 85, 101, 117,
        133, 149, 165, 181, 197, 213, 229
    )

    /**
     * 6GHz：尽量不要 Auto(0)。优先 PSC（1,5,9,13,17,21,25,29,33...），其次取列表第一个可用信道。
     *
     * @param requested 用户选择的信道（0=Auto）
     * @param options VM 输出的 ChannelOption 列表（建议含 Auto）
     */
    private fun pick6gChannel(
        requested: Int,
        options: List<ChannelOption>
    ): Int {
        // 过滤出真实可用信道（排除 Auto）
        val channels = options
            .asSequence()
            .map { it.channel }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toList()

        if (channels.isEmpty()) return 0

        // 如果用户明确选了一个非 0 且存在于 capability，直接用
        if (requested > 0 && channels.contains(requested)) return requested

        // 用户选 Auto(0) 或选了一个不在 capability 的值：挑 PSC
        val psc = setOf(1, 5, 9, 13, 17, 21, 25, 29, 33, 37, 41, 45, 49, 53, 57, 61, 65, 69, 73)
        val pscHit = channels.firstOrNull { it in psc }
        if (pscHit != null) return pscHit

        // 最后兜底：取第一个
        return channels.first()
    }


    fun applyConfig(
        ssidText: String,
        passphrase: String,
        hidden: Boolean,
        band: Int,
        channel: Int,
        maxChannelBandwidth: Int = SoftApConfigurationCompat.CHANNEL_WIDTH_AUTO,
    ) {
        _ui.value = _ui.value.copy(
            configApplying = true,
            configApplyResult = null,
            lastError = null,
            lastEvent = "applyConfig click band=$band ch=$channel bw=$maxChannelBandwidth"
        )

        viewModelScope.launch {
            val ssidTrim = ssidText.trim()
            val pwd = passphrase.trim().takeIf { it.isNotEmpty() }

            // 6GHz 基本盘：必须有密码
            if (band == SoftApConfiguration.BAND_6GHZ && pwd == null) {
                _ui.value = _ui.value.copy(
                    configApplying = false,
                    lastError = ctx.getString(R.string.hotspot_error_6g_requires_password),
                    lastEvent = "applyConfig blocked: 6G requires WPA3"
                )
                return@launch
            }

            // 5GHz DFS 信道拦截（你说系统不支持 DFS）
            if (band == SoftApConfiguration.BAND_5GHZ && channel in 52..144) {
                _ui.value = _ui.value.copy(
                    configApplying = false,
                    lastError = ctx.getString(R.string.hotspot_error_dfs_channel, channel),
                    lastEvent = "applyConfig blocked: DFS channel=$channel"
                )
                return@launch
            }


            // 关键：6G 不要用 Auto channel，先从 capability 里挑 PSC
            val chFinal = if (band == SoftApConfiguration.BAND_6GHZ) {
                pick6gChannel(channel, _ui.value.capChannels6g)
            } else channel

            val cfgCompat = SoftApConfigurationCompat().apply {
                ssid = WifiSsidCompat.fromUtf8Text(ssidTrim, true)
                isHiddenSsid = hidden

                if (pwd == null) {
                    securityType = SoftApConfiguration.SECURITY_TYPE_OPEN
                    this.passphrase = null
                } else {
                    securityType = when (band) {
                        SoftApConfiguration.BAND_6GHZ -> {
                            // ✅强制纯 WPA3（很多 ROM 对 6G 不接受 transition）
                            SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                        }
                        else -> SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
                    }
                    this.passphrase = pwd
                }

                // ✅band/channel 写入
                setChannel(channel = chFinal, band = band)

                // ✅带宽写入（API33+ 才真正生效；但 VPNHotspot compat 会帮你尽量映射）
                if (Build.VERSION.SDK_INT >= 33) {
                    this.maxChannelBandwidth = maxChannelBandwidth
                }
            }

            // 组装 platform（用于日志与 root 写入）
            val platform = if (Build.VERSION.SDK_INT >= 30) cfgCompat.toPlatform() else null

            // 关键：普通 app 调 WifiManager.setSoftApConfiguration 绝大概率 SecurityException，所以最终以 root 为准
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 30) {
                        WifiApManager.setConfiguration(platform!!)
                    } else {
                        @Suppress("DEPRECATION")
                        WifiApManager.setConfiguration(cfgCompat.toWifiConfiguration())
                    }
                    true
                }.getOrElse { e ->
                    Log.w(TAG, "setConfiguration via system failed, fallback to root", e)
                    runCatching {
                        RootManager.use { server ->
                            if (Build.VERSION.SDK_INT >= 30) {
                                server.execute(WifiApCommands.SetConfiguration(platform!!))
                            } else {
                                @Suppress("DEPRECATION")
                                server.execute(WifiApCommands.SetConfigurationLegacy(cfgCompat.toWifiConfiguration()))
                            }
                        }
                        true
                    }.getOrElse { e2 ->
                        Log.w(TAG, "setConfiguration via root failed", e2)
                        false
                    }
                }
            }

            // 写完立刻读一次快照（你是 root 读，最准）
            refreshConfigSnapshot()

            // 读完快照后，顺便给一条“对账信息”：用户选的 vs 系统存下的
            val snap = _ui.value
            val bandTxt = when (band) {
                SoftApConfiguration.BAND_2GHZ -> "2.4G"
                SoftApConfiguration.BAND_5GHZ -> "5G"
                SoftApConfiguration.BAND_6GHZ -> "6G"
                SoftApConfiguration.BAND_60GHZ -> "60G"
                else -> "band=$band"
            }
            val bwTxt = ChannelWidthCompat.label(maxChannelBandwidth)

            val mismatchHint = buildString {
                // 如果系统把你 6G 清洗回 5G，这里会非常直观
                if (snap.configBand != null && snap.configBand != band) {
                    append(ctx.getString(R.string.hotspot_config_band_mismatch, band, snap.configBand))
                }
                if (band == SoftApConfiguration.BAND_6GHZ && snap.configChannel != null && snap.configChannel != chFinal) {
                    append(ctx.getString(R.string.hotspot_config_channel_mismatch, snap.configChannel, chFinal))
                }
            }

            _ui.value = _ui.value.copy(
                configApplying = false,
                configApplyResult = if (ok) {
                    ctx.getString(R.string.hotspot_config_apply_done, bandTxt, chFinal, bwTxt, mismatchHint)
                } else {
                    ctx.getString(R.string.hotspot_config_apply_failed)
                },
                lastEvent = "applyConfig result=$ok band=$band ch=$chFinal bw=$maxChannelBandwidth"
            )
        }
    }


    // =========================
    // Snapshot config (read system config)
    // =========================

    fun refreshConfigSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    val platform: SoftApConfiguration = RootManager.use { server ->
                        server.execute(WifiApCommands.GetConfiguration())
                    }

                    val compat = SoftApConfigurationCompat.Companion.run { platform.toCompat() }

                    val (b, ch) = runCatching {
                        SoftApConfigurationCompat.requireSingleBand(compat.channels)
                    }.getOrElse { null to null }

                    val bw = compat.maxChannelBandwidth // ✅不强行 gate 33；读取不到通常也是 AUTO/-1 或默认值

                    _ui.value = _ui.value.copy(
                        ssid = compat.ssid?.toString(),
                        passphrase = compat.passphrase,
                        configBand = b,
                        configChannel = ch,
                        configMaxBandwidth = bw,
                        lastEvent = "config snapshot refreshed (root) bw=${ChannelWidthCompat.label(bw)}"
                    )
                } else {
                    _ui.value = _ui.value.copy(lastEvent = "config snapshot skipped (<30)")
                }
            }.onFailure { e ->
                Log.w(TAG, "refreshConfigSnapshot(root) failed", e)
                _ui.value = _ui.value.copy(
                    lastError = "refreshConfigSnapshot(root) failed: ${e.javaClass.simpleName}: ${e.message ?: "<null>"}"
                )
            }
        }
    }


    // =========================
    // SoftAp callback: state/info/capability/clients
    // =========================

    private fun registerSoftApCallback() {
        if (rootSoftApCallback != null) return

        val cb = object : WifiApManager.SoftApCallbackCompat {

            override fun onStateChanged(state: Int, failureReason: Int) {
                val stateText = when (state) {
                    WifiApManager.WIFI_AP_STATE_DISABLED -> "DISABLED"
                    WifiApManager.WIFI_AP_STATE_DISABLING -> "DISABLING"
                    WifiApManager.WIFI_AP_STATE_ENABLED -> "ENABLED"
                    WifiApManager.WIFI_AP_STATE_ENABLING -> "ENABLING"
                    WifiApManager.WIFI_AP_STATE_FAILED -> "FAILED"
                    else -> "UNKNOWN($state)"
                }

                val reasonText = runCatching {
                    WifiApManager.failureReasonLookup(failureReason, trimPrefix = true)
                }.getOrElse { failureReason.toString() }

                _ui.value = _ui.value.copy(
                    apState = stateText,
                    apFailureReason = if (state == WifiApManager.WIFI_AP_STATE_FAILED) reasonText else null,
                    lastEvent = "SoftAp onStateChanged=$stateText reason=$reasonText"
                )
            }

            @RequiresApi(30)
            override fun onConnectedClientsChanged(clients: List<Parcelable>) {
                _ui.value = _ui.value.copy(
                    apClients = clients.size,
                    lastEvent = "SoftAp clients=${clients.size}"
                )
            }

            @RequiresApi(30)
            override fun onInfoChanged(info: List<Parcelable>) {
                if (info.isEmpty()) return
                val p0 = info[0]
                val first = SoftApInfo(p0)

                val freq = first.frequency
                val chan = runCatching { SoftApConfigurationCompat.frequencyToChannel(freq) }.getOrNull()

                val rawBw = runCatching { first.bandwidth }.getOrNull()
                val fwText = SoftApInfo.channelWidthLookup(rawBw ?: -1, trimPrefix = true)

                val std = if (Build.VERSION.SDK_INT >= 31) runCatching { first.wifiStandard }.getOrNull() else null

                // ===== ✅ hostapd 校准：只读缓存，不读 logcat =====
                val snap = hostapdCalibrator.snapshot()
                val now = System.currentTimeMillis()
                val realMhz = snap.widthMhz
                val fresh = (now - snap.ts) <= 3_000L // 5 秒内认为新鲜（你可调 3~10s）

                // 防误覆盖：只在“确实是热点接口 + 6G + 11be”时信 hostapd
                val is6g = freq in 5925..7125
                val isEht = (std == 8) // WIFI_STANDARD_11BE 通常为 8
                val allowOverride = fresh && is6g && isEht && (realMhz != null) && (realMhz >= 80)

                // 最终显示：允许覆盖就显示 hostapd 的真实值，否则用 framework 的口径
                val finalBwText = if (allowOverride) {
                    "${realMhz}MHz"
                } else {
                    fwText
                }

                // ===== ✅ 打点（保留你原来的 + 加上 hostapd 信息）=====
//                Log.i(
//                    TAG,
//                    "SoftApInfo onInfoChanged: p0=${p0.javaClass.name} " +
//                            "freq=$freq chan=$chan rawBw=$rawBw fwText=$fwText std=$std " +
//                            "hostapd=${realMhz}MHz fresh=$fresh allowOverride=$allowOverride"
//                )

                _ui.value = _ui.value.copy(
                    apBandText = bandFromFrequency(freq),
                    apFrequencyMhz = freq.takeIf { it != 0 },
                    apChannel = chan,
                    apBandwidth = finalBwText,
                    apWifiStandard = std,
                    lastEvent = buildString {
                        append("SoftApInfo freq=$freq chan=$chan ")
                        append("fw=$fwText rawBw=$rawBw std=$std ")
                        append("hostapd=${realMhz ?: "-"}MHz fresh=$fresh ")
                        if (allowOverride) append("=> use hostapd")
                    }
                )
            }



            @RequiresApi(30)
            override fun onCapabilityChanged(capability: Parcelable) {
                val cap = SoftApCapability(capability)

                val features = if (Build.VERSION.SDK_INT >= 31) cap.supportedFeatures else 0L
                val band24 = if (Build.VERSION.SDK_INT >= 31)
                    (features and SoftApCapability.SOFTAP_FEATURE_BAND_24G_SUPPORTED != 0L) else null
                val band5 = if (Build.VERSION.SDK_INT >= 31)
                    (features and SoftApCapability.SOFTAP_FEATURE_BAND_5G_SUPPORTED != 0L) else null
                val band6 = if (Build.VERSION.SDK_INT >= 31)
                    (features and SoftApCapability.SOFTAP_FEATURE_BAND_6G_SUPPORTED != 0L) else null
                val band60 = if (Build.VERSION.SDK_INT >= 31)
                    (features and SoftApCapability.SOFTAP_FEATURE_BAND_60G_SUPPORTED != 0L) else null

                // raw lists
                val raw2 = if (Build.VERSION.SDK_INT >= 31) runCatching {
                    cap.getSupportedChannelList(SoftApConfiguration.BAND_2GHZ).toList()
                }.getOrElse { emptyList() } else emptyList()

                val raw5 = if (Build.VERSION.SDK_INT >= 31) runCatching {
                    cap.getSupportedChannelList(SoftApConfiguration.BAND_5GHZ).toList()
                }.getOrElse { emptyList() } else emptyList()

                val raw6 = if (Build.VERSION.SDK_INT >= 31) runCatching {
                    cap.getSupportedChannelList(SoftApConfiguration.BAND_6GHZ).toList()
                }.getOrElse { emptyList() } else emptyList()

                val raw60 = if (Build.VERSION.SDK_INT >= 31) runCatching {
                    cap.getSupportedChannelList(SoftApConfiguration.BAND_60GHZ).toList()
                }.getOrElse { emptyList() } else emptyList()

                val cc = if (Build.VERSION.SDK_INT >= 31) cap.countryCode else null

                // ChannelOption lists
                val ch2 = buildChannelOptions(SoftApConfiguration.BAND_2GHZ, raw2, includeAuto = true, keepDfs = true)
                val ch5 = buildChannelOptions(SoftApConfiguration.BAND_5GHZ, raw5, includeAuto = true, keepDfs = true) // DFS 保留但标识
                val ch6 = buildChannelOptions(SoftApConfiguration.BAND_6GHZ, raw6, includeAuto = true, keepDfs = true)
                val ch60 = buildChannelOptions(SoftApConfiguration.BAND_60GHZ, raw60, includeAuto = true, keepDfs = true)

                _ui.value = _ui.value.copy(
                    capMaxClients = cap.maxSupportedClients,
                    capBand24 = band24,
                    capBand5 = band5,
                    capBand6 = band6,
                    capBand60 = band60,
                    capCountryCode = cc,
                    capChannels2g = ch2, //Argument type mismatch: actual type is 'List<ChannelOption>', but 'List<Int>' was expected.
                    capChannels5g = ch5,
                    capChannels6g = ch6,
                    capChannels60g = ch60,
                    lastEvent = "SoftApCapability max=${cap.maxSupportedClients} " +
                            "2g=${ch2.size} 5g=${ch5.size}(raw=${raw5.size}) 6g=${ch6.size}"
                )
            }


            @RequiresApi(30)
            override fun onBlockedClientConnecting(client: Parcelable, blockedReason: Int) {
                val reason = runCatching {
                    WifiApManager.clientBlockLookup(blockedReason, trimPrefix = true)
                }.getOrElse { blockedReason.toString() }

                val c = WifiClient(client)
                _ui.value = _ui.value.copy(
                    lastEvent = "BlockedClient ${c.macAddress} reason=$reason"
                )
            }
        }

        // 先保存，避免重复注册；失败再回滚
        rootSoftApCallback = cb

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // ✅ 关键：走 root server 注册 callback
                WifiApCommands.registerSoftApCallback(cb)
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    _ui.value = _ui.value.copy(
                        lastError = null,
                        lastEvent = "SoftApCallback registered (root)"
                    )
                }
            }.onFailure { e ->
                Log.w(TAG, "registerSoftApCallback(root) failed", e)
                val detail = buildString {
                    append(e.javaClass.name)
                    append(": ")
                    append(e.message ?: "<null>")
                    val c = e.cause
                    if (c != null) {
                        append(" | cause=")
                        append(c.javaClass.name)
                        append(": ")
                        append(c.message ?: "<null>")
                    }
                }
                withContext(Dispatchers.Main) {
                    _ui.value = _ui.value.copy(
                        lastError = "registerSoftApCallback(root) failed: $detail",
                        lastEvent = "SoftApCallback register failed"
                    )
                }
                // 失败回滚
                rootSoftApCallback = null
            }
        }
    }


    private fun unregisterSoftApCallback() {
        val cb = rootSoftApCallback ?: return
        rootSoftApCallback = null

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                WifiApCommands.unregisterSoftApCallback(cb)
            }.onFailure {
                Log.w(TAG, "unregisterSoftApCallback(root) failed", it)
            }
        }
    }


    private fun bandFromFrequency(freqMhz: Int): String? {
        if (freqMhz <= 0) return null
        return when {
            freqMhz in 2400..2500 -> "2.4G"
            freqMhz in 4900..5900 -> "5G"
            freqMhz in 5925..7125 -> "6G"
            freqMhz in 58000..71000 -> "60G"
            else -> "UNKNOWN"
        }
    }
    private fun startTetherErrorName(code: Int?): String {
        if (code == null) return "null"
        return when (code) {
            android.net.TetheringManager.TETHER_ERROR_SERVICE_UNAVAIL -> "SERVICE_UNAVAIL(2)"
            android.net.TetheringManager.TETHER_ERROR_INTERNAL_ERROR -> "INTERNAL_ERROR(5)"
            android.net.TetheringManager.TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION -> "NO_CHANGE_PERMISSION(14)"
            android.net.TetheringManager.TETHER_ERROR_UNKNOWN_TYPE -> "UNKNOWN_TYPE(16)"
            else -> "UNKNOWN($code)"
        }
    }

    private fun isDfs5g(channel: Int): Boolean = channel in 52..144

    private fun channelToFreqMhz(band: Int, channel: Int): Int? = when (band) {
        SoftApConfiguration.BAND_2GHZ -> when (channel) {
            14 -> 2484
            in 1..13 -> 2412 + (channel - 1) * 5
            else -> null
        }
        SoftApConfiguration.BAND_5GHZ -> if (channel > 0) 5000 + channel * 5 else null
        SoftApConfiguration.BAND_6GHZ -> if (channel > 0) 5950 + channel * 5 else null
        SoftApConfiguration.BAND_60GHZ -> null // 60G 频率映射你如果需要后续再补
        else -> null
    }

    /**
     * 生成可展示/可禁用的信道选项。
     *
     * @param includeAuto 是否在首位插入 Auto(0)
     * @param keepDfs 是否保留 DFS 信道（保留则标识并让 UI 禁用；不保留则直接过滤）
     */
    private fun buildChannelOptions(
        band: Int,
        rawChannels: List<Int>,
        includeAuto: Boolean = true,
        keepDfs: Boolean = true
    ): List<ChannelOption> {
        val base = rawChannels
            .asSequence()
            .filter { it > 0 }               // capability 通常不会给 0；这里也顺手清洗
            .distinct()
            .sorted()
            .map { ch ->
                val dfs = (band == SoftApConfiguration.BAND_5GHZ) && isDfs5g(ch)
                val freq = channelToFreqMhz(band, ch)

                // 展示文案：CH + 频率 + DFS 标记
                val text = buildString {
                    append("CH $ch")
                    if (freq != null) append(" · ${freq}MHz")
                    if (dfs) append(" · DFS")
                }

                ChannelOption(
                    channel = ch,
                    freqMhz = freq,
                    isDfs = dfs,
                    display = text
                )
            }
            .let { seq -> if (keepDfs) seq else seq.filterNot { it.isDfs } }
            .toList()

        // Auto 选项放到第一位
        return if (includeAuto) {
            listOf(
                ChannelOption(
                    channel = 0,
                    freqMhz = null,
                    isDfs = false,
                    display = "Auto"
                )
            ) + base
        } else base
    }




    override fun onCleared() {
        stopObserving()
        super.onCleared()
    }
}
