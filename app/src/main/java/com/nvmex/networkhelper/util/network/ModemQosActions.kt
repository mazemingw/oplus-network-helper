package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.util.shell.ShellResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Bounded radio reset. Always restore the radio, including coroutine cancellation. */
internal suspend fun reregisterForQos(
    exec: (String, Long) -> ShellResult,
    assertSafe: () -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) }
) {
    assertSafe()
    val initial = exec("cmd connectivity airplane-mode", 3_000)
    check(initial.code == 0 && initial.out.trim() == "disabled") { "飞行模式已开启或状态无法确认，不执行重注册" }
    // Recheck immediately before changing either SIM's radio state.
    assertSafe()
    try {
        val enabled = exec("cmd connectivity airplane-mode enable", 4_000)
        check(enabled.code == 0) { "无法开启短暂飞行模式：${enabled.err}" }
        pause(1_000)
    } finally {
        withContext(NonCancellable) {
            var restored = exec("cmd connectivity airplane-mode disable", 4_000)
            if (restored.code != 0) restored = exec("cmd connectivity airplane-mode disable", 4_000)
            check(restored.code == 0) { "未能恢复网络，请立即手动关闭飞行模式" }
        }
    }
}

internal object DiagCleanup {
    // Original ZIP targets. ipacm-diag is intentionally excluded; no wildcard kills.
    val targets = listOf(
        "make.more.r2d2.play.cellular_pro",
        "make.more.r2d2.cellular_pro",
        "/data/user/0/com.qtrun.QuickTest/files/bridge",
        "com.qtrun.QuickTest",
        "diag-router"
    )

    fun run(exec: (String, Long) -> ShellResult): String {
        val tools = exec("command -v pidof >/dev/null && command -v killall >/dev/null", 2_000)
        check(tools.code == 0) { "系统缺少 pidof / killall，未执行清理" }
        return targets.joinToString("\n") { target ->
            val result = exec(
                "if pidof '$target' >/dev/null 2>&1; then killall -v '$target'; else echo NOT_RUNNING; fi", 3_000
            )
            when {
                result.code != 0 -> "$target: 清理失败 (${result.err.trim().take(300).ifBlank { result.code.toString() }})"
                result.out.trim() == "NOT_RUNNING" -> "$target: 未运行，跳过"
                else -> "$target: 已发送结束信号"
            }
        }
    }
}
