package com.nvmex.networkhelper.xposed

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import com.nvmex.networkhelper.xposed.logger.Logger
import dalvik.system.DexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class PluginMonitor : IXposedHookLoadPackage {

    companion object {
        private const val ENGINEER_PKG = "com.oplus.engineernetwork"
        private const val PREFS_NAME = "plugin_monitor_prefs"
        private const val PREF_NEVER_SHOW = "never_show_plugin_warning"

        private const val FLAG_DIR = "networkhelper_flags"
        private const val FLAG_FILE = "never_show_plugin_warning.flag"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != ENGINEER_PKG) return
        if (!lpparam.processName.contains(":plugin")) return

        monitorPluginLoad(lpparam)

        if (!shouldNeverShowWarning(lpparam)) {
            showPluginWarning(lpparam)
        } else {
            Logger.log("[PluginMonitor] skip warning dialog due to persisted flag")
        }
    }

    private fun monitorPluginLoad(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.hookAllConstructorsNative(DexClassLoader::class.java) { chain ->
                val result = chain.proceed()
                val dexPath = chain.args.getOrNull(0)?.toString() ?: return@hookAllConstructorsNative result
                if (dexPath.contains(".apk")) {
                    val fileName = dexPath.substringAfterLast("/")
                    Logger.log("[PluginMonitor] plugin apk loaded: $fileName")
                    showToast(lpparam, "加载插件: $fileName")
                }
                result
            }
        } catch (e: Throwable) {
            Logger.logE("[PluginMonitor] monitorPluginLoad failed", e)
        }
    }

    private fun showPluginWarning(lpparam: XC_LoadPackage.LoadPackageParam) {
        runOnMainThread {
            try {
                val context = getApplicationContext(lpparam) ?: return@runOnMainThread
                if (!isContextValid(context)) return@runOnMainThread

                val message = """
                    工程模式专为具备专业知识的用户设计，普通用户请仔细阅读说明后谨慎操作：
                    
                    ⚠ 重要警告：
                    不清楚功能的选项请勿随意修改。
                    错误操作可能导致网络异常，严重时可能造成设备损坏。
                    
                    安全操作指南：
                    1. 操作前请先阅读：欧加网络助手 > 设置 > 使用教程
                    2. 修改任何设置前，建议先截图保存原始配置
                    3. 部分设置会永久生效，不是卸载应用即可恢复
                    
                    继续操作即表示你已理解相关风险。
                """.trimIndent()

                val dialog = AlertDialog.Builder(context)
                    .setTitle("⚠ 重要安全提示")
                    .setMessage(message)
                    .setPositiveButton("我已了解") { d, _ -> d.dismiss() }
                    .setNegativeButton("不再提示") { d, _ ->
                        d.dismiss()
                        setNeverShowWarning(lpparam, true)
                        Toast.makeText(context, "已设置为不再提示", Toast.LENGTH_SHORT).show()
                    }
                    .setCancelable(false)
                    .create()

                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
            } catch (e: Throwable) {
                Logger.logE("[PluginMonitor] showPluginWarning failed", e)
            }
        }
    }

    private fun showToast(lpparam: XC_LoadPackage.LoadPackageParam, message: String) {
        runOnMainThread {
            try {
                val context = getApplicationContext(lpparam) ?: return@runOnMainThread
                // Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }

    private fun getApplicationContext(lpparam: XC_LoadPackage.LoadPackageParam): Application? {
        return try {
            val activityThreadClass = XposedHelpers.findClass(
                "android.app.ActivityThread",
                lpparam.classLoader
            )
            val currentActivityThread = XposedHelpers.callStaticMethod(
                activityThreadClass,
                "currentActivityThread"
            )
            XposedHelpers.callMethod(currentActivityThread, "getApplication") as? Application
        } catch (e: Throwable) {
            Logger.logE("[PluginMonitor] getApplicationContext failed", e)
            null
        }
    }

    private fun isContextValid(context: Context): Boolean {
        return try {
            if (context is Activity) !context.isFinishing && !context.isDestroyed else true
        } catch (_: Throwable) {
            true
        }
    }

    private fun runOnMainThread(action: () -> Unit) {
        try {
            Handler(Looper.getMainLooper()).post(action)
        } catch (e: Throwable) {
            Logger.logE("[PluginMonitor] runOnMainThread failed", e)
        }
    }

    private fun stableContext(context: Context): Context {
        return context.createDeviceProtectedStorageContext() ?: context
    }

    private fun flagFile(context: Context): File {
        val base = stableContext(context)
        val dir = File(base.filesDir, FLAG_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FLAG_FILE)
    }

    private fun setNeverShowWarning(lpparam: XC_LoadPackage.LoadPackageParam, neverShow: Boolean) {
        try {
            val context = getApplicationContext(lpparam) ?: return
            val base = stableContext(context)

            val spOk = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_NEVER_SHOW, neverShow)
                .commit()

            val f = flagFile(base)
            val fileOk = if (neverShow) {
                f.writeText("1")
                f.exists()
            } else {
                !f.exists() || f.delete()
            }

            Logger.log("[PluginMonitor] setNeverShowWarning neverShow=$neverShow spOk=$spOk fileOk=$fileOk")
        } catch (e: Throwable) {
            Logger.logE("[PluginMonitor] setNeverShowWarning failed", e)
        }
    }

    private fun shouldNeverShowWarning(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return try {
            val context = getApplicationContext(lpparam) ?: return false
            val base = stableContext(context)

            val f = flagFile(base)
            if (f.exists()) return true

            base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_NEVER_SHOW, false)
        } catch (e: Throwable) {
            Logger.logE("[PluginMonitor] shouldNeverShowWarning failed", e)
            false
        }
    }
}
