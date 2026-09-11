@file:Suppress("PrivatePropertyName", "SameParameterValue")

package com.nvmex.networkhelper.xposed.hooks

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.SystemClock
import android.util.Log
import com.nvmex.networkhelper.xposed.broad.BroadcastHelper
import com.nvmex.networkhelper.xposed.handler.LteCaHandler
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LTE CA Trace Hooks (Oplus Subsys stack)
 *
 * 目标：
 * 1) hook 请求入队：com.oplus.subsys.AidlBase.obtainRequest(String, Message)
 * 2) hook 回包出队：AidlBase.processResponse(C5912SubsysResponseInfo)
 * 3) hook 回包完成：AidlBase.processResponseDone(rr, info, ret, ...)
 *    - 若 ret 为 RadioLteCaInfo，则解析 scell 列表字段
 * 4) 可选保险：SubsysRadioResponse.responseLteCaInfo(info, C5997LteCaInfo)
 *
 * 日志：
 * - Logcat：优先走你现有 Logger（若无则 Logcat）
 * - 文件：优先 ctx.getExternalFilesDir()/networkhelper/log
 *        其次尝试 /sdcard/Download/networkhelper/log
 */
object LteCaTraceHooks {

    private const val LP = "[LTECA][HOOK]"
    private const val TAG = "NetworkHelper999"

    private val installedCls = ConcurrentHashMap<Int, AtomicBoolean>()

    private val attachHooked = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    // 记录最近看到 request/resp 时间（按 slot）
    private val lastReqMsBySlot = ConcurrentHashMap<Int, Long>()
    private val lastRespMsBySlot = ConcurrentHashMap<Int, Long>()

    private val fileLogger = FileLogger()
    private val broadcastHelper = BroadcastHelper()
    private val lteCaHandler = LteCaHandler(broadcastHelper)

    fun install(classLoader: ClassLoader) {
        // ✅ per-classloader gate
        val key = System.identityHashCode(classLoader)
        val gate = installedCls.getOrPut(key) { AtomicBoolean(false) }

        if (!gate.compareAndSet(false, true)) {
            log("$LP[SKIP] already installed for clKey=$key cl=$classLoader")
            return
        }

        log("$LP[START] install hooks... clKey=$key cl=$classLoader")

        // ⚠️ 可选：
        // 你如果已经在 HookEntry 的 Application.attach 里 setAppContext(ctx)，
        // 这里其实没必要再 hookApplicationAttach()，否则会重复 hook。
        // 如果你想 LteCaTraceHooks 自己也能独立工作（不依赖 HookEntry），就保留它。
        // hookApplicationAttach()

        // 关键：AidlBase 链路（请求/回包/完成）
        hookAidlBaseObtainRequest(classLoader)
        hookAidlBaseProcessResponse(classLoader)
        hookAidlBaseProcessResponseDone(classLoader)

        // 保险：LTECA 专用回包点（可选但建议保留）
        hookSubsysRadioIndicationLteCaInfoInd(classLoader)
        hookSubsysRadioResponseLteCaInfo(classLoader)

        log("$LP[OK] install finished for clKey=$key")
    }


    fun setAppContext(ctx: Context) {
        appContext = ctx.applicationContext
        fileLogger.ensureInited(appContext)
        broadcastHelper.setAppContext(ctx)
        log("$LP[CTX] context set: ${ctx.packageName}")
    }

    // -------------------------
    // Hook 0：拿 Context（用于落盘）
    // -------------------------
    private fun hookApplicationAttach() {
        if (!attachHooked.compareAndSet(false, true)) return

        try {
            XposedBridge.hookAllMethodsNative(Application::class.java, "attach") { chain ->
                val result = chain.proceed()
                val ctx = chain.args.getOrNull(0) as? Context
                if (ctx != null) setAppContext(ctx)
                result
            }
            log("$LP[ATTACH] hooked Application.attach")
        } catch (t: Throwable) {
            logE("$LP[ERR][ATTACH] hook failed", t)
        }
    }

