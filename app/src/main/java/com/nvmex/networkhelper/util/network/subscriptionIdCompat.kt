package com.nvmex.networkhelper.util.network

import android.telephony.TelephonyManager

//工具函数 反射拿 subscriptionId
fun TelephonyManager.subscriptionIdCompat(): Int {
    return runCatching {
        val m = TelephonyManager::class.java
            .getDeclaredMethod("getSubscriptionId")
        m.isAccessible = true
        m.invoke(this) as Int
    }.getOrDefault(-1)
}
