package com.nvmex.networkhelper.xposed.logger

import android.util.Log
import com.nvmex.networkhelper.xposed.config.Config

object Logger {
    @Volatile
    var LOG_ENABLED = false // 日志总开关

    @Volatile
    var LOG_FALLBACK_ENABLED = false

    fun log(msg: String) {
        if (!LOG_ENABLED) return
        Log.i(Config.LOG_TAG, msg)
        logToXposedSafely("${Config.LOG_TAG} [I]: $msg")
    }

    fun logE(msg: String, t: Throwable? = null) {
        if (!LOG_ENABLED) return

        Log.e(Config.LOG_TAG, msg, t)
        logToXposedSafely("${Config.LOG_TAG} [E]: $msg")
        if (t != null) {
            logThrowableToXposedSafely(t)
        }
    }

    private fun logToXposedSafely(message: String) {
        try {
            val clazz = Class.forName("de.robv.android.xposed.XposedBridge")
            val method = clazz.getMethod("log", String::class.java)
            method.invoke(null, message)
        } catch (_: Throwable) {
            if (LOG_FALLBACK_ENABLED) {
                Log.w(Config.LOG_TAG, "XposedBridge 不可用，已跳过 Xposed 日志输出")
            }
        }
    }

    private fun logThrowableToXposedSafely(t: Throwable) {
        try {
            val clazz = Class.forName("de.robv.android.xposed.XposedBridge")
            val method = clazz.getMethod("log", Throwable::class.java)
            method.invoke(null, t)
        } catch (_: Throwable) {
            if (LOG_FALLBACK_ENABLED) {
                Log.w(Config.LOG_TAG, "XposedBridge 不可用，已跳过 Throwable 日志输出", t)
            }
        }
    }

    fun clTag(cl: ClassLoader?): String =
        if (cl == null) "null"
        else "${cl.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(cl))}"
}