    // -------------------------
    // Hook 1：请求入队（AidlBase.obtainRequest）
    // -------------------------
    private fun hookAidlBaseObtainRequest(cl: ClassLoader) {
        try {
            val baseCls = XposedHelpers.findClassIfExists("com.oplus.subsys.AidlBase", cl)
                ?: run {
                    log("$LP[REQ] AidlBase not found")
                    return
                }

            XposedBridge.hookAllMethodsNative(baseCls, "obtainRequest") { chain ->
                val result = chain.proceed()
                // obtainRequest(String request, Message result)
                val reqName = (chain.args.getOrNull(0) as? String).orEmpty()
                val rr = result ?: return@hookAllMethodsNative result

                val slot = getSlotIdFromAidlBase(chain.thisObject)
                val serial = runCatching { XposedHelpers.getIntField(rr, "mSerial") }.getOrDefault(-1)
                val req = runCatching { XposedHelpers.getObjectField(rr, "mRequest")?.toString() }.getOrNull()
                val now = SystemClock.elapsedRealtime()
                lastReqMsBySlot[slot] = now

                val msg = "$LP[REQ] t=$now slot=$slot name=$reqName serial=$serial req=$req"
                log(msg); fileLogger.append(msg)

                // 只对 CA/Lte 相关请求打栈，避免爆炸
                if (reqName.contains("Ca", ignoreCase = true) || reqName.contains("Lte", ignoreCase = true)) {
                    val st = Throwable().stackTrace
                        .take(10)
                        .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                    val msg2 = "$LP[REQ][STACK] slot=$slot name=$reqName serial=$serial $st"
                    log(msg2); fileLogger.append(msg2)
                }
                result
            }

            log("$LP[REQ] hooked AidlBase.obtainRequest(String, Message)")
        } catch (t: Throwable) {
            logE("$LP[ERR][REQ] hookAidlBaseObtainRequest failed", t)
        }
    }

    // -------------------------
    // Hook 2：回包出队（AidlBase.processResponse）
    // -------------------------
    private fun hookAidlBaseProcessResponse(cl: ClassLoader) {
        try {
            val baseCls = XposedHelpers.findClassIfExists("com.oplus.subsys.AidlBase", cl)
                ?: run {
                    log("$LP[RESP] AidlBase not found")
                    return
                }

            XposedBridge.hookAllMethodsNative(baseCls, "processResponse") { chain ->
                val result = chain.proceed()
                // processResponse(C5912SubsysResponseInfo responseInfo) -> SubsysRequest?
                val info = chain.args.getOrNull(0) ?: return@hookAllMethodsNative result
                val rr = result ?: run {
                    // rr 为空也打一下，说明 response 没找到对应 request（异常）
                    val slot = getSlotIdFromAidlBase(chain.thisObject)
                    val serial = getIntFieldCompat(info, "serial", -1)
                    val err = getIntFieldCompat(info, "error", -999)
                    val now = SystemClock.elapsedRealtime()
                    val msg = "$LP[RESP][POP][NULL] t=$now slot=$slot serial=$serial err=$err"
                    log(msg); fileLogger.append(msg)
                    return@hookAllMethodsNative result
                }

                val slot = getSlotIdFromAidlBase(chain.thisObject)
                val serial = getIntFieldCompat(info, "serial", -1)
                val err = getIntFieldCompat(info, "error", -999)
                val req = runCatching { XposedHelpers.getObjectField(rr, "mRequest")?.toString() }.getOrNull()
                val now = SystemClock.elapsedRealtime()
                lastRespMsBySlot[slot] = now

                val msg = "$LP[RESP][POP] t=$now slot=$slot serial=$serial err=$err req=$req"
                log(msg); fileLogger.append(msg)
                result
            }

            log("$LP[RESP] hooked AidlBase.processResponse(...)")
        } catch (t: Throwable) {
            logE("$LP[ERR][RESP] hookAidlBaseProcessResponse failed", t)
        }
    }

