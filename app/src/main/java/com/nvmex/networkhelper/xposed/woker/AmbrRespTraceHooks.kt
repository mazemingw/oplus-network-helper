package com.nvmex.networkhelper.xposed.woker

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AMBR 专用 trace（不混 NRCA）：
 * - 自动适配类名/类加载器差异
 * - 关键词 hook：所有包含 "NwRateLimiting" 的方法
 * - 主动轮询：调用 getNwRateLimitingInfo(Message)
 * - 落盘：/sdcard/Download/networkhelper/log/ambr_yyyyMMdd.txt
 *
 * ✅增强：
 * - 在 RESP 回包处直接对 NwRateLimitingInfo 打 digest（优先 param.args[1]）
 * - RADIO hook slot=-1 时，从缓存 radios 反查补 slot
 * - 兜底：从 args / 1~2 层嵌套尝试提取 digest，必要时输出字段名（节流）
 */
object AmbrRespTraceHooks {

    private const val LP = "[AMBR][TRACE]"

    private val installed = AtomicBoolean(false)

    @Volatile private var appCtx: Context? = null

    // slot -> RadioProxy instance
    private val radios = ConcurrentHashMap<Int, Any>()
    private val pollStarted = AtomicBoolean(false)

    private val ht = HandlerThread("ambr-poller").apply { start() }
    private val h = Handler(ht.looper)

    private const val POLL_MS = 1500L

    // ---- 候选类名（你现在的 ROM 可能在这些包里）----
    private val RESP_CLASS_CANDIDATES = listOf(
        "com.oplus.radio.SubsysRadioResponse",
        "com.oplus.subsys.radio.SubsysRadioResponse",
        "com.oplus.subsys.SubsysRadioResponse",
        "com.oplus.subsys.radio.SubsysRadioResponseJ",
        "com.oplus.subsys.radio.SubsysRadioResponseImpl"
    )

    private val RADIO_CLASS_CANDIDATES = listOf(
        "com.oplus.radio.RadioProxy",
        "com.oplus.subsys.radio.RadioProxy",
        "com.oplus.subsys.radio.RadioProxyJ",
        "com.oplus.subsys.RadioProxy",
        "com.oplus.subsys.radio.RadioProxyImpl"
    )

