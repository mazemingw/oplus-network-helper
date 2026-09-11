package com.nvmex.networkhelper.xposed

import android.app.Application
import android.content.Context
import android.os.Bundle
import com.nvmex.networkhelper.xposed.broad.BroadcastHelper
import com.nvmex.networkhelper.xposed.handler.AmbrHandler
import com.nvmex.networkhelper.xposed.handler.NrcaHandler
import com.nvmex.networkhelper.xposed.hooks.LteCaTraceHooks
import com.nvmex.networkhelper.xposed.logger.Logger
import com.nvmex.networkhelper.xposed.test.NrcaModifier
import com.nvmex.networkhelper.xposed.woker.IndicationHooks
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// 主入口：在目标进程安装 NRCA/AMBR 相关 Hook，并做进程级防重。
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private val counter = AtomicLong(0)
    }

    // 按进程安装一次，避免全局标记导致其它进程被跳过。
    private val installedProcs = ConcurrentHashMap<String, AtomicBoolean>()
    private val attachHookedProcs = ConcurrentHashMap<String, AtomicBoolean>()
    private val lteInstalledProcs = ConcurrentHashMap<String, AtomicBoolean>()

    // 依赖组件
    private val broadcastHelper = BroadcastHelper()
    private val nrcaHandler = NrcaHandler(broadcastHelper)
    private val ambrHandler = AmbrHandler()
    private val indicationHooks = IndicationHooks(nrcaHandler, ambrHandler)

    // private val indicationHooksTelephony = IndicationHooksTelephony(nrcaHandler, ambrHandler)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!shouldHook(lpparam.packageName, lpparam.processName)) return

//        NrcaModifier.enable() // 模拟5GA

        val id = counter.incrementAndGet()
        val procKey = buildProcKey(lpparam.packageName, lpparam.processName)

        Logger.log("[$id] enter pkg=${lpparam.packageName} proc=${lpparam.processName} key=$procKey")

        // 每个进程只安装一次。
        val gate = installedProcs.getOrPut(procKey) { AtomicBoolean(false) }
        if (!gate.compareAndSet(false, true)) {
            Logger.log("[$id] already installed in $procKey, skip")
            return
        }

        try {
            // RegistrantList fallback hook（依赖正确的 classLoader）。
            hookRegistrantList(lpparam.classLoader)

            // AMBR hooks
            ambrHandler.hookNwRateReqJ(lpparam.classLoader)
            ambrHandler.hookNwRateRespJ(lpparam.classLoader)
            ambrHandler.hookNwRatePoller(lpparam.classLoader)

            // NRCA/AMBR indication + response/poll
            indicationHooks.tryHookIndicationWithRetry(lpparam.classLoader, "HLP-immediate-$procKey")
            // indicationHooksTelephony.tryHookWithRetry(lpparam.classLoader, "HLP-immediate-$procKey")

            // 传入 fallback classLoader，同时基于 procKey 做 attach 防重。
            hookApplicationAttach(procKey, lpparam.classLoader)

            Logger.log("[$id] install done for $procKey")
        } catch (t: Throwable) {
            Logger.logE("[$id] init failed for $procKey", t)
        }
    }

    /**
     * 多进程覆盖：subsys + phone + system_server
     * - com.oplus.subsys：SubsystemApp
     * - com.android.phone：Telephony 相关调用方（很多 ROM 在这里发 request）
     * - android：system_server（有的 ROM 把 client 放这里）
     */
    private fun shouldHook(pkg: String, proc: String?): Boolean {
        return pkg == "com.oplus.subsys" ||
                pkg == "com.android.phone" ||
                pkg == "android"
    }

    private fun buildProcKey(pkg: String, proc: String?): String {
        return "$pkg:${proc ?: ""}"
    }

    /**
     * Hook Application.attach：
     * - 拿到 Context（用于落盘、广播上下文等）
     * - 拿到更靠谱的 classloader（ctx.classLoader）
     * - 在这里装 LTECA Trace Hooks（每进程一次）
     */
    private fun hookApplicationAttach(procKey: String, fallbackCl: ClassLoader) {
        val gate = attachHookedProcs.getOrPut(procKey) { AtomicBoolean(false) }
        if (!gate.compareAndSet(false, true)) return

        try {
            XposedBridge.hookAllMethodsNative(Application::class.java, "attach") { chain ->
                val result = chain.proceed()
                val ctx = chain.args.getOrNull(0) as? Context ?: return@hookAllMethodsNative result

                // 提供 ctx 给广播/解析链路。
                broadcastHelper.setAppContext(ctx)

                // 优先使用 ctx.classLoader，失败时回退到 fallbackCl。
                val appCl = (ctx.classLoader ?: fallbackCl)

                Logger.log("[attach][$procKey] Application.attach cl=${Logger.clTag(appCl)} fb=${Logger.clTag(fallbackCl)}")

                // NRCA hooks 再试一次（防止类晚加载）
                indicationHooks.tryHookIndicationWithRetry(appCl, "attach-$procKey")
                // indicationHooksTelephony.tryHookWithRetry(appCl, "attach-$procKey")

                // LTECA 按进程安装一次，避免其它进程抢先置位。
                val lteGate = lteInstalledProcs.getOrPut(procKey) { AtomicBoolean(false) }
                if (lteGate.compareAndSet(false, true)) {
                    LteCaTraceHooks.setAppContext(ctx)

                    // 双 classLoader 安装；内部有原子保护，不会重复 hook 同一点。
                    LteCaTraceHooks.install(appCl)
                    if (fallbackCl !== appCl) {
                        LteCaTraceHooks.install(fallbackCl)
                    }

                    Logger.log("[attach][$procKey] LTECA TraceHooks installed")
                } else {
                    Logger.log("[attach][$procKey] LTECA TraceHooks already installed, skip")
                }
                result
            }

            Logger.log("hooked Application.attach for $procKey")
        } catch (t: Throwable) {
            Logger.logE("hookApplicationAttach failed for $procKey", t)
        }
    }

    private fun hookRegistrantList(cl: ClassLoader) {
        try {
            val rl = XposedHelpers.findClass("android.os.RegistrantList", cl)
            XposedBridge.hookAllMethodsNative(rl, "notifyRegistrants") { chain ->
                if (Logger.LOG_FALLBACK_ENABLED) {
                    val arg0 = chain.args.getOrNull(0)
                    val result = if (arg0 != null) extractAsyncResultResult(arg0) else null
                    val b = result as? Bundle
                    val keyObj = b?.get("keyObject")
                    if (keyObj?.javaClass?.name == "com.oplus.telephony.RadioNrcaInfo") {
                        Logger.log("[NRCA][Bundle] RadioNrcaInfo arrived (fallback)")
                    }
                }
                chain.proceed()
            }
            Logger.log("hooked RegistrantList.notifyRegistrants")
        } catch (t: Throwable) {
            Logger.logE("hookRegistrantList failed", t)
        }
    }

    private fun extractAsyncResultResult(asyncResultLike: Any): Any? {
        return try {
            val f = XposedHelpers.findFieldIfExists(asyncResultLike.javaClass, "result") ?: return null
            f.isAccessible = true
            f.get(asyncResultLike)
        } catch (_: Throwable) {
            null
        }
    }
}

