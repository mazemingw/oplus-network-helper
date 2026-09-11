package com.nvmex.networkhelper.xposed.handler

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

//AMBR处理 暂无结果
class AmbrHandler {
    private val ambrHooked = AtomicBoolean(false)
    private val dumpedRadioClassOnce = AtomicBoolean(false)
    private val hookedRadioReqOnce = AtomicBoolean(false)
    private val hookedRespOnce = AtomicBoolean(false)
    private val nwRateReqHooked = AtomicBoolean(false)
    private val nwRateRespHooked = AtomicBoolean(false)
    private val nwRatePollHooked = AtomicBoolean(false)
    private val pollStartedBySlot = ConcurrentHashMap<Int, Boolean>()

    init {
        Logger.log("[AmbrHandler] initialized")
    }

    fun logAmbr(slot: Int, type: Int, rat: Int, limitState: Int, ambrUl: Int, ambrDl: Int, rawObj: Any) {
        val ts = SystemClock.elapsedRealtime()
        Logger.log("[AMBR] t=$ts slot=$slot indType=$type rat=$rat state=$limitState ul=$ambrUl dl=$ambrDl obj=${rawObj.javaClass.name}")
    }

    fun ensureHookRadioReq(radioClass: Class<*>) {
        if (!hookedRadioReqOnce.compareAndSet(false, true)) return

        XposedBridge.hookAllMethodsNative(radioClass, "getNwRateLimitingInfo") { chain ->
            val slot = runCatching { XposedHelpers.callMethod(chain.thisObject, "getSlotId") as Int }
                .getOrDefault(-1)
            Logger.log("[AMBR][REQ] slot=$slot args=${chain.args.toTypedArray().contentToString()}")
            Logger.logE("[AMBR][REQ] stack", Throwable("getNwRateLimitingInfo stack"))
            chain.proceed()
        }

        Logger.log("[AMBR] hooked req on ${radioClass.name}#getNwRateLimitingInfo")
    }

    fun ensureHookRadioRespFromRadioObj(radioObj: Any) {
        if (!hookedRespOnce.compareAndSet(false, true)) return

        val respObj = runCatching { XposedHelpers.getObjectField(radioObj, "mAidlResponse") }.getOrNull()
        if (respObj == null) {
            Logger.log("[AMBR] mAidlResponse is null on mRadio")
            hookedRespOnce.set(false)
            return
        }

        val respClass = respObj.javaClass
        XposedBridge.hookAllMethodsNative(respClass, "getNwRateLimitingInfoResponse") { chain ->
            val args = chain.args.toTypedArray()
            val limitInfo = args.firstOrNull { it?.javaClass?.name?.endsWith("NwRateLimitingInfo") == true }

            if (limitInfo != null) {
                val rat = XposedHelpers.getIntField(limitInfo, "rat")
                val limitState = XposedHelpers.getIntField(limitInfo, "limitState")
                val ambrUl = XposedHelpers.getIntField(limitInfo, "ambrUl")
                val ambrDl = XposedHelpers.getIntField(limitInfo, "ambrDl")
                Logger.log("[AMBR][RESP] rat=$rat state=$limitState ul=$ambrUl dl=$ambrDl respClass=${respClass.name}")
            } else {
                Logger.log("[AMBR][RESP] args=${args.contentToString()} respClass=${respClass.name}")
            }
            chain.proceed()
        }

        Logger.log("[AMBR] hooked resp on ${respClass.name}#getNwRateLimitingInfoResponse")
    }

