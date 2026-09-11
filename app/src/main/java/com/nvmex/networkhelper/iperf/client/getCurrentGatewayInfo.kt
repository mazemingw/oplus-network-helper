package com.nvmex.networkhelper.iperf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import java.net.Inet4Address
import java.net.Inet6Address

//取网关地址
data class GatewayInfo(
    val isWifi: Boolean,
    val gatewayV4: String?,
    val gatewayV6: String?
) {
    val best: String? get() = gatewayV4 ?: gatewayV6
}

fun getCurrentGatewayInfo(ctx: Context): GatewayInfo {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)

    val net = cm.activeNetwork ?: return GatewayInfo(false, null, null)
    val caps = cm.getNetworkCapabilities(net) ?: return GatewayInfo(false, null, null)
    val lp = cm.getLinkProperties(net) ?: return GatewayInfo(false, null, null)

    val isWifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)

    val gateways = lp.routes
        .asSequence()
        .filter { it.isDefaultRoute }
        .mapNotNull { it.gateway }
        .toList()

    val v4 = gateways.firstOrNull { it is Inet4Address }?.hostAddress
    val v6 = gateways.firstOrNull { it is Inet6Address }?.hostAddress

    return GatewayInfo(isWifi, v4, v6)
}
