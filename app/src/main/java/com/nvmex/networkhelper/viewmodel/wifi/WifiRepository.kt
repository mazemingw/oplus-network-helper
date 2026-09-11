package com.nvmex.networkhelper.viewmodel.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.nvmex.networkhelper.model.wifi.IwStationStats
import com.nvmex.networkhelper.model.wifi.RootIwParser
import com.nvmex.networkhelper.model.wifi.WifiChannelSample
import com.nvmex.networkhelper.model.wifi.WifiUiModel
import com.nvmex.networkhelper.util.shell.SuShellRunner
import com.nvmex.networkhelper.util.wifi.WifiNetUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val wifiManager: WifiManager,
    private val connectivityManager: ConnectivityManager,
    private val su: SuShellRunner
) {
    // 默认走“低扰动模式”：不主动扫，不跑 root 命令，避免时延抖动
    private val enableActiveScan = false
    private val enableRootEnhancement = false
    private val enableRootStationStats = true
    private val activeScanIntervalMs = 6_000L
    private var lastActiveScanRequestMs = 0L
    private val rootEnhanceIntervalMs = 12_000L
    private var lastRootEnhanceReadMs = 0L
    private var cachedRootByBssid: Map<String, RootApEnhancement> = emptyMap()
    private var cachedRootBusyByFreq: Map<Int, Int> = emptyMap()

    // 用于计算 rate/s
    private var lastTsMs: Long = 0L
    private var lastTxRetries: Long? = null
    private var lastTxBad: Long? = null

    private data class RootApEnhancement(
        val ant: String? = null,
        val txPwrDbm: Int? = null,
        val ueCount: Int? = null,
        val busyPercent: Int? = null,
        val beamforming: String? = null,
        val roaming: String? = null
    )

    private data class RootEnhancementSnapshot(
        val byBssid: Map<String, RootApEnhancement> = emptyMap(),
        val busyByFreqMhz: Map<Int, Int> = emptyMap()
    )

    fun snapshot(): WifiUiModel {
        maybeRequestActiveScan()

        // =========================
        // 1) Framework：WifiInfo / LinkProperties / DHCP
        // =========================
        val info: WifiInfo? = runCatching { wifiManager.connectionInfo }.getOrNull()

        val ssid = info?.ssid?.trim()
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            ?: "-"

        val bssid = info?.bssid?.trim()
            ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }

        val scanResults = runCatching { wifiManager.scanResults }.getOrNull().orEmpty()
        val rootEnhancements = if (enableRootEnhancement) {
            readRootApEnhancements(scanResults)
        } else {
            RootEnhancementSnapshot()
        }

        val channelWidthMhz = readChannelWidthMhzFromScanResults(
            scanResults = scanResults,
            bssid = bssid
        )
        val nearbyChannels = buildNearbyChannels(
            scanResults = scanResults,
            rootByBssid = rootEnhancements.byBssid,
            busyByFreqMhz = rootEnhancements.busyByFreqMhz
        )


        val rssiDbm = info?.rssi?.takeIf { it in -127..0 }

        val linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 }

        // 某些 SDK/ROM 会没有这些方法：统一用反射读取，取不到就 null
        val txSpeedMbps = reflectInt(info, "getTxLinkSpeedMbps")?.takeIf { it > 0 }
        val rxSpeedMbps = reflectInt(info, "getRxLinkSpeedMbps")?.takeIf { it > 0 }

        val frequencyMhz = info?.frequency?.takeIf { it > 0 }
        val channel = frequencyMhz?.let { WifiNetUtils.frequencyToChannel(it) }

        val securityType = runCatching { info?.currentSecurityType }.getOrNull()

        val lp = runCatching {
            val net = connectivityManager.activeNetwork ?: return@runCatching null
            connectivityManager.getLinkProperties(net)
        }.getOrNull()
        val activeNet = connectivityManager.activeNetwork
        val netCaps = activeNet?.let { connectivityManager.getNetworkCapabilities(it) }
        val isWifiConnected = netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val dns = lp?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

        val gateway = lp?.let { extractGateway(it) } ?: dhcpGateway()
        val localIp = lp?.let { extractIPv4(it) } ?: dhcpLocalIp()

        val standardLabel = buildStandardLabel(info, frequencyMhz, linkSpeedMbps)

        // =========================
        // 2) Hidden stats：getConnectionStatistics（可能全是 null）
        // =========================
        val hidden = WifiHiddenStatsCompat.readPacketStats(wifiManager)

        // =========================
        // 3) Root 增强：iw station dump（可选）
        //    - 有 su 且能执行成功：用它覆盖更可信的 rssi / tx/rx bitrate / retries/failed
        //    - 失败：完全不影响
        // =========================
        val iw = if (enableRootStationStats) readIwStationDumpOrNull() else null

        val finalRssi = iw?.rssiDbm ?: rssiDbm

        // iw 的 tx/rx bitrate 单位就是 Mbps（MBit/s），更接近真实链路
        val finalTxSpeed = iw?.txBitrateMbps?.toInt()?.takeIf { it > 0 } ?: txSpeedMbps
        val finalRxSpeed = iw?.rxBitrateMbps?.toInt()?.takeIf { it > 0 } ?: rxSpeedMbps

        // 计数优先：iw -> hidden
        // 注意：iw 叫 tx failed，比你之前的 txBad 更贴近“失败/丢包”，这里当作 bad 备用口径
        val finalTxRetriesTotal = iw?.txRetriesTotal ?: hidden.txRetries
        val finalTxBadTotal = iw?.txFailedTotal ?: hidden.txBad

        return WifiUiModel(
            isWifiConnected = isWifiConnected,
            ssid = ssid,
            bssid = bssid,
            channelWidthMhz = channelWidthMhz,
            rssiDbm = finalRssi,

            linkSpeedMbps = linkSpeedMbps,
            txSpeedMbps = finalTxSpeed,
            rxSpeedMbps = finalRxSpeed,

            frequencyMhz = frequencyMhz,
            channel = channel,
            standardLabel = standardLabel,

            gateway = gateway ?: "-",
            localIp = localIp ?: "-",
            dns = dns ?: "-",

            securityType = securityType,

            // 计数（可能为空）：先给 UI/VM 做备用
            txRetriesTotal = finalTxRetriesTotal,
            txBadTotal = finalTxBadTotal,
            txSuccessTotal = hidden.txSuccess,
            rxSuccessTotal = hidden.rxSuccess,

            nearbyChannels = nearbyChannels
        )
    }


    private fun readIwStationDumpOrNull(): IwStationStats? {
        return runCatching {
            if (!su.hasSu()) return@runCatching null
            val r = su.execSu("iw dev wlan0 station dump", timeoutMs = 1200)
            if (r.code != 0 || r.out.isBlank()) return@runCatching null
            RootIwParser.parseStationDump(r.out)
        }.getOrNull()
    }

    private fun reflectInt(target: Any?, methodName: String): Int? {
        if (target == null) return null
        return runCatching {
            val m = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?: return@runCatching null
            (m.invoke(target) as? Number)?.toInt()
        }.getOrNull()
    }


    data class WifiPacketStats(
        val txBad: Long? = null,
        val txRetries: Long? = null,
        val txSuccess: Long? = null,
        val rxSuccess: Long? = null
    )

    object WifiHiddenStatsCompat {

        fun readPacketStats(wifiManager: WifiManager): WifiPacketStats {
            return runCatching {
                // WifiManager#getConnectionStatistics()
                val m = wifiManager.javaClass.methods.firstOrNull { it.name == "getConnectionStatistics" }
                    ?: return WifiPacketStats()

                val statsObj = m.invoke(wifiManager) ?: return WifiPacketStats()

                fun longField(name: String): Long? = runCatching {
                    val f = statsObj.javaClass.getDeclaredField(name)
                    f.isAccessible = true
                    (f.get(statsObj) as? Number)?.toLong()
                }.getOrNull()

                WifiPacketStats(
                    txBad = longField("numTxBad"),
                    txRetries = longField("numTxRetries"),
                    txSuccess = longField("numTxSuccess"),
                    rxSuccess = longField("numRxSuccess")
                )
            }.getOrElse { WifiPacketStats() }
        }
    }


    private fun buildStandardLabel(info: WifiInfo?, freqMhz: Int?, linkSpeedMbps: Int?): String {
        val fromStandard = runCatching {
            if (info == null) return@runCatching null
            when (info.wifiStandard) {
                ScanResult.WIFI_STANDARD_11BE -> "BE"
                ScanResult.WIFI_STANDARD_11AX -> "AX"
                ScanResult.WIFI_STANDARD_11AC -> "AC"
                ScanResult.WIFI_STANDARD_11N -> "N"
                ScanResult.WIFI_STANDARD_LEGACY -> "LEGACY"
                ScanResult.WIFI_STANDARD_UNKNOWN -> null
                else -> null
            }
        }.getOrNull()
        return fromStandard ?: WifiNetUtils.guessWifiStandardLabel(freqMhz, linkSpeedMbps)
    }

    private fun readChannelWidthMhzFromScanResults(
        scanResults: List<ScanResult>,
        bssid: String?
    ): Int? {
        if (bssid.isNullOrBlank()) return null

        val sr = scanResults
            .firstOrNull { it.BSSID.equals(bssid, ignoreCase = true) }
            ?: return null

        return scanResultChannelWidthMhz(sr)
    }

    private fun maybeRequestActiveScan() {
        if (!enableActiveScan) return
        val now = System.currentTimeMillis()
        if (now - lastActiveScanRequestMs < activeScanIntervalMs) return
        lastActiveScanRequestMs = now
        runCatching { wifiManager.startScan() }
    }

    private fun buildNearbyChannels(
        scanResults: List<ScanResult>,
        rootByBssid: Map<String, RootApEnhancement>,
        busyByFreqMhz: Map<Int, Int>
    ): List<WifiChannelSample> {
        if (scanResults.isEmpty()) return emptyList()
        return scanResults.mapNotNull { sr ->
            val frequency = sr.frequency.takeIf { it > 0 } ?: return@mapNotNull null
            val channel = WifiNetUtils.frequencyToChannel(frequency) ?: return@mapNotNull null
            val level = sr.level.takeIf { it in -127..0 } ?: return@mapNotNull null
            val bssidKey = sr.BSSID?.trim()?.lowercase()
            val root = bssidKey?.let { rootByBssid[it] }
            val (ieUe, ieBusy) = parseBssLoadFromIe(sr)
            val ssid = sr.SSID
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "<hidden>"
            WifiChannelSample(
                ssid = ssid,
                bssid = sr.BSSID?.takeIf { it.isNotBlank() },
                frequencyMhz = frequency,
                channel = channel,
                levelDbm = level,
                channelWidthMhz = scanResultChannelWidthMhz(sr),
                wifiGeneration = scanResultWifiGeneration(sr),
                ant = root?.ant ?: "-",
                txPwrDbm = root?.txPwrDbm,
                ueCount = root?.ueCount ?: ieUe,
                busyPercent = root?.busyPercent ?: ieBusy ?: busyByFreqMhz[frequency],
                beamforming = root?.beamforming ?: "-",
                roaming = root?.roaming ?: "-"
            )
        }.distinctBy { it.bssid ?: "${it.ssid}|${it.frequencyMhz}|${it.levelDbm}" }
            .sortedByDescending { it.levelDbm }
    }

    private fun parseBssLoadFromIe(sr: ScanResult): Pair<Int?, Int?> {
        val ies = runCatching { sr.informationElements }.getOrNull() ?: return null to null
        val ie = ies.firstOrNull { it.id == 11 } ?: return null to null
        val bb = ie.bytes.asReadOnlyBuffer()
        if (bb.remaining() < 3) return null to null
        val b0 = bb.get(0).toInt() and 0xFF
        val b1 = bb.get(1).toInt() and 0xFF
        val busy255 = bb.get(2).toInt() and 0xFF
        val stationCount = (b1 shl 8) or b0
        val busyPercent = ((busy255 / 255f) * 100f).roundToInt().coerceIn(0, 100)
        return stationCount to busyPercent
    }

    private fun readRootApEnhancements(scanResults: List<ScanResult>): RootEnhancementSnapshot {
        val now = System.currentTimeMillis()
        val hasCache = cachedRootByBssid.isNotEmpty() || cachedRootBusyByFreq.isNotEmpty()
        if (hasCache && now - lastRootEnhanceReadMs < rootEnhanceIntervalMs) {
            return RootEnhancementSnapshot(cachedRootByBssid, cachedRootBusyByFreq)
        }
        if (!su.hasSu()) {
            return RootEnhancementSnapshot(cachedRootByBssid, cachedRootBusyByFreq)
        }

        val scanCmd = su.execSu("iw dev wlan0 scan", timeoutMs = 3200)
        val surveyCmd = su.execSu("iw dev wlan0 survey dump", timeoutMs = 1800)

        val fromScan = if (scanCmd.code == 0 && scanCmd.out.isNotBlank()) {
            parseRootScanOutput(scanCmd.out)
        } else emptyMap()
        val fromSurvey = if (surveyCmd.code == 0 && surveyCmd.out.isNotBlank()) {
            parseRootSurveyBusyByFreq(surveyCmd.out)
        } else emptyMap()

        // 对没有 BSSID 的数据做频点兜底，避免 root 输出不完整时全空
        if (fromScan.isEmpty() && scanResults.isNotEmpty()) {
            val freqBusyFallback = fromSurvey
            cachedRootByBssid = emptyMap()
            cachedRootBusyByFreq = freqBusyFallback
            lastRootEnhanceReadMs = now
            return RootEnhancementSnapshot(cachedRootByBssid, cachedRootBusyByFreq)
        }

        cachedRootByBssid = fromScan
        cachedRootBusyByFreq = fromSurvey
        lastRootEnhanceReadMs = now
        return RootEnhancementSnapshot(cachedRootByBssid, cachedRootBusyByFreq)
    }

    private fun parseRootScanOutput(text: String): Map<String, RootApEnhancement> {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, RootApEnhancement>()
        var currentBssid: String? = null
        val block = mutableListOf<String>()

        fun flush() {
            val bssid = currentBssid ?: return
            val parsed = parseRootBssBlock(block)
            result[bssid.lowercase()] = parsed
            block.clear()
        }

        val bssHeader = Regex("""^\s*BSS\s+([0-9a-fA-F:]{17})\b""")
        for (line in lines) {
            val m = bssHeader.find(line)
            if (m != null) {
                flush()
                currentBssid = m.groupValues[1]
            }
            if (currentBssid != null) block += line
        }
        flush()
        return result
    }

    private fun parseRootBssBlock(blockLines: List<String>): RootApEnhancement {
        if (blockLines.isEmpty()) return RootApEnhancement()
        val block = blockLines.joinToString("\n")

        val txPwrDbm = Regex("""(?im)\b(?:tx[\s-]*power|transmit power)\D*(-?\d+(?:\.\d+)?)\s*dBm""")
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.roundToInt()

        val ueCount = Regex("""(?im)station count:\s*(\d+)""")
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val busyPercent = Regex("""(?im)channel utilization:\s*(\d+)\s*/\s*255""")
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { ((it / 255f) * 100f).roundToInt().coerceIn(0, 100) }

        val ant = parseAntFromBlock(block)

        val beamforming = if (Regex("""(?i)\bbeamformer\b|\bbeamformee\b""").containsMatchIn(block)) {
            "Y"
        } else "-"

        val roamingFlags = buildList {
            if (Regex("""(?im)^\s*Mobility Domain:""").containsMatchIn(block)) add("r")
            if (Regex("""(?im)^\s*RM enabled capabilities:""").containsMatchIn(block) ||
                Regex("""(?i)\bNeighbor report\b""").containsMatchIn(block)
            ) add("k")
            if (Regex("""(?i)\bBSS Transition\b""").containsMatchIn(block)) add("v")
        }
        val roaming = roamingFlags.distinct().joinToString("/").ifBlank { "-" }

        return RootApEnhancement(
            ant = ant,
            txPwrDbm = txPwrDbm,
            ueCount = ueCount,
            busyPercent = busyPercent,
            beamforming = beamforming,
            roaming = roaming
        )
    }

    private fun parseAntFromBlock(block: String): String {
        val nss = Regex("""(?im)\bNSS\s*[:=]\s*(\d+)""")
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (nss != null && nss > 0) return "${nss}x${nss}"

        val maxStreams = Regex("""(?im)\b(\d+)\s+streams?\b""")
            .findAll(block)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
        if (maxStreams != null && maxStreams > 0) return "${maxStreams}x${maxStreams}"
        return "-"
    }

    private fun parseRootSurveyBusyByFreq(text: String): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        var freq: Int? = null
        var active: Long? = null
        var busy: Long? = null

        fun flush() {
            val f = freq ?: return
            val a = active
            val b = busy
            if (a != null && b != null && a > 0L) {
                val pct = ((b.toDouble() / a.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                out[f] = pct
            }
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val fm = Regex("""^frequency:\s*(\d+)""", RegexOption.IGNORE_CASE).find(line)
            if (fm != null) {
                flush()
                freq = fm.groupValues[1].toIntOrNull()
                active = null
                busy = null
                return@forEach
            }
            val am = Regex("""^channel active time:\s*([\d\s]+)""", RegexOption.IGNORE_CASE).find(line)
            if (am != null) {
                active = am.groupValues[1].filter { it.isDigit() }.toLongOrNull()
                return@forEach
            }
            val bm = Regex("""^channel busy time:\s*([\d\s]+)""", RegexOption.IGNORE_CASE).find(line)
            if (bm != null) {
                busy = bm.groupValues[1].filter { it.isDigit() }.toLongOrNull()
                return@forEach
            }
        }
        flush()
        return out
    }

    private fun scanResultWifiGeneration(sr: ScanResult): String {
        val std = runCatching { sr.wifiStandard }.getOrNull()
        return when (std) {
            ScanResult.WIFI_STANDARD_11BE -> "7"
            ScanResult.WIFI_STANDARD_11AX -> "6"
            ScanResult.WIFI_STANDARD_11AC -> "5"
            ScanResult.WIFI_STANDARD_11N -> "4"
            else -> "-"
        }
    }

    private fun scanResultChannelWidthMhz(sr: android.net.wifi.ScanResult): Int? {
        // 大多数机型能直接给出 20/40/80/160/80+80
        // 新一点的系统可能有 320；老系统没有就会走 null
        return when (sr.channelWidth) {
            android.net.wifi.ScanResult.CHANNEL_WIDTH_20MHZ -> 20
            android.net.wifi.ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            android.net.wifi.ScanResult.CHANNEL_WIDTH_160MHZ -> 160
            android.net.wifi.ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160 // 注意：这个常量名历史上就很怪
            else -> reflectChannelWidth320(sr) // 兼容 320（如果系统有）
        }
    }

    /**
     * 兼容：某些 Android 版本新增 320MHz 常量，但你的 compileSdk/依赖可能没有。
     * 这里用反射，拿不到就 null。
     */
    private fun reflectChannelWidth320(sr: android.net.wifi.ScanResult): Int? {
        return runCatching {
            val cls = android.net.wifi.ScanResult::class.java
            val f = cls.getField("CHANNEL_WIDTH_320MHZ")
            val v = (f.get(null) as? Int) ?: return@runCatching null
            if (sr.channelWidth == v) 320 else null
        }.getOrNull()
    }



    private fun extractGateway(lp: LinkProperties): String? {
        val route = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
        return route?.gateway?.hostAddress
    }

    private fun extractIPv4(lp: LinkProperties): String? {
        val addr = lp.linkAddresses.mapNotNull { it.address }.firstOrNull { it is Inet4Address }
        return addr?.hostAddress
    }

    private fun dhcpGateway(): String? {
        val dhcp = runCatching { wifiManager.dhcpInfo }.getOrNull() ?: return null
        if (dhcp.gateway == 0) return null
        return WifiNetUtils.intToIpv4(dhcp.gateway)
    }

    private fun dhcpLocalIp(): String? {
        val dhcp = runCatching { wifiManager.dhcpInfo }.getOrNull() ?: return null
        if (dhcp.ipAddress == 0) return null
        return WifiNetUtils.intToIpv4(dhcp.ipAddress)
    }
}
