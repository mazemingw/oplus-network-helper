package dev.qcom.efs.platform

import android.content.Context
import android.telephony.SubscriptionManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object RootPhone {
    suspend fun read(context: Context, operation: String, allowed: Boolean? = null): JSONObject = withContext(Dispatchers.IO) {
        require(operation in setOf("identity", "read5g", "toggle5g", "set5g"))
        val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
        val subscription = if (operation == "identity") "" else {
            val publicId = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
            val subId = if (SubscriptionManager.isValidSubscriptionId(publicId)) publicId
                else Settings.Global.getInt(context.contentResolver, "multi_sim_data_call", -1)
            check(SubscriptionManager.isValidSubscriptionId(subId)) { "没有可用的默认数据 SIM 卡" }
            " $subId"
        }
        val target = if (operation == "set5g") { require(allowed != null); if (allowed) " 1" else " 0" } else ""
        val process = ProcessBuilder("su", "-c", "CLASSPATH='$apk' app_process /system/bin dev.qcom.efs.platform.PhoneAccess $operation$subscription$target")
            .redirectErrorStream(true).start()
        process.outputStream.close()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val deadline = timer.schedule({ process.destroyForcibly() }, 25, TimeUnit.SECONDS)
        try {
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "Root 手机服务不可用或超时" }
            val result = lines.lastOrNull { it.startsWith("{") && it.endsWith("}") }?.let(::JSONObject)
                ?: error("无法读取手机服务结果")
            check(result.optBoolean("ok")) { result.optString("error", "手机服务访问失败") }
            result
        } finally {
            deadline.cancel(false); timer.shutdownNow(); process.destroyForcibly()
        }
    }
}