    // -------------------------
    // Hook 3：回包完成（AidlBase.processResponseDone）
    // 在这里 ret 已经是上层对象（比如 RadioLteCaInfo），更适合解析
    // -------------------------
    private fun hookAidlBaseProcessResponseDone(cl: ClassLoader) {
        try {
            val baseCls = XposedHelpers.findClassIfExists("com.oplus.subsys.AidlBase", cl)
                ?: run {
                    log("$LP[RESPDONE] AidlBase not found")
                    return
                }

            XposedBridge.hookAllMethodsNative(baseCls, "processResponseDone") { chain ->
                // processResponseDone(SubsysRequest rr, C5912SubsysResponseInfo info, Object ret, ...)
                val rr = chain.args.getOrNull(0)
                val info = chain.args.getOrNull(1)
                val ret = chain.args.getOrNull(2)

                if (rr != null && info != null) {
                    val slot = getSlotIdFromAidlBase(chain.thisObject)
                    val serial = getIntFieldCompat(rr, "mSerial", -1)
                    val req = runCatching { XposedHelpers.getObjectField(rr, "mRequest")?.toString() }.getOrNull()
                    val err = getIntFieldCompat(info, "error", -999)
                    val retType = ret?.javaClass?.name ?: "null"
                    val now = SystemClock.elapsedRealtime()

                    val msg = "$LP[RESP][DONE] t=$now slot=$slot serial=$serial err=$err req=$req ret=$retType"
                    log(msg); fileLogger.append(msg)

                    // 若返回对象是 LTECA（上层模型），解析 scell 明细
                    if (ret != null && retType.contains("RadioLteCaInfo")) {
                        val digest = buildRadioLteCaInfoDigest(ret)
                        val msg2 = "$LP[LTECA] t=$now slot=$slot serial=$serial $digest"
                        log(msg2); fileLogger.append(msg2)
                        lteCaHandler.parseRadioLteCaInfo(slot, ret)
                    }
                }
                chain.proceed()
            }

            log("$LP[RESPDONE] hooked AidlBase.processResponseDone(...)")
        } catch (t: Throwable) {
            logE("$LP[ERR][RESPDONE] hookAidlBaseProcessResponseDone failed", t)
        }
    }

    // -------------------------
    // 保险：SubsysRadioResponse.responseLteCaInfo(...)
    // 你贴出来的方法体就在这里，说明这个点一定会触发（前提是有人请求 LTECA）
    // -------------------------
    private fun hookSubsysRadioIndicationLteCaInfoInd(cl: ClassLoader) {
        try {
            val indCls = XposedHelpers.findClassIfExists("com.oplus.radio.SubsysRadioIndication", cl)
                ?: run {
                    log("$LP[SUBSYSIND] SubsysRadioIndication not found")
                    return
                }

            val m = indCls.declaredMethods.firstOrNull {
                it.name == "radioLteCaInfoInd" && it.parameterTypes.size == 2
            } ?: run {
                log("$LP[SUBSYSIND] radioLteCaInfoInd not found")
                return
            }

            m.isAccessible = true
            XposedBridge.hookMethodNative(m) { chain ->
                val raw = chain.args.getOrNull(1)
                val caInfo = raw as? IntArray

                if (caInfo != null) {
                    val slot = runCatching {
                        val radioObj = runCatching { XposedHelpers.getObjectField(chain.thisObject, "mRadio") }.getOrNull()
                        when {
                            radioObj != null -> runCatching {
                                val mm = radioObj.javaClass.methods.firstOrNull {
                                    it.name == "getSlotId" && it.parameterTypes.isEmpty()
                                }
                                (mm?.invoke(radioObj) as? Int) ?: -1
                            }.getOrDefault(-1)

                            else -> runCatching { XposedHelpers.getIntField(chain.thisObject, "mSlotId") }.getOrDefault(-1)
                        }
                    }.getOrDefault(-1)

                    val now = SystemClock.elapsedRealtime()
                    val msg = "$LP[SUBSYSIND][LTECA] t=$now slot=$slot len=${caInfo.size}"
                    log(msg); fileLogger.append(msg)
                    lteCaHandler.parseRilLteCaInfo(slot, caInfo)
                }
                chain.proceed()
            }

            log("$LP[SUBSYSIND] hooked ${m.toGenericString()}")
        } catch (t: Throwable) {
            logE("$LP[ERR][SUBSYSIND] hookSubsysRadioIndicationLteCaInfoInd failed", t)
        }
    }

