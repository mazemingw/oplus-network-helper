package com.nvmex.networkhelper.xposed

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Subsys服务客户端
 */
class SubsysClient {

    companion object {
        private const val TAG = "Diag-Subsys-Client"
    }

    fun exploreSubsys() {
        Log.i(TAG, "🔍 探索Subsys服务...")

        try {
            // 1. 检查subsys包是否存在
            checkSubsysPackage()

            // 2. 尝试与DiagnoseService交互
            interactWithDiagnoseService()

            // 3. 尝试获取RadioProxy
            exploreRadioProxy()

        } catch (e: Exception) {
            Log.e(TAG, "探索失败", e)
        }
    }

    private fun checkSubsysPackage() {
        Log.i(TAG, "检查subsys包...")

        try {
            // 检查包是否安装
            val process = Runtime.getRuntime().exec("su -c 'pm path com.oplus.subsys'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.i(TAG, "✅ subsys包路径: $line")
            }
            process.waitFor()

        } catch (e: Exception) {
            Log.e(TAG, "检查包失败", e)
        }
    }

    private fun interactWithDiagnoseService() {
        Log.i(TAG, "尝试与DiagnoseService交互...")

        // 通过am命令调用服务
        val commands = arrayOf(
            "am startservice -n com.oplus.subsys/.DiagnoseService",
            "am broadcast -a com.oplus.subsys.DIAG_ACTION",
            "am broadcast -a android.intent.action.DIAG"
        )

        for (cmd in commands) {
            try {
                Log.i(TAG, "执行: $cmd")
                val process = Runtime.getRuntime().exec("su -c '$cmd'")
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                var output = ""
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output += line + "\n"
                }
                process.waitFor()

                if (output.isNotBlank()) {
                    Log.i(TAG, "  输出: $output")
                }

            } catch (e: Exception) {
                Log.d(TAG, "命令失败: ${e.message}")
            }
        }
    }

    private fun exploreRadioProxy() {
        Log.i(TAG, "探索RadioProxy...")

        // 尝试获取radio相关日志
        try {
            // 读取radio日志缓冲区
            val process = Runtime.getRuntime().exec("su -c 'logcat -b radio -d'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val radioLogs = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { logLine ->
                    if (logLine.contains("QOS", ignoreCase = true) ||
                        logLine.contains("AMBR", ignoreCase = true) ||
                        logLine.contains("BEARER", ignoreCase = true) ||
                        logLine.contains("setupDataCall", ignoreCase = true)) {

                        radioLogs.add(logLine)
                    }
                }
            }
            process.waitFor()

            if (radioLogs.isNotEmpty()) {
                Log.i(TAG, "✅ 发现 ${radioLogs.size} 条radio相关日志")
                radioLogs.take(5).forEach { log ->
                    Log.i(TAG, "  ${extractMessage(log)}")
                }
            } else {
                Log.i(TAG, "ℹ️ 未在radio缓冲区发现相关日志")
            }

        } catch (e: Exception) {
            Log.e(TAG, "读取radio日志失败", e)
        }
    }

    private fun extractMessage(log: String): String {
        val parts = log.split(": ", limit = 2)
        return if (parts.size == 2) parts[1].trim() else log
    }
}