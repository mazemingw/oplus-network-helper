package de.robv.android.xposed

import android.app.Application

object AndroidAppHelper {
    fun currentApplication(): Application? {
        return runCatching {
            val c = Class.forName("android.app.ActivityThread")
            val m = c.getDeclaredMethod("currentApplication")
            m.isAccessible = true
            m.invoke(null) as? Application
        }.getOrNull()
    }

    fun currentProcessName(): String? {
        return runCatching {
            val c = Class.forName("android.app.ActivityThread")
            val m = c.getDeclaredMethod("currentProcessName")
            m.isAccessible = true
            m.invoke(null) as? String
        }.getOrNull()
    }
}

