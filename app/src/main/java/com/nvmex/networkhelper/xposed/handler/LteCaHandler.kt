package com.nvmex.networkhelper.xposed.handler

import android.os.SystemClock
import com.nvmex.networkhelper.xposed.broad.BroadcastHelper
import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class LteCaHandler(private val broadcastHelper: BroadcastHelper) {

    companion object {
        private const val FORCE_REEMIT_MS = 6_000L
        private const val SOURCE_PROTECT_MS = 4_000L

        private const val SOURCE_VENDOR = "vendor"
        private const val SOURCE_RADIO = "radio"
        private const val SOURCE_RIL = "ril"
    }

    private val lastSigBySlot = ConcurrentHashMap<Int, Long>()
    private val lastEmitAtBySlot = ConcurrentHashMap<Int, Long>()
    private val lastSourcePriorityBySlot = ConcurrentHashMap<Int, Int>()

    fun parseRadioLteCaInfo(slot: Int, radioLteCaInfo: Any) {
        val cells = runCatching { parseCellsFromRadioInfo(radioLteCaInfo) }
            .getOrElse {
                Logger.logE("[LTECA][HAL] parseRadioLteCaInfo failed", it)
                return
            }

        emitIfNeeded(slot, cells, SOURCE_RADIO)
    }

    fun parseVendorLteCaInfo(slot: Int, vendorLteCaInfo: Any?) {
        if (vendorLteCaInfo == null) return

        val cells = runCatching { parseCellsFromVendorInfo(vendorLteCaInfo) }
            .getOrElse {
                Logger.logE("[LTECA][HAL] parseVendorLteCaInfo failed", it)
                return
            }

        emitIfNeeded(slot, cells, SOURCE_VENDOR)
    }

    fun parseRilLteCaInfo(slot: Int, rilRet: IntArray) {
        val cells = runCatching { parseCellsFromRilArray(rilRet) }
            .getOrElse {
                Logger.logE("[LTECA][RIL] parseRilLteCaInfo failed", it)
                return
            }

        emitIfNeeded(slot, cells, SOURCE_RIL)
    }

    private fun emitIfNeeded(slot: Int, cells: List<LteCaCellRaw>, source: String) {
        if (slot < 0 || cells.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        val currentPriority = sourcePriority(source)
        val lastPriority = lastSourcePriorityBySlot[slot] ?: Int.MIN_VALUE
        val lastEmitAt = lastEmitAtBySlot[slot] ?: 0L

        if (currentPriority < lastPriority && (now - lastEmitAt) in 0 until SOURCE_PROTECT_MS) {
            Logger.log(
                "[LTECA][HAL] skip weaker source slot=$slot source=$source lastPriority=$lastPriority curPriority=$currentPriority"
            )
            return
        }

        val sig = buildSignature(cells)
        val lastSig = lastSigBySlot.put(slot, sig)
        if (lastSig != null && lastSig == sig) {
            val age = now - lastEmitAt
            if (age in 0 until FORCE_REEMIT_MS) return
            Logger.log("[LTECA][HAL] force re-emit slot=$slot age=${age}ms source=$source")
        }

        lastEmitAtBySlot[slot] = now
        lastSourcePriorityBySlot[slot] = currentPriority

        val json = buildJson(now, slot, cells, source)
        Logger.log("[LTECA][HAL] t=$now slot=$slot source=$source carriers=${cells.size}")
        broadcastHelper.emitLteCaToApp(now, slot, cells.size, json)
    }

    private fun parseCellsFromRilArray(rilRet: IntArray): List<LteCaCellRaw> {
        val len = rilRet.size
        if (len == 10) {
            // Legacy format: pcell + 1 scell
            val band = rilLegacyBandToDisplayBandNumber(rilRet[4])
            val bwText = rilBandwidthText(rilRet[7])
            return listOf(
                LteCaCellRaw(
                    idx = rilRet[8],
                    pci = rilRet[5],
                    dlEarfcn = rilRet[6],
                    ulEarfcn = -1,
                    band = band,
                    bandText = band?.let { "B$it" },
                    dlBwRaw = rilRet[7],
                    dlBwText = bwText,
                    scellState = rilRet[9],
                    scellStateText = rilScellStateToDisplay(rilRet[9]),
                    ulEnabled = null,
                    rsrp = null,
                    rsrq = null,
                    sinr = null
                )
            )
        }

        if (len in 21..54) {
            val out = ArrayList<LteCaCellRaw>()
            var i = 0
            while (((i + 1) * 7) + 5 <= len) {
                val base = i * 7
                val band = rilRet[base + 7] + 1
                val bwRaw = rilRet[base + 9]
                out += LteCaCellRaw(
                    idx = i,
                    pci = rilRet[base + 6],
                    dlEarfcn = rilRet[base + 8],
                    ulEarfcn = -1,
                    band = band,
                    bandText = "B$band",
                    dlBwRaw = bwRaw,
                    dlBwText = rilBandwidthText(bwRaw),
                    scellState = rilRet[base + 10],
                    scellStateText = rilScellStateToDisplay(rilRet[base + 10]),
                    ulEnabled = rilRet[base + 11],
                    rsrp = null,
                    rsrq = null,
                    sinr = null
                )
                i++
            }
            return out
        }

        Logger.log("[LTECA][RIL] unsupported rilRet length=$len")
        return emptyList()
    }

    private fun parseCellsFromRadioInfo(radioLteCaInfo: Any): List<LteCaCellRaw> {
        val listObj = getObjectFieldCompat(
            radioLteCaInfo,
            "lteCaScellInfoList",
            getObjectFieldCompat(radioLteCaInfo, "mLteCaScellInfoList", null)
        )

        val arr = (listObj as? Array<*>) ?: emptyArray<Any?>()
        return arr.mapNotNull { parseScellContainer(it) }
    }

    private fun parseCellsFromVendorInfo(vendorLteCaInfo: Any): List<LteCaCellRaw> {
        val listObj = getObjectFieldCompat(
            vendorLteCaInfo,
            "scell_info_list",
            getObjectFieldCompat(vendorLteCaInfo, "scellInfoList", null)
        )

        val arr = (listObj as? Array<*>) ?: emptyArray<Any?>()
        return arr.mapNotNull { parseScellContainer(it) }
    }

    private fun parseScellContainer(container: Any?): LteCaCellRaw? {
        if (container == null) return null

        val idx = getIntFieldCompat(
            container,
            "scellIdx",
            getIntFieldCompat(container, "mScellIdx", getIntFieldCompat(container, "scell_idx", -1))
        )

        val info = getObjectFieldCompat(
            container,
            "scellInfo",
            getObjectFieldCompat(container, "mScellInfo", getObjectFieldCompat(container, "scell_info", container))
        ) ?: return null

        val pci = firstIntField(info, listOf("pci", "scc_phy_cellid", "sccPhyCellId", "phyCellId"), -1)
        val dlEarfcn = firstIntField(info, listOf("dlChannel", "scc_dl_channel", "sccDlChannel", "dlEarfcn", "earfcnDl"), -1)
        val ulEarfcn = firstIntField(info, listOf("ulChannel", "scc_ul_channel", "sccUlChannel", "ulEarfcn", "earfcnUl"), -1)

        val rsrp = sanitizeSignal(firstIntField(info, listOf("rsrp"), Int.MIN_VALUE))
        val rsrq = sanitizeSignal(firstIntField(info, listOf("rsrq"), Int.MIN_VALUE))
        val sinr = sanitizeSignal(firstIntField(info, listOf("sinr", "rs_sinr", "rsSinr"), Int.MIN_VALUE))

        return LteCaCellRaw(
            idx = idx,
            pci = pci,
            dlEarfcn = dlEarfcn,
            ulEarfcn = ulEarfcn,
            band = null,
            bandText = null,
            dlBwRaw = null,
            dlBwText = null,
            scellState = null,
            scellStateText = null,
            ulEnabled = null,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr
        )
    }

    private fun sourcePriority(source: String): Int = when (source) {
        SOURCE_RIL -> 3
        SOURCE_RADIO -> 2
        else -> 1
    }

    private fun sanitizeSignal(v: Int): Int? {
        if (v == Int.MIN_VALUE) return null
        if (v == -1 || v == 99999 || v == 65535) return null
        return v
    }

    private fun rilLegacyBandToDisplayBandNumber(raw: Int): Int? = when (raw) {
        120 -> 1
        121 -> 2
        122 -> 3
        123 -> 4
        124 -> 5
        125 -> 6
        126 -> 7
        127 -> 8
        128 -> 9
        129 -> 10
        130 -> 11
        131 -> 12
        132 -> 13
        133 -> 14
        134 -> 17
        135 -> 33
        136 -> 34
        137 -> 35
        138 -> 36
        139 -> 37
        140 -> 38
        141 -> 39
        142 -> 40
        143 -> 18
        144 -> 19
        145 -> 20
        146 -> 21
        147 -> 24
        148 -> 25
        149 -> 41
        150 -> 42
        151 -> 43
        152 -> 23
        153 -> 26
        154 -> 32
        155 -> 125
        156 -> 126
        157 -> 127
        158 -> 28
        159 -> 29
        160 -> 30
        else -> null
    }

    private fun rilBandwidthText(raw: Int): String {
        return when (raw) {
            0, 6 -> "NRB_6(1.4 MHz)"
            1, 15 -> "NRB_15(3 MHz)"
            2, 25 -> "NRB_25(5 MHz)"
            3, 50 -> "NRB_50(10 MHz)"
            4, 75 -> "NRB_75(15 MHz)"
            5, 100 -> "NRB_100(20 MHz)"
            else -> "Invalid BandWidth"
        }
    }

    private fun rilScellStateToDisplay(v: Int): String = when (v) {
        0 -> "DECONFIGURED"
        1 -> "CONFIGURED_DEACTIVATED"
        2 -> "CONFIGURED_ACTIVATED"
        else -> "Invalid State"
    }

    private fun buildSignature(cells: List<LteCaCellRaw>): Long {
        var h = 1469598103934665603L
        fun mix(v: Int) {
            h = (h xor v.toLong()) * 1099511628211L
        }

        cells.forEach { c ->
            mix(c.idx)
            mix(c.pci)
            mix(c.dlEarfcn)
            mix(c.ulEarfcn)
            mix(c.band ?: -1)
            mix(c.dlBwRaw ?: -1)
            mix(c.scellState ?: -1)
            mix(c.ulEnabled ?: -1)
            mix(c.rsrp ?: -1)
            mix(c.rsrq ?: -1)
            mix(c.sinr ?: -1)
        }
        return h
    }

    private fun buildJson(ts: Long, slot: Int, cells: List<LteCaCellRaw>, source: String): String {
        val list = JSONArray()
        cells.forEach { c ->
            val obj = JSONObject()
            obj.put("idx", c.idx)
            obj.put("pci", c.pci)
            obj.put("dlEarfcn", c.dlEarfcn)
            obj.put("ulEarfcn", c.ulEarfcn)
            obj.put("band", c.band)
            obj.put("bandText", c.bandText)
            obj.put("dlBwRaw", c.dlBwRaw)
            obj.put("dlBwText", c.dlBwText)
            obj.put("scellState", c.scellState)
            obj.put("scellStateText", c.scellStateText)
            obj.put("ulEnabled", c.ulEnabled)
            obj.put("rsrp", c.rsrp)
            obj.put("rsrq", c.rsrq)
            obj.put("sinr", c.sinr)
            list.put(obj)
        }

        return JSONObject()
            .put("ts", ts)
            .put("slot", slot)
            .put("source", source)
            .put("carriers", cells.size)
            .put("list", list)
            .toString()
    }

    private fun firstIntField(obj: Any, names: List<String>, def: Int): Int {
        names.forEach { name ->
            val v = runCatching { XposedHelpers.getIntField(obj, name) }.getOrNull()
            if (v != null) return v
        }
        return def
    }

    private fun getIntFieldCompat(obj: Any, name: String, def: Int): Int {
        return runCatching { XposedHelpers.getIntField(obj, name) }.getOrDefault(def)
    }

    private fun getObjectFieldCompat(obj: Any, name: String, def: Any?): Any? {
        return runCatching { XposedHelpers.getObjectField(obj, name) }.getOrDefault(def)
    }
}

private data class LteCaCellRaw(
    val idx: Int,
    val pci: Int,
    val dlEarfcn: Int,
    val ulEarfcn: Int,
    val band: Int?,
    val bandText: String?,
    val dlBwRaw: Int?,
    val dlBwText: String?,
    val scellState: Int?,
    val scellStateText: String?,
    val ulEnabled: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?
)
