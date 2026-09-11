package com.nvmex.networkhelper.util.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object PingGatewayUtil {

    data class GatewayTarget(
        val ip: String,
        val iface: String?,     // e.g. wlan0 / rmnet_data0
        val isIpv6: Boolean
    ) {
        val displayText: String
            get() = if (!iface.isNullOrBlank() && isLinkLocalIpv6(ip)) "$ip ($iface)" else ip
    }

    /**
     * 获取默认网关：
     * - 优先 IPv4（最省事、ping 最稳定）
     * - 否则 IPv6（可能是 fe80::/10 link-local）
     * 同时返回 interfaceName（用于 fe80 ping 指定出口）
     */
    fun getDefaultGatewayTarget(context: Context, wifiOnly: Boolean = false): GatewayTarget? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (wifiOnly && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return null

        val iface = lp.interfaceName

        // 1) 优先 IPv4 default route
        val gw4 = lp.routes
            .firstOrNull { r: RouteInfo ->
                r.isDefaultRoute && r.gateway != null && isIpv4(r.gateway?.hostAddress)
            }
            ?.gateway
            ?.hostAddress

        if (!gw4.isNullOrBlank()) {
            return GatewayTarget(ip = gw4, iface = iface, isIpv6 = false)
        }

        // 2) 回退 IPv6 default route
        val gw6 = lp.routes
            .firstOrNull { r: RouteInfo ->
                r.isDefaultRoute && r.gateway != null && isIpv6(r.gateway?.hostAddress)
            }
            ?.gateway
            ?.hostAddress

        if (!gw6.isNullOrBlank()) {
            return GatewayTarget(ip = gw6, iface = iface, isIpv6 = true)
        }

        return null
    }

    /**
     * 兼容 IPv4 / IPv6 / fe80 link-local 的单次 ping
     */
    suspend fun pingOnce(target: GatewayTarget, timeoutSec: Int = 1): Double? = withContext(Dispatchers.IO) {
        val cmdCandidates = buildPingCommands(target, timeoutSec)

        // 依次尝试命令，成功解析到 time=xx ms 就返回
        for (cmd in cmdCandidates) {
            val output = runShell(cmd) ?: continue
            val rtt = parseRttMs(output)
            if (rtt != null) return@withContext rtt
        }

        null
    }

    // -----------------------
    // internal helpers
    // -----------------------

    private fun buildPingCommands(target: GatewayTarget, timeoutSec: Int): List<String> {
        val ip = target.ip
        val iface = target.iface

        return if (!target.isIpv6) {
            // IPv4
            listOf(
                "ping -c 1 -W $timeoutSec $ip"
            )
        } else {
            val isLl = isLinkLocalIpv6(ip)
            val ifArg = if (isLl && !iface.isNullOrBlank()) "-I $iface " else ""

            // 先尝试 ping -6，再尝试 ping6（某些 ROM 只有 ping6）
            listOf(
                "ping -6 -c 1 -W $timeoutSec $ifArg$ip",
                "ping6 -c 1 -W $timeoutSec $ifArg$ip"
            )
        }
    }

    private fun runShell(cmd: String): String? {
        val p = runCatching {
            ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null

        val out = StringBuilder()
        BufferedReader(InputStreamReader(p.inputStream)).use { br ->
            var line: String?
            while (br.readLine().also { line = it } != null) {
                out.appendLine(line)
            }
        }
        runCatching { p.waitFor() }.getOrNull()

        return out.toString()
    }

    private fun parseRttMs(text: String): Double? {
        // time=12.3 ms 或 time<1 ms
        val m = Regex("""time[=<]\s*([\d.]+)\s*ms""").find(text) ?: return null
        return m.groupValues.getOrNull(1)?.toDoubleOrNull()
    }

    private fun isIpv4(addr: String?): Boolean = !addr.isNullOrBlank() && addr.contains('.')
    private fun isIpv6(addr: String?): Boolean = !addr.isNullOrBlank() && addr.contains(':')

    private fun isLinkLocalIpv6(addr: String): Boolean {
        // fe80::/10，最常见就是 fe80: 开头
        return addr.startsWith("fe80", ignoreCase = true)
    }
}
