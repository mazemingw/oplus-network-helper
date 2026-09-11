package com.nvmex.networkhelper.xposed

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.nvmex.networkhelper.xposed.handler.EngineerAmbrHooks
import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

class EngineerHookEntry : IXposedHookLoadPackage {
    private val installed = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.oplus.engineernetwork") return

        // 仅处理插件进程，NRSessionInfo 通常在该进程加载。
        if (lpparam.processName != "com.oplus.engineernetwork:plugin") {
            Logger.log("[ENG-AMBR] Skip non-plugin process: ${lpparam.processName}")
            return
        }

        if (!installed.compareAndSet(false, true)) return

        Logger.log("[ENG-AMBR] enter plugin proc=${lpparam.processName}")

        // 路径1：延迟安装，等待目标类加载完成。
        Handler(Looper.getMainLooper()).postDelayed({
            installHooksWithRetry(lpparam.classLoader, retryCount = 3)
        }, 2000)

        // 路径2：在 Activity 生命周期触发时补装。
        hookActivityLifecycle(lpparam.classLoader)
    }

    private fun installHooksWithRetry(classLoader: ClassLoader, retryCount: Int) {
        var retry = 0

        fun tryInstall() {
            try {
                Logger.log("[ENG-AMBR] Try installing hooks (attempt ${retry + 1}/$retryCount)")

                // 先验证关键类是否已可见。
                testClassExistence(classLoader)

                // 执行 Hook 安装。
                EngineerAmbrHooks.install(classLoader)
                Logger.log("[ENG-AMBR] ✓ Hooks installed successfully")

            } catch (e: ClassNotFoundException) {
                retry++
                Logger.log("[ENG-AMBR] Class not found on attempt $retry: ${e.message}")

                if (retry < retryCount) {
                    // 延迟重试。
                    Handler(Looper.getMainLooper()).postDelayed({
                        tryInstall()
                    }, 1000L * retry) // 每次重试间隔递增
                } else {
                    Logger.log("[ENG-AMBR] ✗ Failed to find class after $retryCount attempts")
                }
            } catch (e: Throwable) {
                Logger.log("[ENG-AMBR] Install error: ${e.message}")
            }
        }

        tryInstall()
    }

    private fun testClassExistence(classLoader: ClassLoader) {
        val testClasses = listOf(
            "com.oplus.engineernetwork.register.mdmcomponent.NRSessionInfo",
            "com.oplus.engineernetwork.register.mdmcomponent.MDMComponent",
            "com.oplus.engineernetwork.register.mdmcomponent.ArrayTableComponent"
        )

        testClasses.forEach { className ->
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                Logger.log("[ENG-AMBR] ✓ Class found: $className")
            } catch (e: ClassNotFoundException) {
                Logger.log("[ENG-AMBR] ✗ Class not found: $className")
                throw e // 重新抛出异常以触发重试
            }
        }
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            // Hook Activity.onCreate，借生命周期时机补装。
            val onCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            onCreate.isAccessible = true
            XposedBridge.hookMethodNative(onCreate) { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity ?: return@hookMethodNative result

                // 只处理工程模式应用的 Activity。
                if (activity.packageName == "com.oplus.engineernetwork") {
                    Logger.log("[ENG-AMBR] Activity.onCreate: ${activity.javaClass.name}")

                    // 延迟执行，尽量确保相关类已加载。
                    Handler(activity.mainLooper).postDelayed({
                        try {
                            EngineerAmbrHooks.install(activity.classLoader)
                        } catch (e: Throwable) {
                            Logger.log("[ENG-AMBR] Delayed install failed: ${e.message}")
                        }
                    }, 1000)
                }
                result
            }

        } catch (e: Throwable) {
            Logger.log("[ENG-AMBR] hookActivityLifecycle error: ${e.message}")
        }
    }
}