    fun setAppContext(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /**
     * ✅ 入口：带重试（因为有时 classpath/加载时机比较刁钻）
     * 你在 XposedInit 里直接调用 tryInstallWithRetry(lpparam.classLoader, "subsys-init") 即可。
     */
    fun tryInstallWithRetry(appCl: ClassLoader, from: String) {
        if (!installed.compareAndSet(false, true)) {
            Logger.log("$LP install already done, skip from=$from")
            return
        }

        Logger.log("$LP[START] tryInstallWithRetry from=$from appCl=$appCl")
        val main = Handler(Looper.getMainLooper())
        val maxTries = 30
        val intervalMs = 200L
        val tries = intArrayOf(0)

        fun attempt() {
            tries[0]++

            val ok = installInternal(appCl)

            if (ok) {
                Logger.log("$LP[OK] install success tries=${tries[0]} from=$from")
                appendToFile("$LP[OK] install success tries=${tries[0]} from=$from")
                return
            }

            if (tries[0] < maxTries) {
                main.postDelayed({ attempt() }, intervalMs)
            } else {
                Logger.log("$LP[GIVEUP] install failed after $maxTries tries from=$from")
                appendToFile("$LP[GIVEUP] install failed after $maxTries tries from=$from")
            }
        }

        attempt()
    }

    /**
     * 真正安装逻辑：返回 true 表示“至少 hook 到了一个关键点”
     */
    private fun installInternal(appCl: ClassLoader): Boolean {
        val bootCl = runCatching { XposedBridge.BOOTCLASSLOADER }.getOrNull()

        dumpCandidates(appCl, bootCl)

        // 1) 先找 Response（最关键：抓回包）
        val respCls = findFirstExistingClass(RESP_CLASS_CANDIDATES, appCl, bootCl)
        // 2) 再找 RadioProxy（用于主动轮询/证明主动拉取）
        val radioCls = findFirstExistingClass(RADIO_CLASS_CANDIDATES, appCl, bootCl)

        var hookedAnything = false

        if (respCls != null) {
            hookedAnything = hookAllNwRateMethods(respCls, kind = "RESP") || hookedAnything
        } else {
            Logger.log("$LP[WARN] Response class not found (all candidates failed)")
        }

        if (radioCls != null) {
            hookedAnything = hookAllNwRateMethods(radioCls, kind = "RADIO") || hookedAnything
        } else {
            Logger.log("$LP[WARN] RadioProxy class not found (all candidates failed)")
        }

        if (hookedAnything) {
            Logger.log("$LP install done (hookedAnything=true)")
            appendToFile("$LP install done (hookedAnything=true)")
        } else {
            Logger.log("$LP install done (hookedAnything=false)")
        }

        return hookedAnything
    }

    /**
     * 把“候选类名在不同 classLoader 下是否能加载”全打印出来。
     * 你看到这段日志就能秒判断：是包名不对，还是 classLoader 不对。
     */
    private fun dumpCandidates(appCl: ClassLoader, bootCl: ClassLoader?) {
        fun probe(name: String, cl: ClassLoader?): String {
            if (cl == null) return "cl=null"
            return try {
                XposedHelpers.findClass(name, cl)
                "OK"
            } catch (_: Throwable) {
                "NO"
            }
        }

        Logger.log("$LP[DUMP] appCl=$appCl bootCl=$bootCl")

        RESP_CLASS_CANDIDATES.forEach { n ->
            Logger.log("$LP[DUMP][RESP] $n app=${probe(n, appCl)} boot=${probe(n, bootCl)}")
        }
        RADIO_CLASS_CANDIDATES.forEach { n ->
            Logger.log("$LP[DUMP][RADIO] $n app=${probe(n, appCl)} boot=${probe(n, bootCl)}")
        }
    }

    private fun findFirstExistingClass(names: List<String>, appCl: ClassLoader, bootCl: ClassLoader?): Class<*>? {
        // 优先 appCl（SubsystemApp.apk 的 PathClassLoader），再试 bootCl
        for (n in names) {
            runCatching { return XposedHelpers.findClass(n, appCl) }
        }
        if (bootCl != null) {
            for (n in names) {
                runCatching { return XposedHelpers.findClass(n, bootCl) }
            }
        }
        return null
    }

    /**
     * 关键词 hook：类里所有包含 NwRateLimiting 的方法都 hook
     *
     * - Response 侧：会命中 responseNwRateLimitingInfo(...) 或 getNwRateLimitingInfoResponse(...)
     * - RadioProxy 侧：会命中 getNwRateLimitingInfo(Message)、setNwRateLimitingDetectCfg(...)
     */
    private fun hookAllNwRateMethods(cls: Class<*>, kind: String): Boolean {
        val targets = cls.declaredMethods
            .filter { it.name.contains("NwRateLimiting", ignoreCase = false) }

        if (targets.isEmpty()) {
            Logger.log("$LP[$kind][WARN] no method contains 'NwRateLimiting' in ${cls.name}")
            return false
        }

        targets.forEach { m ->
            runCatching {
                m.isAccessible = true
                XposedBridge.hookMethodNative(m) { chain ->
                    val args = chain.args.toTypedArray()
                    var slot = extractSlotBestEffort(chain.thisObject)
                    // ✅修复：RadioProxy hook 经常 slot=-1，用缓存反查补一下
                    slot = resolveSlotFromCache(chain.thisObject, slot)

                    val t = SystemClock.elapsedRealtime()

                    // 只打摘要，避免 log 爆炸
                    val argsSig = buildArgsSignature(args)
                    val line = "$LP[$kind][HIT] t=$t slot=$slot ${cls.simpleName}.${m.name}($argsSig)"
                    Logger.log(line)
                    appendToFile(line)

                    // 尝试缓存 mRadio（很多 response/indication wrapper 里都有）
                    cacheRadioFromAny(chain.thisObject, slot)

                    // ✅核心增强：打印 digest
                    // - 优先：RESP 且第二个参数就是 NwRateLimitingInfo（你日志已经证明）
                    // - 兜底：扫描 args / 嵌套字段
                    val digests = when {
                        kind == "RESP" && args.size >= 2 -> {
                            val limit = args[1]
                            listOfNotNull(tryDigestFromAny(limit))
                        }
                        else -> dumpLimitDigestsFromArgs(args)
                    }.distinct()

                    if (digests.isNotEmpty()) {
                        digests.forEachIndexed { idx, d ->
                            val dLine = "$LP[$kind][DIGEST#$idx] slot=$slot ${cls.simpleName}.${m.name} -> $d"
                            Logger.log(dLine)
                            appendToFile(dLine)
                        }
                    } else {
                        val dLine = "$LP[$kind][DIGEST] slot=$slot ${cls.simpleName}.${m.name} -> (none)"
                        Logger.log(dLine)
                        appendToFile(dLine)

                        // ✅仅当 none 时：轻量打印字段名（节流，帮你定位真实字段）
                        val f = buildFieldNameDump(chain.thisObject, args)
                        if (f.isNotBlank()) {
                            val fLine = "$LP[$kind][FIELDS] slot=$slot ${cls.simpleName}.${m.name} -> $f"
                            Logger.log(fLine)
                            appendToFile(fLine)
                        }
                    }
                    chain.proceed(args)
                }

                Logger.log("$LP[$kind] hooked ${m.toGenericString()}")
                appendToFile("$LP[$kind] hooked ${m.name}")
            }.onFailure {
                Logger.logE("$LP[$kind][ERR] hook ${m.name} failed", it)
            }
        }

        return true
    }

    private fun buildArgsSignature(args: Array<Any?>): String {
        if (args.isEmpty()) return ""
        return args.joinToString(",") { a ->
            if (a == null) "null" else a.javaClass.simpleName
        }
    }

    /**
     * 尝试从 thisObj 推断 slot：
     * - this.getSlotId()
     * - this.mRadio.getSlotId()
     * - this.mSlotId / this.mInstanceId
     * - 失败返回 -1
     */
    private fun extractSlotBestEffort(thisObj: Any): Int {
        // 0) 如果 thisObj 本身就是 RadioProxy：尝试 thisObj.getSlotId()
        runCatching {
            val m = thisObj.javaClass.methods.firstOrNull { it.name == "getSlotId" && it.parameterTypes.isEmpty() }
            val slot = (m?.invoke(thisObj) as? Int)
            if (slot != null) return slot
        }

        // 1) this.mRadio.getSlotId()
        runCatching {
            val radioObj = XposedHelpers.getObjectField(thisObj, "mRadio") ?: return@runCatching
            val m = radioObj.javaClass.methods.firstOrNull { it.name == "getSlotId" && it.parameterTypes.isEmpty() }
            val slot = (m?.invoke(radioObj) as? Int) ?: return@runCatching
            return slot
        }

        // 2) 常见字段：mSlotId / mInstanceId
        runCatching { return XposedHelpers.getIntField(thisObj, "mSlotId") }
        runCatching { return XposedHelpers.getIntField(thisObj, "mInstanceId") }

        return -1
    }

    /**
     * ✅slot 兜底：如果 slot=-1，尝试用 radios 反查 thisObj（RadioProxy 自身）
     */
    private fun resolveSlotFromCache(thisObj: Any, slot: Int): Int {
        if (slot >= 0) return slot
        // 这里用 === 做引用相等，避免 equals() 干扰
        radios.forEach { (k, v) ->
            if (v === thisObj) return k
        }
        return -1
    }

    private fun cacheRadioFromAny(anyObj: Any, slot: Int) {
        if (slot < 0) return
        val radioObj = runCatching { XposedHelpers.getObjectField(anyObj, "mRadio") }.getOrNull() ?: return

        radios[slot] = radioObj

        val line = "$LP[RADIO] cache slot=$slot cls=${radioObj.javaClass.name}"
        Logger.log(line)
        appendToFile(line)

        maybeStartPoller()
    }

    private fun maybeStartPoller() {
        if (!pollStarted.compareAndSet(false, true)) return

        val line = "$LP[POLL] start interval=${POLL_MS}ms"
        Logger.log(line)
        appendToFile(line)

        h.post(::tick)
    }

    private fun tick() {
        try {
            radios.forEach { (slot, radioObj) ->
                callGetInfoOnce(slot, radioObj)
            }
        } catch (t: Throwable) {
            Logger.logE("$LP[ERR][POLL] tick failed", t)
            appendToFile("$LP[ERR][POLL] ${t.javaClass.simpleName}:${t.message}")
        } finally {
            h.postDelayed(::tick, POLL_MS)
        }
    }

    /**
     * 主动拉取：调用 getNwRateLimitingInfo(Message)
     * 这条如果能打出日志 + 后续命中 Response，就能证明“主动拉取闭环”。
     */
    private fun callGetInfoOnce(slot: Int, radioObj: Any) {
        val m = radioObj.javaClass.methods.firstOrNull {
            it.name == "getNwRateLimitingInfo" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Message::class.java
        } ?: run {
            val line = "$LP[POLL][WARN] getNwRateLimitingInfo(Message) not found slot=$slot cls=${radioObj.javaClass.name}"
            Logger.log(line)
            appendToFile(line)
            return
        }

        val t = SystemClock.elapsedRealtime()
        val line = "$LP[POLL] t=$t slot=$slot -> getNwRateLimitingInfo"
        Logger.log(line)
        appendToFile(line)

        val msg = Message.obtain()
        runCatching { m.invoke(radioObj, msg) }
            .onFailure { e ->
                Logger.logE("$LP[ERR][POLL] invoke getNwRateLimitingInfo failed slot=$slot", e)
                appendToFile("$LP[ERR][POLL] invoke failed slot=$slot ${e.javaClass.simpleName}:${e.message}")
            }
    }

    // =========================
    // Digest / 解析辅助
    // =========================

    /**
     * 你要的摘要：尽可能从字段里取 rat/limitState/ambrUl/ambrDl（int/long/boxed 都兼容），字段名带别名兜底。
     */
    private fun buildLimitDigest(limitInfo: Any): String {
        fun longField(vararg names: String): Long? {
            for (n in names) {
                val vi = runCatching { XposedHelpers.getIntField(limitInfo, n) }.getOrNull()
                if (vi != null) return vi.toLong()

                val vl = runCatching { XposedHelpers.getLongField(limitInfo, n) }.getOrNull()
                if (vl != null) return vl

                val vo = runCatching { XposedHelpers.getObjectField(limitInfo, n) }.getOrNull()
                when (vo) {
                    is Int -> return vo.toLong()
                    is Long -> return vo
                }
            }
            return null
        }

        val rat = longField("rat", "mRat", "radioTech", "accessNetwork", "nwRat")
        val st  = longField("limitState", "mLimitState", "state", "status")
        val ul  = longField("ambrUl", "mAmbrUl", "uplinkAmbr", "ulAmbr", "ambrUplink", "ul")
        val dl  = longField("ambrDl", "mAmbrDl", "downlinkAmbr", "dlAmbr", "ambrDownlink", "dl")

        return "rat=$rat limitState=$st ambrUl=$ul ambrDl=$dl cls=${limitInfo.javaClass.name}"
    }

    private fun tryDigestFromAny(obj: Any?): String? {
        if (obj == null) return null

        fun hasField(o: Any, name: String): Boolean =
            runCatching { XposedHelpers.findFieldIfExists(o.javaClass, name) != null }.getOrDefault(false)

        fun looksLikeLimit(o: Any): Boolean {
            return hasField(o, "rat") || hasField(o, "mRat") ||
                    hasField(o, "limitState") || hasField(o, "mLimitState") ||
                    hasField(o, "ambrUl") || hasField(o, "mAmbrUl") ||
                    hasField(o, "ambrDl") || hasField(o, "mAmbrDl") ||
                    hasField(o, "uplinkAmbr") || hasField(o, "downlinkAmbr") ||
                    hasField(o, "ulAmbr") || hasField(o, "dlAmbr")
        }

        // 1) obj 自身就是 limit
        if (looksLikeLimit(obj)) {
            return runCatching { buildLimitDigest(obj) }.getOrNull()
        }

        // 2) 一层嵌套
        val nestedNames = arrayOf("mResult", "result", "limitInfo", "info", "data", "payload")
        for (fn in nestedNames) {
            val nested = runCatching { XposedHelpers.getObjectField(obj, fn) }.getOrNull() ?: continue
            if (looksLikeLimit(nested)) {
                return runCatching { buildLimitDigest(nested) }.getOrNull()
            }
        }

        // 3) 二层嵌套（谨慎兜底）
        for (fn in nestedNames) {
            val nested1 = runCatching { XposedHelpers.getObjectField(obj, fn) }.getOrNull() ?: continue
            for (fn2 in nestedNames) {
                val nested2 = runCatching { XposedHelpers.getObjectField(nested1, fn2) }.getOrNull() ?: continue
                if (looksLikeLimit(nested2)) {
                    return runCatching { buildLimitDigest(nested2) }.getOrNull()
                }
            }
        }

        return null
    }

    private fun dumpLimitDigestsFromArgs(args: Array<Any?>): List<String> {
        if (args.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)

        for (a in args) {
            tryDigestFromAny(a)?.let(out::add)

            when (a) {
                is Array<*> -> a.forEach { e -> tryDigestFromAny(e)?.let(out::add) }
                is List<*> -> a.forEach { e -> tryDigestFromAny(e)?.let(out::add) }
            }
        }

        return out.distinct()
    }

    /**
     * 仅当 digest none 时输出：只打字段名，不打字段值（节流）。
     */
    private fun buildFieldNameDump(thisObj: Any, args: Array<Any?>): String {
        fun fieldNamesOf(o: Any?): String {
            if (o == null) return "null"
            val names = runCatching { o.javaClass.declaredFields.map { it.name } }.getOrElse { emptyList() }
            val trimmed = names.take(40).joinToString("|")
            return "${o.javaClass.simpleName}[$trimmed]"
        }

        val parts = ArrayList<String>(6)
        parts.add("this=${fieldNamesOf(thisObj)}")
        args.take(4).forEachIndexed { idx, a ->
            parts.add("arg$idx=${fieldNamesOf(a)}")
        }

        val s = parts.joinToString(" ; ")
        return if (s.length > 600) s.take(600) + "..." else s
    }

    // ---- 落盘 ----
    private fun appendToFile(line: String) {
        val ctx = appCtx
        h.post {
            try {
                val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                val fileName = "ambr_$day.txt"

                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "networkhelper/log"
                )
                if (!downloadDir.exists()) downloadDir.mkdirs()

                val f1 = File(downloadDir, fileName)

                runCatching {
                    f1.appendText(line + "\n")
                }.onFailure {
                    // 降级：app 外部目录
                    val fallback = ctx?.getExternalFilesDir("log")
                    if (fallback != null) {
                        File(fallback, fileName).appendText(line + "\n")
                    }
                }
            } catch (t: Throwable) {
                Logger.logE("$LP[ERR] appendToFile failed", t)
            }
        }
    }
}
