package com.nvmex.networkhelper.xposed

import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * API 101 modern entry.
 * Reuse existing legacy hook pipeline to reduce migration risk.
 */
class ModernXposedInit : XposedModule() {

    companion object {
        private const val TAG = "NetworkHelper999Modern"
    }

    private val legacyEntry by lazy { XposedInit() }

    @Volatile
    private var currentProcessName: String = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XposedBridge.attachModule(this)
        currentProcessName = param.processName
        log(
            Log.INFO,
            TAG,
            "onModuleLoaded process=${param.processName} system=${param.isSystemServer}"
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val processName = currentProcessName.ifBlank { param.packageName }
        val lpparam = newLegacyParam(
            packageName = param.packageName,
            processName = processName,
            classLoader = param.defaultClassLoader,
            appInfo = param.applicationInfo,
            isFirst = param.isFirstPackage
        )
        dispatchToLegacy(lpparam, "onPackageLoaded")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        // Keep behavior aligned with legacy API: package/process both use "android".
        val lpparam = newLegacyParam(
            packageName = "android",
            processName = "android",
            classLoader = param.classLoader,
            appInfo = null,
            isFirst = true
        )
        dispatchToLegacy(lpparam, "onSystemServerStarting")
    }

    private fun newLegacyParam(
        packageName: String,
        processName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirst: Boolean
    ): XC_LoadPackage.LoadPackageParam {
        return XC_LoadPackage.LoadPackageParam().apply {
            this.packageName = packageName
            this.processName = processName
            this.classLoader = classLoader
            this.appInfo = appInfo
            this.isFirstApplication = isFirst
        }
    }

    private fun dispatchToLegacy(lpparam: XC_LoadPackage.LoadPackageParam, source: String) {
        runCatching {
            legacyEntry.handleLoadPackage(lpparam)
        }.onFailure { t ->
            log(Log.ERROR, TAG, "$source dispatch failed: ${t.javaClass.simpleName}: ${t.message}", t)
            runCatching { XposedBridge.log(t) }
        }
    }
}
