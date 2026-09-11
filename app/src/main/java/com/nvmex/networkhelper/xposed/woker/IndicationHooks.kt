package com.nvmex.networkhelper.xposed.woker

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import com.nvmex.networkhelper.xposed.handler.AmbrHandler
import com.nvmex.networkhelper.xposed.handler.NrcaHandler
import com.nvmex.networkhelper.xposed.logger.Logger
import com.nvmex.networkhelper.xposed.test.NrcaModifier
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import com.nvmex.networkhelper.xposed.utils.nrca.DebugFlags
import com.nvmex.networkhelper.xposed.utils.nrca.HalRawDumper
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NRCA/AMBR Hook 总入口（Subsys 侧）
 *
 * 设计目标：
 * 1) 主动优先：以 Response(getNrcaInfoResponse) 为主数据源
 * 2) 主动成功后短暂压制被动：避免同批次 Indication 旧包覆盖主动新鲜值
 * 3) 主动无效时允许被动兜底：确保 UI 至少有数据
 * 4) digest 去抖：同 slot 同内容（sig 相同）不重复 parse，降低广播/UI 重组
 * 5) 自适应轮询：sig 变化 -> burst 高频追踪；稳定 -> 自动退频；长期无有效 -> 中频保活
 */
class IndicationHooks(
    private val nrcaHandler: NrcaHandler,
    private val ambrHandler: AmbrHandler
) {
    companion object {
        const val LP = "[NRCA][HOOK]"
    }


    private val indicationHooked = AtomicBoolean(false)
    private val responseHooked = AtomicBoolean(false)
    private val requestHooked = AtomicBoolean(false)

    fun tryHookIndicationWithRetry(cl: ClassLoader, from: String) {
        val mainHandler = Handler(Looper.getMainLooper())
        val maxTries = 20
        val intervalMs = 200L
        val tries = intArrayOf(0)

        fun attempt() {
            tries[0]++

            if (!indicationHooked.get()) tryHookIndicationNow(cl)
            if (!responseHooked.get()) tryHookResponseNow(cl)
            if (!requestHooked.get()) tryHookRequestNow(cl)

            if (indicationHooked.get() && responseHooked.get() && requestHooked.get()) {
                Logger.log("$LP[OK] all hooked from=$from try=${tries[0]}")
                return
            }

            if (tries[0] < maxTries) {
                mainHandler.postDelayed({ attempt() }, intervalMs)
            } else {
                Logger.log(
                    "$LP[GIVEUP] from=$from tries=$maxTries " +
                            "ind=${indicationHooked.get()} resp=${responseHooked.get()} req=${requestHooked.get()}"
                )
            }
        }

        Logger.log("$LP[START] tryHookIndicationWithRetry from=$from")
        attempt()
    }

    private fun tryHookIndicationNow(cl: ClassLoader): Boolean {
        return try {
            val indicationCls = XposedHelpers.findClassIfExists(
                "com.oplus.radio.SubsysRadioIndication",
                cl
            ) ?: return false

            val hooked = hookIndicationByClass(indicationCls)
            if (hooked) {
                indicationHooked.set(true)
                seedRadioProxyFromIndicationInstances(indicationCls)
                Logger.log("$LP[IND] indication hooks completed")
            }
            hooked
        } catch (t: Throwable) {
            Logger.logE("$LP[ERR][IND] tryHookIndicationNow failed", t)
            false
        }
    }

    private fun tryHookResponseNow(cl: ClassLoader): Boolean {
        return try {
            val respCls = XposedHelpers.findClassIfExists(
                "com.oplus.radio.SubsysRadioResponse",
                cl
            ) ?: return false

            val hooked = hookResponseByClass(respCls)
            if (hooked) {
                responseHooked.set(true)
                Logger.log("$LP[RESP] response hooks completed")
            }
            hooked
        } catch (t: Throwable) {
            Logger.logE("$LP[ERR][RESP] tryHookResponseNow failed", t)
            false
        }
    }

    /**
     * Indication hooks：
     * - radioNrcaInfoChangeInd：被动 NRCA（但会被主动优先仲裁压制）
     * - radioServingCellInfoInd：高频，仅用于抓 mRadio，让主动轮询可启动
     * - radioNwRateLimitingInd：AMBR（保留原逻辑）
     */
    private fun hookIndicationByClass(indicationClass: Class<*>): Boolean {
        var hookedAnything = false

        // 0) 构造时立刻抓 mRadio，避免“先有回调才有主动轮询”的循环依赖。
        runCatching {
            XposedBridge.hookAllConstructorsNative(indicationClass) { chain ->
                val result = chain.proceed()
                val slot = NrcaTranslator.tryGetSlotId(chain.thisObject)
                cacheRadioProxyFromAny(chain.thisObject, slot)
                result
            }
            Logger.log("$LP[IND] hooked constructors for radio cache")
            hookedAnything = true
        }.onFailure { Logger.logE("$LP[ERR][IND][CTOR] hook failed", it) }

        // 1) 被动 NRCA
        try {
            val m = indicationClass.declaredMethods.firstOrNull {
                it.name == "radioNrcaInfoChangeInd" && it.parameterTypes.size == 2
            }
            if (m != null) {
                m.isAccessible = true
                XposedBridge.hookMethodNative(m) { chain ->
                    val args = chain.args.toTypedArray()
                    if (DebugFlags.DUMP_HAL_RAW) {
                        Logger.log("$LP[RAW][IND][被动NRCA] ===== BEGIN =====")
                        args.forEachIndexed { idx, arg ->
                            HalRawDumper.dump(arg, "IND.NRCA.arg[$idx]")
                        }
                        Logger.log("$LP[RAW][IND][被动NRCA] ===== END =====")
                    }

                    val type = args.getOrNull(0) as? Int
                    val nrca = args.getOrNull(1)
                    if (type != null && nrca != null) {
                        val slot = NrcaTranslator.tryGetSlotId(chain.thisObject)

                        // 直接使用单例修改器。
                        val modifiedNrca = NrcaModifier.modifyNrca(slot, nrca)
                        args[1] = modifiedNrca

                        // 先抓 mRadio，确保主动轮询能启动
                        cacheRadioProxyFromAny(chain.thisObject, slot)

                        val accept = ActiveFirstArbiter.shouldAcceptPassive(slot)
                        Logger.log("$LP[IND][NRCA] passive accept=$accept slot=$slot type=$type")
                        if (!accept) {
                            logNrcaDigest("PASSIVE-DROP", slot, type, nrca)
                            return@hookMethodNative chain.proceed(args)
                        }

                        // digest 去抖（被动也做，避免刷爆）
                        val d = runCatching { buildNrcaDigest(nrca) }.getOrNull()
                        if (d == null) {
                            Logger.log("$LP[PASSIVE][NRCA] slot=$slot type=$type (digest failed) cls=${nrca.javaClass.name}")
                            nrcaHandler.parseHalNrca(slot, type, nrca)
                            return@hookMethodNative chain.proceed(args)
                        }

                        if (!Deduper.shouldAccept(slot, source = "PASSIVE", sig = d.sig, num = d.num)) {
                            Logger.log("$LP[PASSIVE][NRCA] slot=$slot drop duplicate sig=${java.lang.Long.toHexString(d.sig)} num=${d.num}")
                            return@hookMethodNative chain.proceed(args)
                        }

                        logNrcaDigest("PASSIVE", slot, type, nrca)
                        nrcaHandler.parseHalNrca(slot, type, nrca)
                    }
                    chain.proceed(args)
                }
                Logger.log("$LP[IND] hooked ${m.toGenericString()}")
                hookedAnything = true
            } else {
                Logger.log("$LP[IND] radioNrcaInfoChangeInd not found")
            }
        } catch (t: Throwable) {
            Logger.logE("$LP[ERR][IND][NRCA] hook failed", t)
        }

        // 2) ServingCell：只为抓 mRadio
        try {
            val m = indicationClass.declaredMethods.firstOrNull {
                it.name == "radioServingCellInfoInd" && it.parameterTypes.size == 2
            }
            if (m != null) {
                m.isAccessible = true
                XposedBridge.hookMethodNative(m) { chain ->
                    val slot = NrcaTranslator.tryGetSlotId(chain.thisObject)
                    cacheRadioProxyFromAny(chain.thisObject, slot)
                    chain.proceed()
                }
                Logger.log("$LP[IND] hooked ${m.toGenericString()} (ServingCell for cache)")
                hookedAnything = true
            } else {
                Logger.log("$LP[IND] radioServingCellInfoInd not found")
            }
        } catch (t: Throwable) {
            Logger.logE("$LP[ERR][IND][SCell] hook failed", t)
        }

        // 3) AMBR（沿用现有逻辑）
        try {
            val ambrMethod = indicationClass.declaredMethods.firstOrNull {
                it.name == "radioNwRateLimitingInd" && it.parameterTypes.size == 2
            }
            if (ambrMethod != null) {
                ambrMethod.isAccessible = true
                XposedBridge.hookMethodNative(ambrMethod) { chain ->
                    try {
                        val type = chain.args.getOrNull(0) as? Int
                        val info = chain.args.getOrNull(1)
                        if (type != null && info != null) {
                            val slot = NrcaTranslator.tryGetSlotId(chain.thisObject)

                            val rat = XposedHelpers.getIntField(info, "rat")
                            val limitState = XposedHelpers.getIntField(info, "limitState")
                            val ambrUl = XposedHelpers.getIntField(info, "ambrUl")
                            val ambrDl = XposedHelpers.getIntField(info, "ambrDl")

                            Logger.log("$LP[IND][AMBR] slot=$slot type=$type rat=$rat limit=$limitState ul=$ambrUl dl=$ambrDl")

                            ambrHandler.logAmbr(slot, type, rat, limitState, ambrUl, ambrDl, info)

                            // 反查 mRadio，并顺便喂给轮询器
                            val radioObj = runCatching { XposedHelpers.getObjectField(chain.thisObject, "mRadio") }.getOrNull()
                            if (radioObj != null) {
                                ambrHandler.ensureHookRadioReq(radioObj.javaClass)
                                ambrHandler.ensureHookRadioRespFromRadioObj(radioObj)
                                NrcaPoller.onRadioProxySeen(slot, radioObj)
                            }
                        }
                    } catch (t: Throwable) {
                        Logger.logE("$LP[ERR][IND][AMBR] hook callback error", t)
                    }
                    chain.proceed()
                }
                Logger.log("$LP[IND] hooked ${ambrMethod.toGenericString()} (AMBR)")
                hookedAnything = true
            } else {
                Logger.log("$LP[IND] radioNwRateLimitingInd not found")
            }
        } catch (t: Throwable) {
            Logger.logE("$LP[ERR][IND][AMBR] hook install failed", t)
        }

        return hookedAnything
    }

    /**
     * Response hook：主动查询返回入口
     *
     * 关键点：
     * - 只要 valid=true（num>0），就立刻调用 onNrcaArrived(slot) 刷新 lastOkAt
     *   即使内容重复（Deduper drop），也不会导致 ageOk 越来越大而误判
     */
    private fun hookResponseByClass(respClass: Class<*>): Boolean {
        var hookedAnything = false

        try {
            // 构造时优先缓存 mRadio，尽快拉起主动轮询。
            runCatching {
                XposedBridge.hookAllConstructorsNative(respClass) { chain ->
                    val result = chain.proceed()
                    val slot = extractSlotFromResponse(chain.thisObject)
                    cacheRadioProxyFromAny(chain.thisObject, slot)
                    result
                }
                Logger.log("$LP[RESP] hooked constructors for radio cache")
                hookedAnything = true
            }.onFailure { Logger.logE("$LP[ERR][RESP][CTOR] hook failed", it) }

            val m = respClass.declaredMethods.firstOrNull {
                it.name == "getNrcaInfoResponse" && it.parameterTypes.size == 2
            }

            if (m != null) {
                m.isAccessible = true
                XposedBridge.hookMethodNative(m) { chain ->
                    val args = chain.args.toTypedArray()
                    val info = args.getOrNull(0)
                    val respError = runCatching {
                        if (info != null) XposedHelpers.getIntField(info, "error") else 0
                    }.getOrDefault(0)
                    val respSerial = runCatching {
                        if (info != null) XposedHelpers.getIntField(info, "serial") else -1
                    }.getOrDefault(-1)

                    if (DebugFlags.DUMP_HAL_RAW) {
                        Logger.log("$LP[RAW][RESP][主动NRCA] ===== BEGIN =====")
                        args.forEachIndexed { idx, arg ->
                            HalRawDumper.dump(arg, "RESP.NRCA.arg[$idx]")
                        }
                        Logger.log("$LP[RAW][RESP][主动NRCA] ===== END =====")
                    }

                    val slot = extractSlotFromResponse(chain.thisObject)

                    // 先缓存 mRadio，保证轮询持续可用
                    cacheRadioProxyFromAny(chain.thisObject, slot)

                    val acceptBySerial = NrcaPoller.onActiveResponse(slot, respSerial, respError)
                    if (!acceptBySerial) {
                        Logger.log("$LP[RESP][NRCA] drop stale active response slot=$slot serial=$respSerial err=$respError")
                        return@hookMethodNative chain.proceed(args)
                    }

                    if (respError != 0) {
                        ActiveFirstArbiter.onActiveArrived(slot, false)
                        Logger.log("$LP[RESP][NRCA] drop error response slot=$slot serial=$respSerial err=$respError")
                        return@hookMethodNative chain.proceed(args)
                    }

                    val nrca = args.getOrNull(1) ?: run {
                        ActiveFirstArbiter.onActiveArrived(slot, false)
                        Logger.log("$LP[RESP][NRCA] drop null active payload slot=$slot serial=$respSerial")
                        return@hookMethodNative chain.proceed(args)
                    }

                    // 直接使用单例修改器。
                    val modifiedNrca = NrcaModifier.modifyNrca(slot, nrca)
                    args[1] = modifiedNrca

                    val d = runCatching { buildNrcaDigest(nrca) }.getOrNull()
                    val valid = d != null && d.num > 0

                    Logger.log(
                        "$LP[RESP][NRCA] slot=$slot serial=$respSerial err=$respError valid=$valid num=${d?.num ?: -1}"
                    )
                    logNrcaDigest("ACTIVE", slot, 0, nrca)

                    // 主动成功/失败：决定压制/放开被动
                    ActiveFirstArbiter.onActiveArrived(slot, valid)

                    if (!valid) {
                        Logger.log("$LP[RESP][NRCA] drop invalid active result slot=$slot")
                        return@hookMethodNative chain.proceed(args)
                    }

                    // 只要 valid 到达就更新 lastOkAt（不受 Deduper 影响）。
                    NrcaPoller.onNrcaArrived(slot)

                    // 去抖：同内容不重复 parse
                    if (!Deduper.shouldAccept(slot, source = "ACTIVE", sig = d!!.sig, num = d.num)) {
                        Logger.log("$LP[ACTIVE][NRCA] slot=$slot drop duplicate sig=${java.lang.Long.toHexString(d.sig)} num=${d.num}")
                        return@hookMethodNative chain.proceed(args)
                    }

                    // 仅在“接受新内容”时更新 sig，用于变化检测与稳定计数。
                    NrcaPoller.onSigAccepted(slot, d.sig)

                    // 主动有效且为新内容时才解析。
                    nrcaHandler.parseHalNrca(slot, 0, nrca)
                    chain.proceed(args)
                }

                Logger.log("$LP[RESP] hooked ${m.toGenericString()} (active query return)")
                hookedAnything = true
            } else {
                Logger.log("$LP[RESP] getNrcaInfoResponse not found")
            }
        } catch (t: Throwable) {
            Logger.logE("$LP[ERR][RESP] hook getNrcaInfoResponse failed", t)
        }

        return hookedAnything
    }

    private fun extractSlotFromResponse(respObj: Any): Int {
        // 1) this.mRadio.getSlotId()
        runCatching {
            val radioObj = XposedHelpers.getObjectField(respObj, "mRadio") ?: return@runCatching
            val m = radioObj.javaClass.methods.firstOrNull { it.name == "getSlotId" && it.parameterTypes.isEmpty() }
            val slot = (m?.invoke(radioObj) as? Int) ?: return@runCatching
            return slot
        }

        // 2) this.mSlotId
        runCatching {
            return XposedHelpers.getIntField(respObj, "mSlotId")
        }

        // 3) fallback
        return runCatching { NrcaTranslator.tryGetSlotId(respObj) }.getOrDefault(-1)
    }

    private fun cacheRadioProxyFromAny(anyObj: Any, slot: Int) {
        try {
            val radioObj = runCatching { XposedHelpers.getObjectField(anyObj, "mRadio") }.getOrNull() ?: return
            Logger.log("$LP[RADIO] cache mRadio slot=$slot class=${radioObj.javaClass.name}")
            NrcaPoller.onRadioProxySeen(slot, radioObj)
        } catch (t: Throwable) {
            Logger.logE("$LP[ERR][RADIO] cacheRadioProxyFromAny failed", t)
        }
    }

    private fun tryHookRequestNow(cl: ClassLoader): Boolean {
        return try {
            val proxyCls = XposedHelpers.findClassIfExists("com.oplus.radio.RadioProxy", cl) ?: return false

            val ms = proxyCls.declaredMethods.filter {
                it.name == "getNrcaInfo" && it.parameterTypes.size == 1
            }
            if (ms.isEmpty()) return false

            ms.forEach { m ->
                m.isAccessible = true
                XposedBridge.hookMethodNative(m) { chain ->
                    val slot = runCatching {
                        XposedHelpers.callMethod(chain.thisObject, "getSlotId") as? Int
                    }.getOrNull() ?: -1

                    val msg = chain.args.getOrNull(0) as? Message
                    val hasTarget = msg?.target != null
                    Logger.log(
                        "$LP[REQ] getNrcaInfo slot=$slot " +
                            "msg=${msg != null} target=$hasTarget what=${msg?.what ?: -1}"
                    )
                    chain.proceed()
                }
            }

            requestHooked.set(true)
            Logger.log("$LP[REQ] request hook completed methods=${ms.size}")
            true
        } catch (t: Throwable) {
            Logger.logE("$LP[ERR][REQ] tryHookRequestNow failed", t)
            false
        }
    }

    private fun seedRadioProxyFromIndicationInstances(indicationClass: Class<*>) {
        runCatching {
            val f = XposedHelpers.findFieldIfExists(indicationClass, "mInstance") ?: return@runCatching
            f.isAccessible = true
            val instances = f.get(null) as? Array<*> ?: return@runCatching
            instances.forEach { inst ->
                if (inst == null) return@forEach
                val slot = NrcaTranslator.tryGetSlotId(inst)
                cacheRadioProxyFromAny(inst, slot)
            }
            Logger.log("$LP[IND] seeded radio cache from mInstance size=${instances.size}")
        }.onFailure {
            Logger.logE("$LP[ERR][IND] seed from mInstance failed", it)
        }
    }

    // =============================================================================================
    // 主动优先仲裁：
    // - 主动成功：仅短暂压制被动（防止同批次旧包覆盖）
    // - 主动失败：放开被动兜底 15s
    // =============================================================================================
    private object ActiveFirstArbiter {
        private const val ACTIVE_SUPPRESS_PASSIVE_MS = 400L
        private const val ACTIVE_BAD_ALLOW_PASSIVE_MS = 15_000L

        private val lastActiveOkAt = ConcurrentHashMap<Int, Long>()
        private val lastActiveBadAt = ConcurrentHashMap<Int, Long>()

        fun onActiveArrived(slot: Int, valid: Boolean) {
            if (slot < 0) return
            val now = SystemClock.elapsedRealtime()
            if (valid) lastActiveOkAt[slot] = now else lastActiveBadAt[slot] = now
        }

        fun shouldAcceptPassive(slot: Int): Boolean {
            if (slot < 0) return true
            val now = SystemClock.elapsedRealtime()

            val okAge = now - (lastActiveOkAt[slot] ?: Long.MIN_VALUE)
            val badAge = now - (lastActiveBadAt[slot] ?: Long.MIN_VALUE)

            // 主动刚成功过：压制被动
            if (okAge in 0 until ACTIVE_SUPPRESS_PASSIVE_MS) return false

            // 主动近期失败：允许被动兜底
            if (badAge in 0 until ACTIVE_BAD_ALLOW_PASSIVE_MS) return true

            // 冷启动：允许被动
            if (!lastActiveOkAt.containsKey(slot) && !lastActiveBadAt.containsKey(slot)) return true

            // 主动很久没成功：允许被动
            return okAge > ACTIVE_SUPPRESS_PASSIVE_MS
        }
    }

    // =============================================================================================
    // 去抖：同 slot 同 sig 不重复 parse（ACTIVE/PASSIVE 分开计）
    // =============================================================================================
    private object Deduper {
        private const val FORCE_REEMIT_MS = 6_000L

        private data class Key(val slot: Int, val source: String)
        private val lastSig = ConcurrentHashMap<Key, Long>()
        private val lastNum = ConcurrentHashMap<Key, Int>()
        private val lastEmitAt = ConcurrentHashMap<Key, Long>()

        fun shouldAccept(slot: Int, source: String, sig: Long, num: Int): Boolean {
            val key = Key(slot, source)
            val now = SystemClock.elapsedRealtime()
            val ps = lastSig[key]
            val pn = lastNum[key]

            if (ps == sig && pn == num) {
                val age = now - (lastEmitAt[key] ?: 0L)
                if (age in 0 until FORCE_REEMIT_MS) return false
                Logger.log("$LP[DEDUP] force re-emit slot=$slot src=$source age=${age}ms")
            }

            lastSig[key] = sig
            lastNum[key] = num
            lastEmitAt[key] = now
            return true
        }
    }

    // =============================================================================================
    // 自适应轮询器（Adaptive Poller）
    // - sig 变化 -> burst 高频追踪（8s）
    // - sig 稳定 -> 1000ms -> 5000ms -> 10000ms 自动退频
    // - valid 很久没到（>12s）-> 降为 5000ms（避免刷爆）
    // =============================================================================================
    private object NrcaPoller {
        private const val FAST_MS = 1000L
        private const val MID_MS = 5000L
        private const val SLOW_MS = 10_000L

        private const val STALE_OK_MS = 12_000L
        private const val BURST_MS = 8_000L
        private const val INFLIGHT_TIMEOUT_MS = 8_000L

        private data class SlotState(
            var lastOkAt: Long = 0L,            // 最近一次 valid=true 到达时间（即使 duplicate 也更新）
            var lastSig: Long = Long.MIN_VALUE, // 最近一次“被接受的新内容”的 sig
            var stableCount: Int = 0,           // sig 连续不变计数（以“被接受的新内容”为准）
            var burstUntil: Long = 0L,          // 变化后 burst 窗口截止时间
            var inflightAt: Long = 0L,          // 最近一次主动请求发出时间（0 表示无 inflight）
            var lastRespSerial: Int = Int.MIN_VALUE
        )

        private val radios = ConcurrentHashMap<Int, Any>()       // slot -> RadioProxy
        private val states = ConcurrentHashMap<Int, SlotState>() // slot -> state

        private val started = AtomicBoolean(false)

        private val ht = HandlerThread("nrca-poller").apply { start() }
        private val h = Handler(ht.looper)
        private val resultHandler = Handler(ht.looper) { true }

        // per-slot 节流：避免某 slot 想慢时仍被全局 tick 带着跑
        private val lastFireAt = ConcurrentHashMap<Int, Long>()

        fun onRadioProxySeen(slot: Int, radio: Any) {
            if (slot < 0) return
            radios[slot] = radio
            states.putIfAbsent(slot, SlotState())
            if (started.compareAndSet(false, true)) {
                Logger.log("$LP[POLL] start (slot=$slot)")
                schedule(FAST_MS)
            }
        }

        /**
         * valid=true 到达（哪怕内容重复）
         * 关键点：避免 ageOk 因 duplicate 持续变大。
         */
        fun onNrcaArrived(slot: Int) {
            if (slot < 0) return
            val st = states.getOrPut(slot) { SlotState() }
            st.lastOkAt = SystemClock.elapsedRealtime()
        }

        /**
         * 主动响应到达：
         * 1) 结束 inflight（无论成功失败）
         * 2) 仅接受 serial 单调递增的响应，丢弃明显滞后的旧响应
         */
        fun onActiveResponse(slot: Int, serial: Int, error: Int): Boolean {
            if (slot < 0) return true
            val st = states.getOrPut(slot) { SlotState() }
            st.inflightAt = 0L

            if (serial < 0) return true

            val last = st.lastRespSerial
            if (last != Int.MIN_VALUE && serial <= last) {
                Logger.log("$LP[POLL] stale response drop slot=$slot serial=$serial last=$last err=$error")
                return false
            }

            st.lastRespSerial = serial
            return true
        }

        /**
         * 只有 Deduper 通过的“新内容”才调用：
         * - sig 变化：进入 burst
         * - sig 不变：stableCount++
         */
        fun onSigAccepted(slot: Int, sig: Long) {
            if (slot < 0) return
            val st = states.getOrPut(slot) { SlotState() }
            val now = SystemClock.elapsedRealtime()

            if (st.lastSig == Long.MIN_VALUE) {
                st.lastSig = sig
                st.stableCount = 0
                st.burstUntil = now + BURST_MS
                Logger.log("$LP[POLL] slot=$slot baseline sig=${java.lang.Long.toHexString(sig)} -> burst")
                return
            }

            if (sig != st.lastSig) {
                st.lastSig = sig
                st.stableCount = 0
                st.burstUntil = now + BURST_MS
                Logger.log("$LP[POLL] slot=$slot sig changed -> burst sig=${java.lang.Long.toHexString(sig)}")
            } else {
                st.stableCount++
            }
        }

        private fun schedule(delayMs: Long) {
            h.removeCallbacksAndMessages(null)
            h.postDelayed(::tick, delayMs)
        }

        private fun tick() {
            val now = SystemClock.elapsedRealtime()

            if (radios.isEmpty()) {
                schedule(MID_MS)
                return
            }

            var next = SLOW_MS

            radios.forEach { (slot, radioObj) ->
                val st = states.getOrPut(slot) { SlotState() }

                val ageOk = now - st.lastOkAt
                val inBurst = now < st.burstUntil

                val desired = when {
                    inBurst -> FAST_MS
                    ageOk > STALE_OK_MS -> MID_MS
                    st.stableCount >= 10 -> SLOW_MS
                    st.stableCount >= 3 -> MID_MS
                    else -> FAST_MS
                }

                next = minOf(next, desired)

                if (shouldFire(slot, desired, now)) {
                    Logger.log("$LP[POLL] fire slot=$slot desired=${desired}ms stable=${st.stableCount} ageOk=${ageOk}ms burst=$inBurst")
                    requestNrcaOnce(slot, radioObj)
                }
            }

            schedule(next)
        }

        private fun shouldFire(slot: Int, intervalMs: Long, now: Long): Boolean {
            val last = lastFireAt[slot] ?: 0L
            if (now - last >= intervalMs) {
                lastFireAt[slot] = now
                return true
            }
            return false
        }

        private fun requestNrcaOnce(slot: Int, radioObj: Any) {
            val st = states.getOrPut(slot) { SlotState() }
            val now = SystemClock.elapsedRealtime()
            val inflightAt = st.inflightAt
            if (inflightAt > 0L) {
                val age = now - inflightAt
                if (age in 0 until INFLIGHT_TIMEOUT_MS) {
                    Logger.log("$LP[POLL] skip slot=$slot inflight age=${age}ms")
                    return
                }
                Logger.log("$LP[POLL] inflight timeout slot=$slot age=${age}ms -> resend")
                st.inflightAt = 0L
            }

            try {
                val m = radioObj.javaClass.methods.firstOrNull {
                    it.name == "getNrcaInfo" && it.parameterTypes.size == 1
                } ?: run {
                    Logger.log("$LP[POLL][WARN] RadioProxy.getNrcaInfo(Message) not found slot=$slot cls=${radioObj.javaClass.name}")
                    return
                }

                // 必须带 target，ResponseHelper.sendMessageResponse 才能安全 sendToTarget。
                val msg = Message.obtain(resultHandler, 0x4E524341).apply {
                    arg1 = slot
                }
                m.invoke(radioObj, msg)
                st.inflightAt = now
            } catch (t: Throwable) {
                st.inflightAt = 0L
                Logger.logE("$LP[ERR][POLL] requestNrcaOnce slot=$slot failed", t)
            }
        }
    }
}

