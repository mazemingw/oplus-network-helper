package com.nvmex.networkhelper.model.wifi

data class IwStationStats(
    val stationMac: String? = null,
    val rssiDbm: Int? = null,
    val rssiAvgDbm: Int? = null,

    val txRetriesTotal: Long? = null,
    val txFailedTotal: Long? = null, // iw 里是 tx failed
    val txPackets: Long? = null,
    val rxPackets: Long? = null,
    val txBytes: Long? = null,
    val rxBytes: Long? = null,

    val txBitrateMbps: Double? = null,
    val rxBitrateMbps: Double? = null
)

object RootIwParser {

    fun parseStationDump(text: String): IwStationStats? {
        // 只解析第一段 Station（一般就一段）
        val lines = text.lineSequence().map { it.trim() }.toList()
        if (lines.isEmpty()) return null

        var mac: String? = null
        var rssi: Int? = null
        var rssiAvg: Int? = null

        var txRetries: Long? = null
        var txFailed: Long? = null
        var txPackets: Long? = null
        var rxPackets: Long? = null
        var txBytes: Long? = null
        var rxBytes: Long? = null

        var txRate: Double? = null
        var rxRate: Double? = null

        for (ln in lines) {
            // Station xx:xx:.. (on wlan0)
            if (ln.startsWith("Station ", ignoreCase = true)) {
                val parts = ln.split(" ", "(", limit = 3)
                mac = parts.getOrNull(1)
                continue
            }

            // signal: -37 dBm
            if (ln.startsWith("signal:", ignoreCase = true)) {
                rssi = ln.substringAfter("signal:", "").trim().split(" ").firstOrNull()?.toIntOrNull()
                continue
            }

            // signal avg: -38 [-38, -38] dBm
            if (ln.startsWith("signal avg:", ignoreCase = true)) {
                rssiAvg = ln.substringAfter("signal avg:", "").trim().split(" ").firstOrNull()?.toIntOrNull()
                continue
            }

            // tx retries: 160
            if (ln.startsWith("tx retries:", ignoreCase = true)) {
                txRetries = ln.substringAfter("tx retries:", "").trim().toLongOrNull()
                continue
            }

            // tx failed: 109
            if (ln.startsWith("tx failed:", ignoreCase = true)) {
                txFailed = ln.substringAfter("tx failed:", "").trim().toLongOrNull()
                continue
            }

            // tx packets: 652
            if (ln.startsWith("tx packets:", ignoreCase = true)) {
                txPackets = ln.substringAfter("tx packets:", "").trim().toLongOrNull()
                continue
            }

            // rx packets: 744
            if (ln.startsWith("rx packets:", ignoreCase = true)) {
                rxPackets = ln.substringAfter("rx packets:", "").trim().toLongOrNull()
                continue
            }

            // tx bytes: 146739
            if (ln.startsWith("tx bytes:", ignoreCase = true)) {
                txBytes = ln.substringAfter("tx bytes:", "").trim().toLongOrNull()
                continue
            }

            // rx bytes: 204268
            if (ln.startsWith("rx bytes:", ignoreCase = true)) {
                rxBytes = ln.substringAfter("rx bytes:", "").trim().toLongOrNull()
                continue
            }

            // tx bitrate: 2161.3 MBit/s ...
            if (ln.startsWith("tx bitrate:", ignoreCase = true)) {
                txRate = parseBitrateMbps(ln.substringAfter("tx bitrate:", ""))
                continue
            }

            // rx bitrate: 2401.9 MBit/s ...
            if (ln.startsWith("rx bitrate:", ignoreCase = true)) {
                rxRate = parseBitrateMbps(ln.substringAfter("rx bitrate:", ""))
                continue
            }
        }

        // 没有核心字段就认为失败
        if (mac == null && rssi == null && txRetries == null && txFailed == null) return null

        return IwStationStats(
            stationMac = mac,
            rssiDbm = rssi,
            rssiAvgDbm = rssiAvg,
            txRetriesTotal = txRetries,
            txFailedTotal = txFailed,
            txPackets = txPackets,
            rxPackets = rxPackets,
            txBytes = txBytes,
            rxBytes = rxBytes,
            txBitrateMbps = txRate,
            rxBitrateMbps = rxRate
        )
    }

    private fun parseBitrateMbps(s: String): Double? {
        // "2161.3 MBit/s 160MHz HE-MCS ..."
        val t = s.trim()
        val num = t.split(" ").firstOrNull()?.toDoubleOrNull() ?: return null
        // iw 用的是 MBit/s，本质就是 Mbps
        return num
    }
}
