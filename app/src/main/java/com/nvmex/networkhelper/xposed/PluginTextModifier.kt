package com.nvmex.networkhelper.xposed

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.AndroidAppHelper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

class PluginTextModifier : IXposedHookLoadPackage {

    companion object {
        private const val ENGINEER_PKG = "com.oplus.engineernetwork"
        private const val NETWORK_HELPER_PKG = "com.nvmex.networkhelper"
        private val PLUGIN_HELP_URI: Uri =
            Uri.parse("content://$NETWORK_HELPER_PKG.xposed.settings/plugin_help")
        private const val COL_ENABLED = "enabled"

        private var isPluginHelpEnabled = true
        private var lastSettingsCheckTime = 0L
        private const val SETTINGS_CHECK_INTERVAL = 5000L

        private fun readPluginHelpSetting(): Boolean {
            readFromProvider()?.let { return it }
            return readFromXSharedPreferences()
        }

        private fun readFromProvider(): Boolean? {
            return try {
                val app = AndroidAppHelper.currentApplication() ?: return null
                app.contentResolver.query(PLUGIN_HELP_URI, arrayOf(COL_ENABLED), null, null, null)
                    ?.use { cursor ->
                        if (!cursor.moveToFirst()) return null
                        val idx = cursor.getColumnIndex(COL_ENABLED)
                        if (idx < 0) return null
                        val value = cursor.getInt(idx) == 1
                        Logger.log("[PluginTextModifier] read from provider: $value")
                        value
                    }
            } catch (t: Throwable) {
                Logger.logE("[PluginTextModifier] provider read failed", t)
                null
            }
        }

        private fun readFromXSharedPreferences(): Boolean {
            return try {
                val pref = XSharedPreferences(NETWORK_HELPER_PKG, "nh_settings")
                pref.reload()
                val value = pref.getBoolean("enable_plugin_help", true)
                Logger.log("[PluginTextModifier] read from XSharedPreferences: $value")
                value
            } catch (t: Throwable) {
                Logger.logE("[PluginTextModifier] XSharedPreferences read failed", t)
                true
            }
        }

        private fun shouldReplaceText(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastSettingsCheckTime > SETTINGS_CHECK_INTERVAL) {
                isPluginHelpEnabled = readPluginHelpSetting()
                lastSettingsCheckTime = now
            }
            return isPluginHelpEnabled
        }

        private val textReplacementRules = mapOf(
            "RadioInfo信息" to "RadioInfo信息（常用：数据网信息）",
            "Data Cell" to "Data Cell（数据业务小区）",
            "Voice Cell" to "Voice Cell（语音业务小区）",
            "NR Information" to "NR Information（5G网络信息）",
            "Other Information" to "Other Information（其他信息）",
            "Neighbour Cell" to "Neighbour Cell（邻小区）",
            "CAInfo信息" to "CAInfo信息（常用：载波聚合信息）",
            "LTE CA信息" to "4G载波聚合信息",
            "NR CA信息" to "5G载波聚合信息",
            "RAT模式" to "RAT模式（常用：网络制式偏好）",
            "NR/LTE/GSM/WCDMA" to "NR/LTE/GSM/WCDMA（默认）",
            "NR/LTE" to "NR/LTE（仅限5G、4G）",
            "仅NR" to "仅NR（仅限5G）",
            "锁小区测试" to "锁小区测试（常用：锁定基站）",
            "请输入绝对频点号" to "输入目标ARFCN",
            "请输入物理小区标识" to "输入PCI",
            "请输入波段" to "输入频段号，例如41",
            "频带选择" to "频带选择（常用：锁频段）",
            "BAND 1" to "BAND 1 (联通/电信)",
            "BAND 3" to "BAND 3 (联通/电信/移动)",
            "BAND 5" to "BAND 5 (电信/联通)",
            "BAND 8" to "BAND 8 (联通/移动/电信)",
            "BAND 34" to "BAND 34 (移动)",
            "BAND 38" to "BAND 38 (移动)",
            "BAND 39" to "BAND 39 (移动)",
            "BAND 40" to "BAND 40 (移动)",
            "BAND 41" to "BAND 41 (移动)",
            "BAND N1" to "BAND N1 (电信/联通)",
            "BAND N3" to "BAND N3 (电信/联通)",
            "BAND N5" to "BAND N5 (电信/联通)",
            "BAND N8" to "BAND N8 (电信/联通)",
            "BAND N28" to "BAND N28 (移动/广电)",
            "BAND N41" to "BAND N41 (移动/广电)",
            "BAND N77" to "BAND N77 (电信/联通)",
            "BAND N78" to "BAND N78 (电信/联通)",
            "BAND N79" to "BAND N79 (移动/广电)"
        )

