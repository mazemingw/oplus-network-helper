package dev.qcom.efs.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.IBinder
import android.os.Process
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONObject

/** Short-lived root app_process entry point. No DIAG, EFS or block-device writes. */
object PhoneAccess {
    const val NR_BIT = 1L shl 19

    @SuppressLint("MissingPermission", "PrivateApi") // This entry point is invoked as uid 0, never as the app uid.
    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Process.myUid() == 0) { "Root is required" }
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            val base = context.getSystemService(TelephonyManager::class.java)
            val subId = args.getOrNull(1)?.toIntOrNull() ?: SubscriptionManager.getDefaultDataSubscriptionId()
            val tm = if (SubscriptionManager.isValidSubscriptionId(subId)) base.createForSubscriptionId(subId) else base
            val result = when (args.firstOrNull()) {
                "identity" -> {
                    val serial = runCatching { Build.getSerial() }.getOrNull()
                        ?.takeUnless { it.isBlank() || it == Build.UNKNOWN }
                        ?: Runtime.getRuntime().exec(arrayOf("getprop", "ro.serialno")).inputStream.bufferedReader().use { it.readText().trim() }
                    // Cloud identity is always IMEI1/SIM0; never silently substitute IMEI2.
                    val imei = (runCatching { phoneCall("getImeiForSlot", 0, "root", null) as? String }.getOrNull()
                        ?: runCatching { base.getImei(0) }.getOrNull()).orEmpty()
                    JSONObject().put("model", Build.MODEL).put("serial", serial).put("imei", imei)
                        .put("firmware", Build.FINGERPRINT)
                }
                "read5g", "toggle5g", "set5g" -> {
                    check(SubscriptionManager.isValidSubscriptionId(subId)) { "没有可用的默认数据 SIM 卡" }
                    check(Build.VERSION.SDK_INT >= 31) { "5G 磁贴需要 Android 12 或更高版本" }
                    val before = (phoneCall("getAllowedNetworkTypesForReason", subId, 0) as Number).toLong()
                    check(before > 0) { "无法读取默认数据卡的网络类型" }
                    if (args[0] != "read5g") {
                        val allow = if (args[0] == "toggle5g") before and NR_BIT == 0L else when (args.getOrNull(2)) {
                            "1" -> true; "0" -> false; else -> error("缺少 5G 目标状态")
                        }
                        val next = setNrAllowed(before, allow)
                        if (next != before) {
                            val accepted = phoneCall("setAllowedNetworkTypesForReason", subId, 0, next)
                            check(accepted != false) { "系统拒绝 5G 网络设置" }
                        }
                        var after = before
                        for (attempt in 0..4) {
                            after = (phoneCall("getAllowedNetworkTypesForReason", subId, 0) as Number).toLong()
                            if ((after and NR_BIT != 0L) == allow) break
                            Thread.sleep(150)
                        }
                        // Other RAT bits can be normalized by Android. Verify the requested NR state.
                        check((after and NR_BIT != 0L) == allow) { "系统未保留 5G 网络设置" }
                        JSONObject().put("enabled", after and NR_BIT != 0L).put("subId", subId)
                    } else JSONObject().put("enabled", before and NR_BIT != 0L).put("subId", subId)
                }
                else -> error("unsupported_phone_operation")
            }
            println(result.put("ok", true))
        } catch (t: Throwable) {
            println(JSONObject().put("ok", false).put("error", t.cause?.message ?: t.message ?: "手机服务访问失败"))
        }
    }

    // The system-context TelephonyManager reports package "android" for uid 0.
    // Call the platform binder with the correct root attribution instead.
    private fun phoneCall(name: String, vararg supplied: Any?): Any? {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "phone") as IBinder
        val phone = Class.forName("com.android.internal.telephony.ITelephony\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val method = phone.javaClass.methods.filter { it.name == name && it.parameterCount >= supplied.size }.minByOrNull { it.parameterCount }
            ?: error("系统不支持 $name")
        method.isAccessible = true
        val args = Array<Any?>(method.parameterCount) { i ->
            if (i < supplied.size) supplied[i] else when (method.parameterTypes[i]) {
                String::class.java -> "root"; Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L; Boolean::class.javaPrimitiveType -> false; else -> null
            }
        }
        return method.invoke(phone, *args)
    }
}

fun toggleNrBit(current: Long): Long {
    return setNrAllowed(current, current and PhoneAccess.NR_BIT == 0L)
}

fun setNrAllowed(current: Long, allowed: Boolean): Long {
    require(current > 0)
    return (if (allowed) current or PhoneAccess.NR_BIT else current and PhoneAccess.NR_BIT.inv())
        .also { require(it > 0) { "请先允许 4G，再关闭 5G" } }
}
