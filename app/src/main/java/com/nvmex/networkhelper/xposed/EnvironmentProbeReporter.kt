package com.nvmex.networkhelper.xposed

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.nvmex.networkhelper.ui.onboarding.EnvironmentProbeStore
import de.robv.android.xposed.AndroidAppHelper
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

object EnvironmentProbeReporter {
    private val attachHooked = ConcurrentHashMap.newKeySet<String>()

    fun isProbeTarget(packageName: String): Boolean {
        return EnvironmentProbeStore.requiredScopes.any { it.packageName == packageName } ||
                EnvironmentProbeStore.supportScopeTargets.any { it.packageName == packageName }
    }

    fun reportIfPossible(packageName: String, processName: String?) {
        val ctx = currentContext() ?: return
        report(ctx, packageName, processName)
    }

    fun hookApplicationAttach(packageName: String, processName: String?) {
        if (!isProbeTarget(packageName)) return
        if (packageName == "android") return

        val key = "$packageName:${processName.orEmpty()}"
        if (!attachHooked.add(key)) return

        runCatching {
            XposedBridge.hookAllMethodsNative(Application::class.java, "attach") { chain ->
                val result = chain.proceed()
                val ctx = chain.args.getOrNull(0) as? Context
                if (ctx != null) {
                    report(ctx.applicationContext ?: ctx, packageName, processName)
                }
                result
            }
        }
    }

    private fun report(context: Context, packageName: String, processName: String?) {
        runCatching {
            val values = ContentValues().apply {
                put(EnvironmentProbeStore.COL_PACKAGE, packageName)
                put(EnvironmentProbeStore.COL_PROCESS, processName.orEmpty())
                put(EnvironmentProbeStore.COL_LAST_SEEN_MS, System.currentTimeMillis())
            }
            context.contentResolver.update(environmentProbeUri, values, null, null)
        }
    }

    private fun currentContext(): Context? {
        AndroidAppHelper.currentApplication()?.let { return it.applicationContext ?: it }

        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .invoke(null)
                ?: return@runCatching null
            currentThread.javaClass
                .getDeclaredMethod("getSystemContext")
                .invoke(currentThread) as? Context
        }.getOrNull()
    }

    private val environmentProbeUri: Uri
        get() = Uri.parse("content://${EnvironmentProbeStore.AUTHORITY}/${EnvironmentProbeStore.PATH_ENVIRONMENT_PROBE}")
}