        private fun replaceText(original: String): String = textReplacementRules[original] ?: original
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != ENGINEER_PKG || !lpparam.processName.contains(":plugin")) return

        isPluginHelpEnabled = readPluginHelpSetting()
        lastSettingsCheckTime = System.currentTimeMillis()

        installTextViewHooks()
        installEditTextHooks(lpparam)
        installViewHooks(lpparam)
        installActivityScanHook(lpparam)
    }

    private fun installTextViewHooks() {
        XposedBridge.hookAllMethodsNative(TextView::class.java, "setText") { chain ->
            if (!shouldReplaceText()) return@hookAllMethodsNative chain.proceed()
            val args = chain.args.toTypedArray()
            val text = args.getOrNull(0)?.toString()
            if (text == null) return@hookAllMethodsNative chain.proceed(args)
            val replaced = replaceText(text)
            if (text != replaced) args[0] = replaced
            chain.proceed(args)
        }
    }

    private fun installEditTextHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists("android.widget.EditText", lpparam.classLoader) ?: return
        XposedBridge.hookAllMethodsNative(clazz, "setHint") { chain ->
            if (!shouldReplaceText()) return@hookAllMethodsNative chain.proceed()
            val args = chain.args.toTypedArray()
            val hint = args.getOrNull(0)?.toString()
            if (hint == null) return@hookAllMethodsNative chain.proceed(args)
            val replaced = replaceText(hint)
            if (hint != replaced) args[0] = replaced
            chain.proceed(args)
        }
    }

    private fun installViewHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val viewClass = XposedHelpers.findClass("android.view.View", lpparam.classLoader)
        XposedBridge.hookAllMethodsNative(viewClass, "setContentDescription") { chain ->
            if (!shouldReplaceText()) return@hookAllMethodsNative chain.proceed()
            val args = chain.args.toTypedArray()
            val text = args.getOrNull(0)?.toString()
            if (text == null) return@hookAllMethodsNative chain.proceed(args)
            val replaced = replaceText(text)
            if (text != replaced) args[0] = replaced
            chain.proceed(args)
        }
    }

    private fun installActivityScanHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val activityClass = XposedHelpers.findClass("android.app.Activity", lpparam.classLoader)
        XposedBridge.hookAllMethodsNative(activityClass, "onCreate") { chain ->
            val result = chain.proceed()
            if (shouldReplaceText()) {
                val activityObj = chain.thisObject
                Handler(Looper.getMainLooper()).postDelayed({
                    scanAndReplaceViews(activityObj)
                }, 500)
            }
            result
        }
    }

    private fun scanAndReplaceViews(activity: Any) {
        try {
            val window = XposedHelpers.callMethod(activity, "getWindow") as Window
            scanViewTree(window.decorView)
        } catch (t: Throwable) {
            Logger.logE("scanAndReplaceViews failed", t)
        }
    }

    private fun scanViewTree(view: View) {
        if (!shouldReplaceText()) return

        if (view is TextView) {
            val text = view.text?.toString() ?: return
            val replaced = replaceText(text)
            if (text != replaced) view.text = replaced
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                scanViewTree(view.getChildAt(i))
            }
        }
    }
}
