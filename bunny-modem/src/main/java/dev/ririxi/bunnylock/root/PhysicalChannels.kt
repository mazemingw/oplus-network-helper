package dev.ririxi.bunnylock.root

import org.json.JSONArray
import org.json.JSONObject

object PhysicalChannels {
    private const val CONFIGS_PREFIX = "mPhysicalChannelConfigs="
    private const val PHONE_PREFIX = "Phone Id="

    // PhysicalChannelConfig's unknown values. Band and bandwidth use 0.
    // Some Samsung radios also leave the channel at 0. Channel 0 is a band edge and never a carrier's center.
    private const val CHANNEL_UNKNOWN = Int.MAX_VALUE.toLong()
    private const val PCI_UNKNOWN = -1

    fun report(slot: Int): JSONObject {
        var phone: Int? = null
        val configs =
            Sys.sh("dumpsys telephony.registry").lineSequence().map { it.trim() }.firstNotNullOfOrNull { line ->
                if (line.startsWith(PHONE_PREFIX)) phone = line.removePrefix(PHONE_PREFIX).toIntOrNull()
                line.removePrefix(CONFIGS_PREFIX).takeIf { line.startsWith(CONFIGS_PREFIX) && phone == slot }
            }
        val carriers = JSONArray()
        configs?.removeSurrounding("[{", "}]")?.split("}, {")?.forEach { config -> carrierOf(config)?.let(carriers::put) }
        return JSONObject().put("carriers", carriers)
    }

    private fun field(
        config: String,
        name: String,
    ): String? = Regex("""\b$name=([^,\[]*)""").find(config)?.groupValues?.get(1)

    private fun carrierOf(config: String): JSONObject? {
        val rat =
            when (field(config, "mNetworkType")) {
                "LTE", "LTE_CA" -> "LTE"
                "NR" -> "NR"
                else -> return null
            }
        val carrier =
            JSONObject()
                .put("rat", rat)
                .put("primary", field(config, "mConnectionStatus") == "PrimaryServing")
        field(config, "mDownlinkChannelNumber")?.toLongOrNull()?.takeIf { it in 1 until CHANNEL_UNKNOWN }?.let { carrier.put("channel", it) }
        field(config, "mPhysicalCellId")?.toIntOrNull()?.takeIf { it != PCI_UNKNOWN }?.let { carrier.put("pci", it) }
        field(config, "mBand")?.toIntOrNull()?.takeIf { it > 0 }?.let { carrier.put("band", it) }
        field(config, "mCellBandwidthDownlinkKhz")?.toIntOrNull()?.takeIf { it > 0 }?.let { carrier.put("bandwidth", it) }
        return carrier
    }
}
