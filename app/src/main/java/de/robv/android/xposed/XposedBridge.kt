package de.robv.android.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.concurrent.CopyOnWriteArraySet

object XposedBridge {
    val BOOTCLASSLOADER: ClassLoader = ClassLoader.getSystemClassLoader()

    @Volatile
    private var module: XposedModule? = null

    fun attachModule(module: XposedModule) {
        this.module = module
    }

    fun getAttachedModule(): XposedModule? = module

    private fun requireModule(): XposedModule {
        return module ?: error("Xposed framework not attached yet")
    }

    fun log(text: String) {
        runCatching { requireModule().log(Log.INFO, "XposedCompat", text) }
            .onFailure { Log.i("XposedCompat", text) }
    }

    fun log(t: Throwable) {
        runCatching { requireModule().log(Log.ERROR, "XposedCompat", t.message ?: "Throwable", t) }
            .onFailure { Log.e("XposedCompat", Log.getStackTraceString(t)) }
    }

    fun hookMethodNative(
        hookMethod: Executable?,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        block: (XposedInterface.Chain) -> Any?
    ): XposedInterface.HookHandle? {
        if (hookMethod == null) return null
        return requireModule()
            .hook(hookMethod)
            .setPriority(priority)
            .setExceptionMode(exceptionMode)
            .intercept { chain -> block(chain) }
    }

    fun hookAllMethodsNative(
        hookClass: Class<*>?,
        methodName: String,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        block: (XposedInterface.Chain) -> Any?
    ): Set<XposedInterface.HookHandle> {
        if (hookClass == null) return emptySet()
        val handles = CopyOnWriteArraySet<XposedInterface.HookHandle>()
        hookClass.declaredMethods
            .filter { it.name == methodName }
            .forEach {
                it.isAccessible = true
                hookMethodNative(it, priority, exceptionMode, block)?.let(handles::add)
            }
        return handles
    }

    fun hookAllConstructorsNative(
        hookClass: Class<*>?,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        block: (XposedInterface.Chain) -> Any?
    ): Set<XposedInterface.HookHandle> {
        if (hookClass == null) return emptySet()
        val handles = CopyOnWriteArraySet<XposedInterface.HookHandle>()
        hookClass.declaredConstructors.forEach {
            it.isAccessible = true
            hookMethodNative(it, priority, exceptionMode, block)?.let(handles::add)
        }
        return handles
    }
}
