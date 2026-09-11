package com.nvmex.networkhelper.xposed.woker

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import com.nvmex.networkhelper.xposed.handler.AmbrHandler
import com.nvmex.networkhelper.xposed.handler.NrcaHandler
import com.nvmex.networkhelper.xposed.logger.Logger
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class IndicationHooksTelephony(
    private val nrcaHandler: NrcaHandler,
    private val ambrHandler: AmbrHandler
) {
    companion object { const val LP = "[NRCA][HOOK]" }

    private val indicationHooked = AtomicBoolean(false)
    private val responseHooked = AtomicBoolean(false)

    fun tryHookWithRetry(cl: ClassLoader, from: String) {
        val mainHandler = android.os.Handler(Looper.getMainLooper())
        val maxTries = 30
        val intervalMs = 200L
        val tries = intArrayOf(0)

        fun attempt() {
            tries[0]++
            var okAny = false

            if (!indicationHooked.get()) okAny = tryHookIndicationNow(cl) || okAny
            if (!responseHooked.get()) okAny = tryHookResponseNow(cl) || okAny

            if (indicationHooked.get() && responseHooked.get()) {
                Logger.log("$LP[OK] all hooked from=$from try=${tries[0]}")
                return
            }

            if (tries[0] < maxTries) {
                mainHandler.postDelayed({ attempt() }, intervalMs)
            } else {
                Logger.log("$LP[GIVEUP] from=$from tries=$maxTries ind=${indicationHooked.get()} resp=${responseHooked.get()}")
            }
        }

        Logger.log("$LP[START] tryHookWithRetry from=$from")
        attempt()
    }

    // ---------- Indication（可选） ----------
    private fun tryHookIndicationNow(cl: ClassLoader): Boolean {
        val candidates = listOf(
            "com.oplus.telephony.SubsysRadioIndication",
            "com.oplus.radio.SubsysRadioIndication",
        )

        for (cn in candidates) {
            val cls = XposedHelpers.findClassIfExists(cn, cl) ?: continue
            val ok = hookIndicationByClass(cls)
            if (ok) {
                indicationHooked.set(true)
                Logger.log("$LP[IND] indication hooks completed cls=$cn")
                return true
            }
        }

        Logger.log("$LP[IND] indication class not found (skip)")
        return false
    }

    private fun hookIndicationByClass(indicationClass: Class<*>): Boolean {
        var hookedAnything = false

        // 这里先保留你原来的三件套：NRCA / ServingCell / AMBR
        // 但注意：telephony 这边的方法名不一定还是 radioNrcaInfoChangeInd 等
        // 所以先用 “包含关键词 + 参数个数” 的策略尝试。
        fun findMethod(vararg names: String, params: Int): java.lang.reflect.Method? {
            return indicationClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == params && names.any { m.name == it }
            }
        }

        // 1) NRCA indication（如果存在）
        runCatching {
            val m = findMethod("radioNrcaInfoChangeInd", "nrcaInfoChangeInd", "nrcaInfoChangedInd", params = 2)
            if (m != null) {
                m.isAccessible = true
                XposedBridge.hookMethodNative(m) { chain ->
                    val type = chain.args.getOrNull(0) as? Int ?: 0
                    val nrca = chain.args.getOrNull(1)
                    val slot = NrcaTranslator.tryGetSlotId(chain.thisObject)

                    if (nrca != null) {
                        Logger.log("$LP[IND][NRCA] ${m.name} slot=$slot type=$type")
                        logNrcaDigest("PASSIVE", slot, type, nrca)
                        nrcaHandler.parseHalNrca(slot, type, nrca)
                    }

                    cacheRadioProxyFromAny(chain.thisObject, slot)
                    chain.proceed()
                }
                Logger.log("$LP[IND] hooked ${m.toGenericString()}")
                hookedAnything = true
            }
        }.onFailure { Logger.logE("$LP[ERR][IND][NRCA] hook failed", it) }

        // 2) ServingCell indication（只为抓 mRadio）
        runCatching {
            val m = findMethod("radioServingCellInfoInd", "servingCellInfoInd", params = 2)
            if (m != null) {
                m.isAccessible = true
                XposedBridge.hookMethodNative(m) { chain ->
                    val slot = NrcaTranslator.tryGetSlotId(chain.thisObject)
                    cacheRadioProxyFromAny(chain.thisObject, slot)
                    chain.proceed()
                }
                Logger.log("$LP[IND] hooked ${m.toGenericString()} (ServingCell cache)")
                hookedAnything = true
            }
        }.onFailure { Logger.logE("$LP[ERR][IND][SCell] hook failed", it) }

        // 3) AMBR indication（如果存在）
        runCatching {
            val m = findMethod("radioNwRateLimitingInd", "nwRateLimitingInd", params = 2)
            if (m != null) {
                m.isAccessible = true
                XposedBridge.hookMethodNative(m) { chain ->
                    val type = chain.args.getOrNull(0) as? Int
                    val info = chain.args.getOrNull(1)
                    val slot = NrcaTranslator.tryGetSlotId(chain.thisObject)

                    if (type != null && info != null) {
                        runCatching {
                            val rat = XposedHelpers.getIntField(info, "rat")
                            val limitState = XposedHelpers.getIntField(info, "limitState")
                            val ambrUl = XposedHelpers.getIntField(info, "ambrUl")
                            val ambrDl = XposedHelpers.getIntField(info, "ambrDl")
                            Logger.log("$LP[IND][AMBR] slot=$slot type=$type rat=$rat limit=$limitState ul=$ambrUl dl=$ambrDl")
                            ambrHandler.logAmbr(slot, type, rat, limitState, ambrUl, ambrDl, info)
                        }
                    }

                    val radioObj = runCatching { XposedHelpers.getObjectField(chain.thisObject, "mRadio") }.getOrNull()
                    if (radioObj != null) {
                        NrcaPoller.onRadioProxySeen(slot, radioObj)
                    }
                    chain.proceed()
                }
                Logger.log("$LP[IND] hooked ${m.toGenericString()} (AMBR)")
                hookedAnything = true
            }
        }.onFailure { Logger.logE("$LP[ERR][IND][AMBR] hook failed", it) }

        return hookedAnything
    }

    // ---------- Response（强烈建议先把这条跑通） ----------
    private fun tryHookResponseNow(cl: ClassLoader): Boolean {
        val candidates = listOf(
            "com.oplus.telephony.SubsysRadioResponse",
            "com.oplus.radio.SubsysRadioResponse",
        )

        for (cn in candidates) {
            val cls = XposedHelpers.findClassIfExists(cn, cl) ?: continue
            val ok = hookResponseByClass(cls)
            if (ok) {
                responseHooked.set(true)
                Logger.log("$LP[RESP] response hooks completed cls=$cn")
                return true
            }
        }
        return false
    }

    private fun hookResponseByClass(respClass: Class<*>): Boolean {
        var hookedAnything = false

        runCatching {
            val m = respClass.declaredMethods.firstOrNull {
                it.name == "getNrcaInfoResponse" && it.parameterTypes.size == 2
            }
            if (m != null) {
                m.isAccessible = true
                XposedBridge.hookMethodNative(m) { chain ->
                    val nrca = chain.args.getOrNull(1)
                    val slot = extractSlotFromTelephonyResponse(chain.thisObject)

                    if (nrca != null) {
                        Logger.log("$LP[RESP][NRCA] getNrcaInfoResponse slot=$slot cls=${nrca.javaClass.name}")
                        logNrcaDigest("ACTIVE", slot, 0, nrca)

                        // ✅ 保留你的老解析入口
                        nrcaHandler.parseHalNrca(slot, 0, nrca)

                        cacheRadioProxyFromAny(chain.thisObject, slot)
                        NrcaPoller.onNrcaArrived(slot)
                    }
                    chain.proceed()
                }
                Logger.log("$LP[RESP] hooked ${m.toGenericString()}")
                hookedAnything = true
            }
        }.onFailure { Logger.logE("$LP[ERR][RESP] hook getNrcaInfoResponse failed", it) }

        return hookedAnything
    }

    private fun extractSlotFromTelephonyResponse(respObj: Any): Int {
        // telephony 这套：this.mRadio.mSlotId 最准
        runCatching {
            val radioObj = XposedHelpers.getObjectField(respObj, "mRadio") ?: return@runCatching
            return XposedHelpers.getIntField(radioObj, "mSlotId")
        }
        // fallback
        return runCatching { NrcaTranslator.tryGetSlotId(respObj) }.getOrDefault(-1)
    }

    private fun cacheRadioProxyFromAny(anyObj: Any, slot: Int) {
        runCatching {
            val radioObj = XposedHelpers.getObjectField(anyObj, "mRadio") ?: return
            Logger.log("$LP[RADIO] cache mRadio slot=$slot class=${radioObj.javaClass.name}")
            NrcaPoller.onRadioProxySeen(slot, radioObj)
        }.onFailure { Logger.logE("$LP[ERR][RADIO] cacheRadioProxyFromAny failed", it) }
    }

    private object NrcaPoller {
        private const val POLL_MS_FAST = 1500L
        private const val POLL_MS_SLOW = 5000L
        private const val STALE_MS = 12_000L

        private val radios = ConcurrentHashMap<Int, Any>()
        private val lastSeenNrca = ConcurrentHashMap<Int, Long>()
        private val started = AtomicBoolean(false)

        private val ht = HandlerThread("nrca-poller").apply { start() }
        private val h = Handler(ht.looper)
        private val tickRunnable = Runnable { tick() }

        // ✅ 必须有 target，ResponseHelper.sendMessageResponse 才能投递
        private val resultHandler = Handler(ht.looper) { msg ->
            true
        }

        fun onRadioProxySeen(slot: Int, radio: Any) {
            if (slot < 0) return
            val old = radios.put(slot, radio)
            if (old != null && old !== radio) lastSeenNrca.remove(slot)

            if (started.compareAndSet(false, true)) {
                Logger.log("$LP[POLL] start slot=$slot")
                schedule(POLL_MS_FAST)
            }
        }

        fun onNrcaArrived(slot: Int) {
            if (slot < 0) return
            lastSeenNrca[slot] = SystemClock.elapsedRealtime()
        }

        private fun schedule(delayMs: Long) {
            h.removeCallbacks(tickRunnable)
            h.postDelayed(tickRunnable, delayMs)
        }

        private fun tick() {
            val now = SystemClock.elapsedRealtime()
            if (radios.isEmpty()) {
                schedule(POLL_MS_SLOW)
                return
            }

            var next = POLL_MS_FAST

            radios.forEach { (slot, radioObj) ->
                val age = now - (lastSeenNrca[slot] ?: 0L)
                val slow = age > STALE_MS
                if (slow) next = POLL_MS_SLOW

                Logger.log("$LP[POLL] trigger getNrcaInfo slot=$slot age=${age}ms slow=$slow")
                requestNrcaOnce(slot, radioObj)
            }

            schedule(next)
        }

        private fun requestNrcaOnce(slot: Int, radioObj: Any) {
            try {
                val m = radioObj.javaClass.methods.firstOrNull {
                    it.name == "getNrcaInfo" && it.parameterTypes.size == 1
                } ?: run {
                    Logger.log("$LP[POLL][WARN] getNrcaInfo(Message) not found slot=$slot cls=${radioObj.javaClass.name}")
                    return
                }

                // ✅ 核心修正：带 target
                val msg = Message.obtain(resultHandler, 0x4E524341)
                msg.arg1 = slot
                m.invoke(radioObj, msg)
            } catch (t: Throwable) {
                Logger.logE("$LP[ERR][POLL] requestNrcaOnce slot=$slot failed", t)
            }
        }
    }
}