    private fun hookSubsysRadioResponseLteCaInfo(cl: ClassLoader) {
        try {
            val respCls = XposedHelpers.findClassIfExists("com.oplus.radio.SubsysRadioResponse", cl)
                ?: run {
                    log("$LP[SUBSYSRESP] SubsysRadioResponse not found")
                    return
                }

            val m = respCls.declaredMethods.firstOrNull {
                it.name == "responseLteCaInfo" && it.parameterTypes.size == 2
            } ?: run {
                log("$LP[SUBSYSRESP] responseLteCaInfo not found")
                return
            }

            m.isAccessible = true
            XposedBridge.hookMethodNative(m) { chain ->
                val info = chain.args.getOrNull(0)
                val raw = chain.args.getOrNull(1) // C5997LteCaInfo (vendor parcel)

                val slot = runCatching {
                    // SubsysRadioResponse 里一般持有 mRadio (RadioProxy) 或 mInstanceId
                    val radioObj = runCatching { XposedHelpers.getObjectField(chain.thisObject, "mRadio") }.getOrNull()
                    when {
                        radioObj != null -> runCatching {
                            val mm = radioObj.javaClass.methods.firstOrNull { it.name == "getSlotId" && it.parameterTypes.isEmpty() }
                            (mm?.invoke(radioObj) as? Int) ?: -1
                        }.getOrDefault(-1)

                        else -> runCatching { XposedHelpers.getIntField(chain.thisObject, "mSlotId") }.getOrDefault(-1)
                    }
                }.getOrDefault(-1)

                val serial = if (info != null) getIntFieldCompat(info, "serial", -1) else -1
                val err = if (info != null) getIntFieldCompat(info, "error", -999) else -999

                val now = SystemClock.elapsedRealtime()
                val digest = buildVendorLteCaInfoDigest(raw)

                val msg = "$LP[SUBSYSRESP][LTECA] t=$now slot=$slot serial=$serial err=$err $digest"
                log(msg); fileLogger.append(msg)
                lteCaHandler.parseVendorLteCaInfo(slot, raw)
                chain.proceed()
            }

            log("$LP[SUBSYSRESP] hooked ${m.toGenericString()}")
        } catch (t: Throwable) {
            logE("$LP[ERR][SUBSYSRESP] hookSubsysRadioResponseLteCaInfo failed", t)
        }
    }

