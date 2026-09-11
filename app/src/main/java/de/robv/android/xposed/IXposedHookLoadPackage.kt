package de.robv.android.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage

interface IXposedHookLoadPackage {
    fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam)
}

