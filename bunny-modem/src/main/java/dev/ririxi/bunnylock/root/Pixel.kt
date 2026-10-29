package dev.ririxi.bunnylock.root

import dev.ririxi.bunnylock.core.Bands
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tensor (Shannon modem) band locking via the NV items Google's ACTS power tests use.
 * Changes apply after a radio restart.
 */
object Pixel {
    const val NODE = "/dev/umts_router"

    private const val LTE_ENABLE = "!SAEL3.Manual.Band.Select Enb/ Dis"
    private const val LTE_BITMAP = "!SAEL3.Manual.Enabled.RFBands.BitMap"
    private const val NR_ENABLE = "!NRRRC.SIM_BASED_BAND_LIST_SUPPORT"
    private const val NR_LIST = "!NRRRC.SIM_OPERATOR_BAND_LIST"

    private const val NV_TIMEOUT_MS = 5000

    // The LTE band mask is stored as four NV entries of eight bytes each.
    private const val LTE_BITMAP_ENTRIES = 4
    private const val LTE_BITMAP_ENTRY_BYTES = 8
    private const val LTE_MASK_BYTES = LTE_BITMAP_ENTRIES * LTE_BITMAP_ENTRY_BYTES

    fun available() = At.exists(NODE)

    private fun nrModeItem(sim: Int) = if (sim == 2) "DS.NR.CONFIG.MODE" else "NR.CONFIG.MODE"

    /** index -> hex string without separators. */
    fun getNv(name: String): Map<Int, String> {
        val reply = At.send(NODE, "AT+GOOGGETNV=\"$name\"", NV_TIMEOUT_MS)
        // Each entry reads "<name>",<index>,"<hex bytes>", with the bytes separated by commas or spaces.
        val entry = Regex("\"" + Regex.escape(name) + "\",\\s*(\\d+),\\s*\"([^\"]*)\"")
        return entry.findAll(reply).associate { match ->
            val (index, hexBytes) = match.destructured
            index.toInt() to hexBytes.replace(",", "").replace(" ", "")
        }
    }

    fun setNv(
        name: String,
        index: Int,
        bytes: ByteArray,
    ) {
        val hexBytes = bytes.joinToString(",") { "%02X".format(it) }
        At.ok(NODE, "AT+GOOGSETNV=\"$name\",$index,\"$hexBytes\"", NV_TIMEOUT_MS)
    }

    private fun nvFlag(name: String): Boolean = getNv(name)[0]?.toIntOrNull(16) == 1

    fun state(sim: Int): JSONObject {
        val out = JSONObject()
        runCatching {
            val enabled = nvFlag(LTE_ENABLE)
            val mask = ByteArray(LTE_MASK_BYTES)
            for ((index, hexBytes) in getNv(LTE_BITMAP)) {
                val byteCount = minOf(LTE_BITMAP_ENTRY_BYTES, hexBytes.length / 2)
                if (index in 0 until LTE_BITMAP_ENTRIES) hexBytes.unhex().copyInto(mask, index * LTE_BITMAP_ENTRY_BYTES, 0, byteCount)
            }
            out.put("lteLocked", enabled).put("lte", JSONArray(Bands.fromMask(mask)))
        }.onFailure { out.put("lteError", it.message) }
        runCatching {
            val enabled = nvFlag(NR_ENABLE)
            // Each slot holds one band number as a little-endian u16, and 0 marks an unused slot.
            val bands =
                getNv(NR_LIST).toSortedMap().values.mapNotNull { hexBytes ->
                    if (hexBytes.length < 4) null else Reader(hexBytes.unhex()).u16().takeIf { it != 0 }
                }
            out.put("nrLocked", enabled).put("nr", JSONArray(bands)).put("nrSlots", getNv(NR_LIST).size)
        }.onFailure { out.put("nrError", it.message) }
        runCatching {
            getNv(nrModeItem(sim))[0]?.let { out.put("nrMode", it) }
        }
        return out
    }

    /** An empty list turns the lock off and leaves the stored bitmap as it is. */
    fun setLte(bands: List<Int>): JSONObject {
        if (bands.isEmpty()) {
            setNv(LTE_ENABLE, 0, byteArrayOf(0))
        } else {
            val mask = Bands.toMask(bands, LTE_MASK_BYTES)
            for (index in 0 until LTE_BITMAP_ENTRIES) {
                val start = index * LTE_BITMAP_ENTRY_BYTES
                setNv(LTE_BITMAP, index, mask.copyOfRange(start, start + LTE_BITMAP_ENTRY_BYTES))
            }
            setNv(LTE_ENABLE, 0, byteArrayOf(1))
        }
        return JSONObject().put("lte", JSONArray(bands))
    }

    /** An empty list turns the lock off and leaves the stored band list as it is. */
    fun setNr(bands: List<Int>): JSONObject {
        if (bands.isEmpty()) {
            setNv(NR_ENABLE, 0, byteArrayOf(0))
        } else {
            // Slots past the end of the new list are zeroed, so bands from a longer earlier list don't stay in.
            val slotCount = maxOf(getNv(NR_LIST).size, bands.size)
            for (slot in 0 until slotCount) setNv(NR_LIST, slot, LittleEndian.u16(bands.getOrNull(slot) ?: 0))
            setNv(NR_ENABLE, 0, byteArrayOf(1))
        }
        return JSONObject().put("nr", JSONArray(bands))
    }

    /** "11" SA+NSA, "01" NSA only, "10" SA only, "00" NR off. */
    fun setNrMode(
        sim: Int,
        value: String,
    ): JSONObject {
        setNv(nrModeItem(sim), 0, value.unhex())
        return JSONObject().put("nrMode", value)
    }
}
