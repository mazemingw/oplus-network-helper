package com.nvmex.networkhelper.util.modem

import android.content.Context
import android.os.Process
import com.nvmex.networkhelper.util.qmi.QmiSessionRecovery
import dev.ririxi.bunnylock.data.RootClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/** Establishes no NAS binding: a full client table must not prevent its own cleanup. */
suspend fun strongModemCleanup(context: Context, diagnostics: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
    val known = runCatching { QmiSessionRecovery(context).releaseBandMenu() }
    val helper = RootClient(context)
    try {
        val hello = helper.start()
        check(hello.optBoolean("qrtrLib")) { "无法加载通道占用扫描组件；已尝试清理已知后端" }
        val result = helper.call("host.cleanupQmiOwners", 60.seconds) {
            put("hostUid", Process.myUid())
            put("hostPackage", context.packageName)
            put("hostApk", context.applicationInfo.sourceDir)
        }
        val failures = result.getJSONArray("failures")
        val released = result.getJSONArray("released").length()
        android.util.Log.i("ModemCleanup", result.toString())
        diagnostics(result.toString())
        check(failures.length() == 0) { "部分占用进程未能释放：$failures" }
        buildString {
            append("已成功释放后端服务")
            append("\n扫描释放 $released 个额外占用进程")
            if (known.getOrNull() == true) append("；已清理已知竞争后端")
            if (result.optBoolean("unsupported") || result.optBoolean("truncated") || result.optInt("denied") > 0)
                append("\n部分进程受内核或权限限制，未宣称全部清理；详情见诊断日志")
            known.exceptionOrNull()?.let { append("\n已知后端检查：${it.message}") }
        }
    } finally { helper.stop() }
}
