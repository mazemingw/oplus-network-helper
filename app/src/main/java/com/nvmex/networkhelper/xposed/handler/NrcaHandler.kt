package com.nvmex.networkhelper.xposed.handler

import android.os.SystemClock
import com.nvmex.networkhelper.xposed.broad.BroadcastHelper
import com.nvmex.networkhelper.xposed.logger.Logger
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

//NRCA处理 调用广播发送器发送
//有跨进程问题
class NrcaHandler(private val broadcastHelper: BroadcastHelper) {
    companion object {
        private const val FORCE_REEMIT_MS = 6_000L
    }

    private val dumpedFieldsOnce = AtomicBoolean(false)
    private val lastSigBySlot = ConcurrentHashMap<Int, Long>()
    private val lastEmitAtBySlot = ConcurrentHashMap<Int, Long>()

    fun parseHalNrca(slot: Int, type: Int, nrca: Any) {
        try {
            val c = nrca.javaClass

            if (dumpedFieldsOnce.compareAndSet(false, true)) {
                val fields = c.declaredFields.joinToString { "${it.type.simpleName}:${it.name}" }
                Logger.log("[NRCA][parse] nrcaClass=${c.name} cl=${Logger.clTag(c.classLoader)} fields=$fields")
            }

            val numField = XposedHelpers.findField(c, "numCarriers")
            val cfgField = XposedHelpers.findField(c, "carrierConfig")

            val numAny = numField.get(nrca)
            val num = when (numAny) {
                is Byte -> numAny.toInt()
                is Int -> numAny
                else -> 0
            }

            val cfgAny = cfgField.get(nrca)
            val arr = cfgAny as? Array<*> ?: run {
                Logger.log("[NRCA][HAL] slot=$slot carrierConfig not Array, type=${cfgAny?.javaClass?.name}")
                return
            }

            // 变化检测
            val sig = buildNrcaSignature(num, arr)
            val now = SystemClock.elapsedRealtime()
            val last = lastSigBySlot.put(slot, sig)
            if (last != null && last == sig) {
                val age = now - (lastEmitAtBySlot[slot] ?: 0L)
                if (age in 0 until FORCE_REEMIT_MS) return
                Logger.log("[NRCA][HAL] force re-emit slot=$slot age=${age}ms")
            }

            val ts = now
            lastEmitAtBySlot[slot] = ts

            // 打日志
            Logger.log("=== [NRCA][HAL] t=$ts slot=$slot type=$type carriers=$num ===")
            val limit = minOf(num, arr.size, 8)

            // 组JSON
            val json = buildNrcaJson(ts, slot, type, num, arr, limit)

            // 发广播
            broadcastHelper.emitNrcaToApp(ts, slot, type, num, json)

            // 打印明细
            for (i in 0 until limit) {
                val item = arr[i] ?: continue
                val ccId = XposedHelpers.getIntField(item, "ccId")
                val sccId = XposedHelpers.getIntField(item, "sccId")
                val pci = XposedHelpers.getIntField(item, "pci")
                val dlStateRaw = XposedHelpers.getIntField(item, "dlState")
                val dlEarfcn = XposedHelpers.getIntField(item, "dlEarfcn")
                val bandRaw = XposedHelpers.getIntField(item, "band")
                val dlBwRaw = XposedHelpers.getIntField(item, "dlBandwidth")
                val ulStateRaw = XposedHelpers.getIntField(item, "ulState")
                val ulBwRaw = XposedHelpers.getIntField(item, "ulBandwith")

                Logger.log(
                    "  " + NrcaTranslator.formatCarrier(
                        idx = i,
                        ccId = ccId,
                        sccId = sccId,
                        pci = pci,
                        band = bandRaw,
                        dlEarfcn = dlEarfcn,
                        dlState = dlStateRaw,
                        dlBw = dlBwRaw,
                        ulState = ulStateRaw,
                        ulBw = ulBwRaw
                    )
                )
            }
        } catch (t: Throwable) {
            Logger.logE("parseHalNrca failed", t)
        }
    }

    private fun buildNrcaSignature(num: Int, arr: Array<*>): Long {
        var h = 1469598103934665603L
        fun mix(v: Int) {
            h = (h xor v.toLong()) * 1099511628211L
        }

        mix(num)
        val limit = minOf(num, arr.size, 8)
        for (i in 0 until limit) {
            val item = arr[i] ?: continue
            mix(XposedHelpers.getIntField(item, "ccId"))
            mix(XposedHelpers.getIntField(item, "sccId"))
            mix(XposedHelpers.getIntField(item, "pci"))
            mix(XposedHelpers.getIntField(item, "band"))
            mix(XposedHelpers.getIntField(item, "dlEarfcn"))
            mix(XposedHelpers.getIntField(item, "dlState"))
            mix(XposedHelpers.getIntField(item, "dlBandwidth"))
            mix(XposedHelpers.getIntField(item, "ulState"))
            mix(XposedHelpers.getIntField(item, "ulBandwith"))
        }
        return h
    }

    private fun buildNrcaJson(
        ts: Long,
        slot: Int,
        type: Int,
        carriers: Int,
        arr: Array<*>,
        limit: Int
    ): String {
        val list = JSONArray()
        for (i in 0 until limit) {
            val item = arr[i] ?: continue
            val obj = JSONObject()
            obj.put("idx", i)
            obj.put("ccId", XposedHelpers.getIntField(item, "ccId"))
            obj.put("sccId", XposedHelpers.getIntField(item, "sccId"))
            obj.put("pci", XposedHelpers.getIntField(item, "pci"))
            obj.put("band", XposedHelpers.getIntField(item, "band"))
            obj.put("dlEarfcn", XposedHelpers.getIntField(item, "dlEarfcn"))
            obj.put("dlState", XposedHelpers.getIntField(item, "dlState"))
            obj.put("dlBw", XposedHelpers.getIntField(item, "dlBandwidth"))
            obj.put("ulState", XposedHelpers.getIntField(item, "ulState"))
            obj.put("ulBw", XposedHelpers.getIntField(item, "ulBandwith"))
            list.put(obj)
        }

        return JSONObject()
            .put("ts", ts)
            .put("slot", slot)
            .put("type", type)
            .put("carriers", carriers)
            .put("list", list)
            .toString()
    }
}
