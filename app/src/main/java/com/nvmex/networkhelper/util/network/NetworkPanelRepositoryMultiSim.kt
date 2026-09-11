package com.nvmex.networkhelper.util.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.*
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class CellularLinkRate(
    val downstreamKbps: Int?,
    val upstreamKbps: Int?
) {
    fun hasAnyValue(): Boolean = downstreamKbps != null || upstreamKbps != null
}

data class DisplayInfoSnapshot(
    val networkType: Int,
    val overrideNetworkType: Int
)

class NetworkPanelRepositoryMultiSim(
    private val context: Context,
    private val snapshotter: TelephonySnapshotterMultiSim
) {
    private val tm = context.getSystemService(TelephonyManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun observeSims(): Flow<List<SubscriptionInfo>> = flow {
        emit(snapshotter.getActiveSims())
    }

    @SuppressLint("MissingPermission")
    fun observeStates(): Flow<Map<Int, NetworkPanelUiState>> = callbackFlow {

        val latestCellInfos = ConcurrentHashMap<Int, List<CellInfo>>()      // subId -> cellInfos
        val latestServiceState = ConcurrentHashMap<Int, ServiceState>()     // subId -> ss
        val latestSignal = ConcurrentHashMap<Int, SignalStrength>()         // subId -> signalStrength
        val latestDisplayInfo = ConcurrentHashMap<Int, DisplayInfoSnapshot>() // subId -> TelephonyDisplayInfo
        val latestLinkRate = ConcurrentHashMap<Int, CellularLinkRate>()     // subId -> cellular link rate
        val networkRates = ConcurrentHashMap<Network, CellularLinkRate>()
        val networkSubIds = ConcurrentHashMap<Network, Set<Int>>()

        fun NetworkCapabilities.toCellularLinkRateOrNull(): CellularLinkRate? {
            if (!hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null

            val down = getLinkDownstreamBandwidthKbps().takeIf { it > 0 }
            val up = getLinkUpstreamBandwidthKbps().takeIf { it > 0 }
            return CellularLinkRate(
                downstreamKbps = down,
                upstreamKbps = up
            ).takeIf { it.hasAnyValue() }
        }

        fun NetworkCapabilities.subIdsCompat(): Set<Int> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ids = subscriptionIds
                    .filter { SubscriptionManager.isValidSubscriptionId(it) }
                    .toSet()
                if (ids.isNotEmpty()) return ids
            }

            val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            return if (SubscriptionManager.isValidSubscriptionId(defaultDataSubId)) {
                setOf(defaultDataSubId)
            } else {
                emptySet()
            }
        }

        fun rebuildLinkRateCache() {
            latestLinkRate.clear()
            networkRates.forEach { (network, rate) ->
                networkSubIds[network].orEmpty().forEach { subId ->
                    latestLinkRate[subId] = rate
                }
            }
        }

        fun updateLinkRate(network: Network, capabilities: NetworkCapabilities) {
            val rate = capabilities.toCellularLinkRateOrNull()
            val subIds = capabilities.subIdsCompat()
            if (rate == null || subIds.isEmpty()) {
                networkRates.remove(network)
                networkSubIds.remove(network)
            } else {
                networkRates[network] = rate
                networkSubIds[network] = subIds
            }
            rebuildLinkRateCache()
        }

        fun pullCurrentCellularLinkRates() {
            runCatching {
                cm.allNetworks.forEach { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@forEach
                    updateLinkRate(network, caps)
                }
            }
        }

        fun emitAll(reason: String = "") {
            val sims = snapshotter.getActiveSims()
            val map = sims.associate { info ->
                val subId = info.subscriptionId
                subId to snapshotter.snapshotForSubId(
                    subId = subId,
                    cellInfosOverride = latestCellInfos[subId],
                    serviceStateOverride = latestServiceState[subId],
                    signalStrengthOverride = latestSignal[subId],
                    linkRateOverride = latestLinkRate[subId],
                    displayInfoOverride = latestDisplayInfo[subId],
                )
            }

            trySend(map)
        }

        val linkRateCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                updateLinkRate(network, networkCapabilities)
                emitAll("linkRate")
            }

            override fun onLost(network: Network) {
                networkRates.remove(network)
                networkSubIds.remove(network)
                rebuildLinkRateCache()
                emitAll("linkLost")
            }
        }

        var linkRateCallbackRegistered = false
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            cm.registerNetworkCallback(request, linkRateCallback)
            linkRateCallbackRegistered = true
            pullCurrentCellularLinkRates()
        }

        fun unregisterLinkRateCallback() {
            if (!linkRateCallbackRegistered) return
            runCatching { cm.unregisterNetworkCallback(linkRateCallback) }
            linkRateCallbackRegistered = false
        }

        // 1) 冷启动先发一次（允许空/unknown）
        emitAll("cold")

        // 2) 心跳兜底（只是触发 snapshot；真正能否推动 UI，要看 updatedAt 是否变化）
        // 2) 心跳兜底 + 主动拉取（节流）
        var lastPullAt = 0L
        val pullIntervalMs = 1000L // 1s 拉一次，够用且不至于太耗

        val tickJob: Job = launch {
            while (isActive) {
                delay(1000)

                // Some ROMs update cellular bandwidth estimates internally without dispatching a
                // fresh NetworkCallback. Poll the current capabilities with the existing 1s tick.
                pullCurrentCellularLinkRates()

                // 先正常 tick 刷一下（哪怕只是 updatedAt 变化）
                emitAll("tick")

                // ✅ Android 10(Q) 起可用：主动要一次 CellInfo（ROM 不给回调就硬要）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val now = System.currentTimeMillis()
                    if (now - lastPullAt >= pullIntervalMs) {
                        lastPullAt = now

                        val sims = snapshotter.getActiveSims()
                        sims.forEach { info ->
                            val subId = info.subscriptionId
                            val tmSub = tm.createForSubscriptionId(subId)

                            runCatching {
                                tmSub.requestCellInfoUpdate(
                                    context.mainExecutor,
                                    object : TelephonyManager.CellInfoCallback() {
                                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                            latestCellInfos[subId] = cellInfo.toList()
                                            emitAll("pullCellInfo[$subId]")
                                        }

                                        override fun onError(errorCode: Int, detail: Throwable?) {
                                            // 你想看作妖就打印一下
//                                            Log.w("NPemitAll", "pullCellInfo[$subId] error=$errorCode detail=$detail")
                                        }
                                    }
                                )
                            }.onFailure {
//                                Log.w("NPemitAll", "pullCellInfo[$subId] failed: $it")
                            }
                        }
                    }
                }
            }
        }


        val callbacks = mutableListOf<Any>()
        val subTelephonyManagers = mutableListOf<TelephonyManager>()

        val simsNow = snapshotter.getActiveSims()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            simsNow.forEach { info ->
                val subId = info.subscriptionId
                val tmSub = tm.createForSubscriptionId(subId)
                subTelephonyManagers += tmSub

                val cb = object : TelephonyCallback(),
                    TelephonyCallback.CellInfoListener,
                    TelephonyCallback.ServiceStateListener,
                    TelephonyCallback.DisplayInfoListener,
                    TelephonyCallback.SignalStrengthsListener {

                    override fun onCellInfoChanged(cellInfo: List<CellInfo>) {
                        latestCellInfos[subId] = cellInfo
                        emitAll("cellInfo[$subId]")
                    }

                    override fun onServiceStateChanged(serviceState: ServiceState) {
                        latestServiceState[subId] = serviceState
                        emitAll("ss[$subId]")
                    }

                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                        latestSignal[subId] = signalStrength
                        emitAll("sig[$subId]")
                    }

                    override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                        latestDisplayInfo[subId] = DisplayInfoSnapshot(
                            networkType = info.networkType,
                            overrideNetworkType = info.overrideNetworkType
                        )
                        emitAll("display[$subId]")
                    }
                }

                callbacks += cb
                tmSub.registerTelephonyCallback(context.mainExecutor, cb)
            }

            awaitClose {
                tickJob.cancel()
                unregisterLinkRateCallback()
                callbacks.forEachIndexed { idx, any ->
                    val cb = any as? TelephonyCallback ?: return@forEachIndexed
                    val t = subTelephonyManagers.getOrNull(idx) ?: return@forEachIndexed
                    runCatching { t.unregisterTelephonyCallback(cb) }
                }
            }
        } else {
            simsNow.forEach { info ->
                val subId = info.subscriptionId
                val tmSub = tm.createForSubscriptionId(subId)
                subTelephonyManagers += tmSub

                val l = object : PhoneStateListener() {

                    override fun onServiceStateChanged(serviceState: ServiceState?) {
                        if (serviceState != null) latestServiceState[subId] = serviceState
                        emitAll("ss[$subId]")
                    }

                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                        if (cellInfo != null) latestCellInfos[subId] = cellInfo.toList()
                        emitAll("cellInfo[$subId]")
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                        if (signalStrength != null) latestSignal[subId] = signalStrength
                        emitAll("sig[$subId]")
                    }

                    override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                        emitAll("data[$subId]")
                    }
                }

                callbacks += l
                tmSub.listen(
                    l,
                    PhoneStateListener.LISTEN_SERVICE_STATE or
                            PhoneStateListener.LISTEN_CELL_INFO or
                            PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                            PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                )
            }

            awaitClose {
                tickJob.cancel()
                unregisterLinkRateCallback()
                callbacks.forEachIndexed { idx, any ->
                    val l = any as? PhoneStateListener ?: return@forEachIndexed
                    val t = subTelephonyManagers.getOrNull(idx) ?: return@forEachIndexed
                    runCatching { t.listen(l, PhoneStateListener.LISTEN_NONE) }
                }
            }
        }

    }.buffer(Channel.CONFLATED)
}
