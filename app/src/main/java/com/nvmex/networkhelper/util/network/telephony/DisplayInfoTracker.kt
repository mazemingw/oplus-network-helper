package com.nvmex.networkhelper.util.network.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 只负责订阅 TelephonyDisplayInfo，并缓存 overrideNetworkType（NSA 判定唯一权威来源）
 * 这是一个专门监听TelephonyDisplayInfo（电话显示信息）的追踪器
 *
 * 它使用Android的TelephonyCallback.DisplayInfoListener接口来监听网络类型变化
 *
 * 主要作用是在Android 12+ (API 31+) 上捕获网络的NSA（非独立组网）状态
 */
class DisplayInfoTracker(
    private val context: Context,
    private val tm: TelephonyManager
) {
    private val executor by lazy { ContextCompat.getMainExecutor(context) }

    @Volatile private var registered = false
    @Volatile private var listener: TelephonyCallback? = null

    /** NSA 判定所需：overrideNetworkType（NR_NSA / NR_ADVANCED） */
    @Volatile var lastOverrideType: Int? = null
        private set

    @SuppressLint("MissingPermission")
    fun ensureRegisteredOnce() {
        if (registered) return
        registered = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        runCatching {
            val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(di: TelephonyDisplayInfo) {
                    lastOverrideType = di.overrideNetworkType
                }
            }
            listener = cb
            tm.registerTelephonyCallback(executor, cb)
        }.onFailure {
            Log.e("TDI", "register DisplayInfoListener failed: ${it.message}", it)
        }
    }
}