// =================================================================================================
// Digest 工具：用于验真 ACTIVE/PASSIVE 内容是否变化 + 生成 sig（FNV-1a）
// =================================================================================================

internal data class NrcaDigest(
    val num: Int,
    val sig: Long,
    val bands: String,
    val bws: String,
    val dlStates: String,
)

internal fun logNrcaDigest(tag: String, slot: Int, type: Int, nrca: Any) {
    val d = runCatching { buildNrcaDigest(nrca) }.getOrNull()
    if (d == null) {
        Logger.log("${IndicationHooks.LP}[$tag][NRCA] slot=$slot type=$type (digest failed) cls=${nrca.javaClass.name}")
        return
    }
    Logger.log(
        "${IndicationHooks.LP}[$tag][NRCA] slot=$slot type=$type num=${d.num} sig=${java.lang.Long.toHexString(d.sig)} " +
                "bands=${d.bands} bw=${d.bws} states=${d.dlStates}"
    )
}

internal fun buildNrcaDigest(nrca: Any): NrcaDigest {
    val numAny = runCatching { XposedHelpers.getObjectField(nrca, "numCarriers") }.getOrNull()
        ?: runCatching { XposedHelpers.getObjectField(nrca, "mNumCarriers") }.getOrNull()

    val num = when (numAny) {
        is Byte -> numAny.toInt()
        is Int -> numAny
        is Short -> numAny.toInt()
        else -> 0
    }.coerceAtLeast(0)

    val cfgAny = runCatching { XposedHelpers.getObjectField(nrca, "carrierConfig") }.getOrNull()
        ?: runCatching { XposedHelpers.getObjectField(nrca, "mCarrierConfig") }.getOrNull()

    val arr: Array<*> = (cfgAny as? Array<*>) ?: emptyArray<Any?>()

    fun mhzOf(dlBwRaw: Int): Int = NrcaTranslator.bandwidthMhzOrNull(dlBwRaw) ?: 0
    fun bandStr(bandRaw: Int): String = "n$bandRaw"

    val limit = minOf(num, arr.size, 8)

    var h = 1469598103934665603L
    fun mix(v: Int) { h = (h xor v.toLong()) * 1099511628211L }

    mix(num)

    val bands = ArrayList<String>(limit)
    val bws = ArrayList<String>(limit)
    val states = ArrayList<String>(limit)

    for (i in 0 until limit) {
        val item = arr[i] ?: continue
        val band = runCatching { XposedHelpers.getIntField(item, "band") }.getOrDefault(0)
        val dlBw = runCatching { XposedHelpers.getIntField(item, "dlBandwidth") }.getOrDefault(0)
        val dlState = runCatching { XposedHelpers.getIntField(item, "dlState") }.getOrDefault(0)

        bands.add(bandStr(band))
        bws.add(mhzOf(dlBw).toString())
        states.add(dlState.toString())

        mix(band)
        mix(dlBw)
        mix(dlState)
    }

    return NrcaDigest(
        num = num,
        sig = h,
        bands = bands.joinToString(prefix = "[", postfix = "]"),
        bws = bws.joinToString(prefix = "[", postfix = "]"),
        dlStates = states.joinToString(prefix = "[", postfix = "]")
    )
}