    // -------------------------
    // Digest：上层 RadioLteCaInfo（推荐）
    // -------------------------
    private fun buildRadioLteCaInfoDigest(radioLteCaInfo: Any): String {
        // 字段名不同 ROM 可能略有差异，这里做“多字段兼容探测”
        val scellNum = getIntFieldCompat(radioLteCaInfo, "scellNum",
            getIntFieldCompat(radioLteCaInfo, "mScellNum", -1)
        )

        val listObj = getObjFieldCompat(radioLteCaInfo, "lteCaScellInfoList",
            getObjFieldCompat(radioLteCaInfo, "mLteCaScellInfoList", null)
        )

        val arr = (listObj as? Array<*>) ?: emptyArray<Any?>()
        val take = minOf(arr.size, 4)

        val parts = ArrayList<String>(take)
        for (i in 0 until take) {
            val item = arr[i] ?: continue

            val scellIdx = getIntFieldCompat(item, "scellIdx",
                getIntFieldCompat(item, "mScellIdx", -1)
            )

            val scellInfo = getObjFieldCompat(item, "scellInfo",
                getObjFieldCompat(item, "mScellInfo", null)
            )

            if (scellInfo == null) {
                parts.add("[$i] idx=$scellIdx scellInfo=null")
                continue
            }

            // RadioScellInfo 构造里传的是：
            // (pci, ulCh, dlCh, rsrp, rsrq, sinr) —— 字段名可能是 pci/ulChannel/dlChannel 或 sccPhyCellId 等
            val pci = getIntFieldCompat(scellInfo, "pci",
                getIntFieldCompat(scellInfo, "scc_phy_cellid",
                    getIntFieldCompat(scellInfo, "sccPhyCellId", -1)
                )
            )

            val ul = getIntFieldCompat(scellInfo, "ulChannel",
                getIntFieldCompat(scellInfo, "scc_ul_channel",
                    getIntFieldCompat(scellInfo, "sccUlChannel", -1)
                )
            )

            val dl = getIntFieldCompat(scellInfo, "dlChannel",
                getIntFieldCompat(scellInfo, "scc_dl_channel",
                    getIntFieldCompat(scellInfo, "sccDlChannel", -1)
                )
            )

            val rsrp = getIntFieldCompat(scellInfo, "rsrp", 99999)
            val rsrq = getIntFieldCompat(scellInfo, "rsrq", 99999)

            val sinr = getIntFieldCompat(scellInfo, "sinr",
                getIntFieldCompat(scellInfo, "rs_sinr",
                    getIntFieldCompat(scellInfo, "rsSinr", 99999)
                )
            )

            parts.add("[$i] idx=$scellIdx pci=$pci dl=$dl ul=$ul rsrp=$rsrp rsrq=$rsrq sinr=$sinr")
        }

        return "scellNum=$scellNum list=${arr.size} ${parts.joinToString(" | ")}"
    }

    // -------------------------
    // Digest：vendor parcel（只是保险/摸底）
    // -------------------------
    private fun buildVendorLteCaInfoDigest(vendorInfo: Any?): String {
        if (vendorInfo == null) return "vendorLteCaInfo=null"

        val num = getIntFieldCompat(vendorInfo, "scell_num",
            getIntFieldCompat(vendorInfo, "scellNum", -1)
        )

        val listObj = getObjFieldCompat(vendorInfo, "scell_info_list",
            getObjFieldCompat(vendorInfo, "scellInfoList", null)
        )

        val arr = (listObj as? Array<*>) ?: emptyArray<Any?>()
        val take = minOf(arr.size, 2)

        val parts = ArrayList<String>(take)
        for (i in 0 until take) {
            val item = arr[i] ?: continue
            val idx = getIntFieldCompat(item, "scell_idx",
                getIntFieldCompat(item, "scellIdx", -1)
            )
            val scellInfo = getObjFieldCompat(item, "scell_info",
                getObjFieldCompat(item, "scellInfo", null)
            )
            if (scellInfo == null) {
                parts.add("[$i] idx=$idx scell_info=null")
                continue
            }

            val pci = getIntFieldCompat(scellInfo, "scc_phy_cellid", -1)
            val ul = getIntFieldCompat(scellInfo, "scc_ul_channel", -1)
            val dl = getIntFieldCompat(scellInfo, "scc_dl_channel", -1)
            val rsrp = getIntFieldCompat(scellInfo, "rsrp", 99999)
            val rsrq = getIntFieldCompat(scellInfo, "rsrq", 99999)
            val sinr = getIntFieldCompat(scellInfo, "rs_sinr", 99999)

            parts.add("[$i] idx=$idx pci=$pci dl=$dl ul=$ul rsrp=$rsrp rsrq=$rsrq sinr=$sinr")
        }

        return "vendor scellNum=$num list=${arr.size} ${parts.joinToString(" | ")}"
    }

