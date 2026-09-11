package de.robv.android.xposed.callbacks

import android.content.pm.ApplicationInfo

abstract class XC_LoadPackage {
    class LoadPackageParam {
        var packageName: String = ""
        var processName: String = ""
        lateinit var classLoader: ClassLoader
        var appInfo: ApplicationInfo? = null
        var isFirstApplication: Boolean = false
    }
}

