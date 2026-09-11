package be.mygod.vpnhotspot

import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import timber.log.Timber

/**
 * Firebase/Crashlytics 的替代层：
 * - 默认什么都不做（避免闪退）
 * - 保留接口，让上层代码不需要大改
 */
object CrashReporter {

    /** 是否启用（你以后想恢复 Firebase，只需把这里改成 true 并接回实现） */
    val enabled: Boolean = false

    fun init(app: App) {
        // 保留原行为：开启协程 debug（非必须，但原项目就这么做）
        System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)

        if (!enabled) {
            Timber.plant(object : Timber.DebugTree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    // 保持 Logcat 输出
                    if (t == null) {
                        if (priority != Log.DEBUG || BuildConfig.DEBUG) Log.println(priority, tag, message)
                    } else {
                        if (priority >= Log.WARN || priority == Log.DEBUG) {
                            Log.println(priority, tag, message)
                            Log.w(tag, message, t)
                        }
                    }
                }
            })
            return
        }

        // 未来你要接回 Firebase：把 Firebase 初始化与 Crashlytics/TImber 上报放这里
        // 并确保存在 google-services.json + 插件。
    }

    fun log(message: String) {
        // no-op
    }

    fun recordException(t: Throwable) {
        // no-op
    }

    fun setCustomKey(key: String, value: String) {
        // no-op
    }

    fun buildInfo(): Map<String, String> = mapOf(
        "uname.release" to runCatching { Os.uname().release }.getOrElse { "unknown" },
        "build" to Build.DISPLAY
    )
}
