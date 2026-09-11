package com.nvmex.networkhelper.iperf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

//UI 自动刷新：用 NetworkCallback 监听变化
fun observeGatewayInfo(ctx: Context) = callbackFlow {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)

    fun emitNow() {
        trySend(getCurrentGatewayInfo(ctx))
    }

    val req = NetworkRequest.Builder().build()

    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = emitNow()
        override fun onLost(network: Network) = emitNow()
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = emitNow()
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = emitNow()
    }

    emitNow()
    cm.registerNetworkCallback(req, cb)

    awaitClose { cm.unregisterNetworkCallback(cb) }
}.distinctUntilChanged()