    fun hookNwRateReqJ(cl: ClassLoader) {
        if (!nwRateReqHooked.compareAndSet(false, true)) return

        val rpJ = findFirstClass(
            cl,
            "com.oplus.radio.RadioProxyJ",
            "com.oplus.subsys.radio.RadioProxyJ"
        ) ?: run {
            Logger.log("[AMBR] RadioProxyJ class not found (req)")
            return
        }

        XposedBridge.hookAllMethodsNative(rpJ, "getNwRateLimitingInfo") { chain ->
            val slot = runCatching { XposedHelpers.callMethod(chain.thisObject, "getSlotId") as Int }
                .getOrDefault(-1)
            Logger.log("[AMBR][REQ-J] slot=$slot args=${chain.args.toTypedArray().contentToString()}")
            Logger.logE("[AMBR][REQ-J] stack", Throwable("getNwRateLimitingInfo stack"))
            chain.proceed()
        }

        Logger.log("[AMBR] hooked ${rpJ.name}#getNwRateLimitingInfo")
    }

    fun hookNwRateRespJ(cl: ClassLoader) {
        if (!nwRateRespHooked.compareAndSet(false, true)) return

        val rpJ = findFirstClass(
            cl,
            "com.oplus.radio.RadioProxyJ",
            "com.oplus.subsys.radio.RadioProxyJ"
        ) ?: run {
            Logger.log("[AMBR] RadioProxyJ class not found")
            return
        }

        XposedBridge.hookAllMethodsNative(rpJ, "getNwRateLimitingInfoResponse") { chain ->
            val args = chain.args.toTypedArray()
            val infoObj = args.firstOrNull { it?.javaClass?.name?.endsWith("NwRateLimitingInfo") == true }
            if (infoObj != null) {
                val rat = XposedHelpers.getIntField(infoObj, "rat")
                val limitState = XposedHelpers.getIntField(infoObj, "limitState")
                val ambrUl = XposedHelpers.getIntField(infoObj, "ambrUl")
                val ambrDl = XposedHelpers.getIntField(infoObj, "ambrDl")

                val slot = runCatching { XposedHelpers.callMethod(chain.thisObject, "getSlotId") as Int }
                    .getOrDefault(-1)

                Logger.log("[AMBR][RESP-J] slot=$slot rat=$rat state=$limitState ul=$ambrUl dl=$ambrDl")
            } else {
                Logger.log("[AMBR][RESP-J] args=${args.contentToString()}")
            }
            chain.proceed()
        }

        Logger.log("[AMBR] hooked ${rpJ.name}#getNwRateLimitingInfoResponse")
    }

    fun hookNwRatePoller(cl: ClassLoader) {
        if (!nwRatePollHooked.compareAndSet(false, true)) return

        val rp = XposedHelpers.findClassIfExists("com.oplus.radio.RadioProxy", cl)
            ?: run { Logger.log("[AMBR] RadioProxy class not found for poller"); return }

        XposedBridge.hookAllMethodsNative(rp, "onLoadProxyCallback") { chain ->
            val result = chain.proceed()
            val slot = runCatching { XposedHelpers.callMethod(chain.thisObject, "getSlotId") as Int }.getOrDefault(-1)
            if (slot < 0) return@hookAllMethodsNative result
            if (pollStartedBySlot.putIfAbsent(slot, true) != null) return@hookAllMethodsNative result

            Logger.log("[AMBR][POLL] start poller for slot=$slot")

            val handler = Handler(Looper.getMainLooper())
            val self = chain.thisObject

            var left = 6
            val r = object : Runnable {
                override fun run() {
                    if (left-- <= 0) return
                    runCatching {
                        val msg = Message.obtain(handler, 0)
                        XposedHelpers.callMethod(self, "getNwRateLimitingInfo", msg)
                        Logger.log("[AMBR][POLL] slot=$slot request sent, left=$left")
                    }.onFailure {
                        Logger.logE("[AMBR][POLL] request failed slot=$slot", it)
                    }
                    handler.postDelayed(this, 10_000L)
                }
            }
            handler.postDelayed(r, 2_000L)
            result
        }

        Logger.log("[AMBR] hooked RadioProxy.onLoadProxyCallback (poller)")
    }

    private fun findFirstClass(cl: ClassLoader, vararg names: String): Class<*>? {
        for (n in names) {
            val c = XposedHelpers.findClassIfExists(n, cl)
            if (c != null) return c
        }
        return null
    }
}