    // -------------------------
    // Utils：slot/field helpers
    // -------------------------
    private fun getSlotIdFromAidlBase(baseObj: Any): Int {
        // AidlBase 已经有 mInstanceId（你的反编译就写了）
        return runCatching { XposedHelpers.getIntField(baseObj, "mInstanceId") }.getOrDefault(-1)
    }

    private fun getIntFieldCompat(obj: Any, name: String, def: Int): Int {
        return runCatching { XposedHelpers.getIntField(obj, name) }.getOrDefault(def)
    }

    private fun getObjFieldCompat(obj: Any, name: String, def: Any?): Any? {
        return runCatching { XposedHelpers.getObjectField(obj, name) }.getOrDefault(def)
    }

    // -------------------------
    // Logging
    // -------------------------
    private fun log(msg: String) {
        // 优先走你自定义 Logger（如果存在），否则 Logcat
        try {
            val loggerCls = runCatching { Class.forName("com.nvmex.networkhelper.xposed.Logger") }.getOrNull()
            val m = loggerCls?.methods?.firstOrNull { it.name == "log" && it.parameterTypes.size == 1 }
            if (m != null) {
                m.invoke(null, msg)
                return
            }
        } catch (_: Throwable) { }
        Log.i(TAG, msg)
    }

    private fun logE(msg: String, t: Throwable) {
        try {
            val loggerCls = runCatching { Class.forName("com.nvmex.networkhelper.xposed.Logger") }.getOrNull()
            val m = loggerCls?.methods?.firstOrNull { it.name == "logE" && it.parameterTypes.size == 2 }
            if (m != null) {
                m.invoke(null, msg, t)
                return
            }
        } catch (_: Throwable) { }
        Log.e(TAG, msg, t)
    }

    // -------------------------
    // File Logger（串行落盘）
    // -------------------------
    private class FileLogger {

        private val inited = AtomicBoolean(false)
        private val ht = HandlerThread("lteca-filelog").apply { start() }
        private val h = Handler(ht.looper)

        @Volatile
        private var dir: File? = null

        private val fmtHour = SimpleDateFormat("yyyyMMdd-HH", Locale.US)
        private val dfTs = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        fun ensureInited(ctx: Context?) {
            if (inited.get()) return
            if (ctx == null) return

            val d = resolveLogDir(ctx)
            dir = d
            inited.set(true)

            Log.i(TAG, "$LP[FILE] dir=${d.absolutePath}")
        }

        fun append(line: String) {
            val d = dir ?: return
            val now = System.currentTimeMillis()
            val file = File(d, "lteca-${fmtHour.format(Date(now))}.log")
            val text = "${dfTs.format(Date(now))} $line\n"

            h.post {
                runCatching {
                    FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.onFailure {
                    Log.e(TAG, "$LP[FILE][ERR] append failed: ${it.message}", it)
                }
            }
        }

        private fun resolveLogDir(ctx: Context): File {
            // 1) 优先：外部私有目录（稳定可写，不吃 scoped storage 限制）
            runCatching {
                val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                val p = File(base, "networkhelper/log")
                if (!p.exists()) p.mkdirs()
                // probe
                val probe = File(p, ".probe")
                FileOutputStream(probe, true).use { it.write("ok\n".toByteArray()) }
                probe.delete()
                return p
            }

            // 2) 其次：公共 Download（可能被限制，作为可选）
            val primary = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "networkhelper/log"
            )
            runCatching {
                if (!primary.exists()) primary.mkdirs()
                val probe = File(primary, ".probe")
                FileOutputStream(probe, true).use { it.write("ok\n".toByteArray()) }
                probe.delete()
                return primary
            }

            // 3) 兜底：内部 filesDir
            val fallback = File(ctx.filesDir, "networkhelper/log")
            if (!fallback.exists()) fallback.mkdirs()
            return fallback
        }
    }
}
