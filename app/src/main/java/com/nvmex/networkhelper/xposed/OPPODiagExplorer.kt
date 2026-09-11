package com.nvmex.networkhelper.xposed

import android.util.Log
import java.io.*

/**
 * 针对OPPO/一加设备的Diag探索
 */
class OPPODiagExplorer {

    companion object {
        private const val TAG = "OPPO-Diag-Explorer"

        // 从日志中发现的路径
        private val OPPO_DIAG_PATHS = arrayOf(
            // 标准路径
            "/dev/diag",
            "/dev/diag_router",
            "/dev/diag0",

            // OPPO特有路径
            "/sys/kernel/debug/diag",
            "/data/diag_logs",
            "/data/vendor/diag",
            "/data/oplus/diag",
            "/data/oppo/diag",

            // 从日志中发现的可能路径
            "/sys/kernel/debug/ims",           // IMS相关
            "/sys/kernel/debug/qmi",           // QMI接口
            "/sys/kernel/debug/ipa",           // 高通IPA
            "/sys/kernel/debug/subsystem",     // 子系统

            // Vendor分区
            "/vendor/etc/diag",
            "/vendor/bin/diag"
        )
    }

    fun explore() {
        Log.i(TAG, "=".repeat(50))
        Log.i(TAG, "🔍 OPPO/一加设备Diag探索")
        Log.i(TAG, "=".repeat(50))

        // 1. 检查已知diag路径
        checkKnownPaths()

        // 2. 检查正在运行的diag进程
        checkDiagProcesses()

        // 3. 检查subsys相关服务
        checkSubsysServices()

        // 4. 检查logcat中的diag日志
        checkDiagInLogcat()

        Log.i(TAG, "=".repeat(50))
        Log.i(TAG, "✅ 探索完成")
        Log.i(TAG, "=".repeat(50))
    }

    private fun checkKnownPaths() {
        Log.i(TAG, "\n📁 检查已知Diag路径...")

        for (path in OPPO_DIAG_PATHS) {
            try {
                val file = File(path)
                if (file.exists()) {
                    val permissions = getPermissions(file)
                    val size = if (file.isFile) "${file.length()}B" else "目录"

                    Log.i(TAG, "✅ 找到: $path")
                    Log.i(TAG, "  类型: ${if (file.isFile) "文件" else "目录"}")
                    Log.i(TAG, "  权限: $permissions")
                    Log.i(TAG, "  大小: $size")

                    // 如果是目录，列出内容
                    if (file.isDirectory) {
                        val files = file.listFiles()
                        if (files != null && files.isNotEmpty()) {
                            Log.i(TAG, "  内容(${files.size}个):")
                            files.take(5).forEach { child ->
                                Log.i(TAG, "    - ${child.name}")
                            }
                            if (files.size > 5) {
                                Log.i(TAG, "    ... 还有${files.size - 5}个")
                            }
                        }
                    }

                    // 尝试读取内容（如果是小文件）
                    if (file.isFile && file.length() < 10240 && file.canRead()) {
                        try {
                            val content = file.readText().trim()
                            if (content.isNotEmpty()) {
                                Log.i(TAG, "  内容预览: ${content.take(100)}...")
                            }
                        } catch (e: Exception) {
                            // 可能是二进制文件
                        }
                    }

                    Log.i(TAG, "  ---")
                } else {
                    Log.d(TAG, "❌ 不存在: $path")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "⚠️ 权限不足: $path")
            } catch (e: Throwable) {
                Log.e(TAG, "检查 $path 失败", e)
            }
        }
    }

    private fun getPermissions(file: File): String {
        return try {
            "R:${file.canRead()} W:${file.canWrite()} X:${file.canExecute()}"
        } catch (e: Exception) {
            "未知"
        }
    }

    private fun checkDiagProcesses() {
        Log.i(TAG, "\n📊 检查Diag相关进程...")

        val diagProcessCommands = arrayOf(
            "ps -A | grep -i diag",
            "ps -A | grep -i qmi",
            "ps -A | grep -i subsys",
            "ps -A | grep -i ims",
            "ps -A | grep -i radio"
        )

        for (cmd in diagProcessCommands) {
            try {
                Log.i(TAG, "执行: $cmd")

                val process = Runtime.getRuntime().exec("su -c '$cmd'")
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                var hasOutput = false
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line?.isNotBlank() == true) {
                        Log.i(TAG, "  $line")
                        hasOutput = true
                    }
                }
                process.waitFor()

                if (!hasOutput) {
                    Log.i(TAG, "  无输出")
                }

            } catch (e: Exception) {
                Log.e(TAG, "执行命令失败: $cmd", e)
            }
        }
    }

    private fun checkSubsysServices() {
        Log.i(TAG, "\n📡 检查Subsys相关服务...")

        // 检查服务列表
        try {
            Log.i(TAG, "检查服务列表...")
            val process = Runtime.getRuntime().exec("su -c 'service list'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { serviceLine ->
                    if (serviceLine.contains("diag", ignoreCase = true) ||
                        serviceLine.contains("subsys", ignoreCase = true) ||
                        serviceLine.contains("radio", ignoreCase = true) ||
                        serviceLine.contains("qmi", ignoreCase = true)) {

                        Log.i(TAG, "🎯 发现相关服务: $serviceLine")
                    }
                }
            }
            process.waitFor()

        } catch (e: Exception) {
            Log.e(TAG, "检查服务失败", e)
        }

        // 检查Binder接口
        try {
            Log.i(TAG, "检查Binder接口...")
            val process = Runtime.getRuntime().exec("su -c 'ls -la /dev/binder'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            reader.readLines().forEach { line ->
                Log.i(TAG, "  $line")
            }
            process.waitFor()

        } catch (e: Exception) {
            Log.e(TAG, "检查Binder失败", e)
        }
    }

    private fun checkDiagInLogcat() {
        Log.i(TAG, "\n📝 从logcat中提取Diag信息...")

        // 保存最近的diag日志
        val diagLogs = mutableListOf<String>()

        try {
            // 读取最近的logcat（只读最后100行，避免太多）
            val process = Runtime.getRuntime().exec("su -c 'logcat -d -t 100'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { logLine ->
                    // 过滤包含diag、qmi、ims、subsys等关键字的日志
                    if (logLine.contains("diag", ignoreCase = true) ||
                        logLine.contains("qmi", ignoreCase = true) ||
                        logLine.contains("ims", ignoreCase = true) ||
                        logLine.contains("subsys", ignoreCase = true) ||
                        logLine.contains("radio", ignoreCase = true) ||
                        logLine.contains("netid", ignoreCase = true) ||
                        logLine.contains("mtu", ignoreCase = true) ||
                        logLine.contains("apn", ignoreCase = true)) {

                        diagLogs.add(logLine)
                    }
                }
            }
            process.waitFor()

            // 输出发现的diag日志
            if (diagLogs.isNotEmpty()) {
                Log.i(TAG, "✅ 发现 ${diagLogs.size} 条diag相关日志")

                // 按关键词分组输出
                val imsLogs = diagLogs.filter { it.contains("ims", ignoreCase = true) }
                val netidLogs = diagLogs.filter { it.contains("netid", ignoreCase = true) }
                val mtuLogs = diagLogs.filter { it.contains("mtu", ignoreCase = true) }
                val qosLogs = diagLogs.filter { it.contains("qos", ignoreCase = true) }

                if (imsLogs.isNotEmpty()) {
                    Log.i(TAG, "\n📞 IMS相关日志(${imsLogs.size}条):")
                    imsLogs.take(3).forEach { log ->
                        Log.i(TAG, "  ${extractTime(log)} - ${extractMessage(log)}")
                    }
                }

                if (netidLogs.isNotEmpty()) {
                    Log.i(TAG, "\n🌐 NetID相关日志(${netidLogs.size}条):")
                    netidLogs.take(3).forEach { log ->
                        Log.i(TAG, "  ${extractTime(log)} - ${extractMessage(log)}")

                        // 尝试提取NetID值
                        extractNetID(log)?.let { netid ->
                            Log.i(TAG, "    🎯 NetID: $netid")
                        }
                    }
                }

                if (mtuLogs.isNotEmpty()) {
                    Log.i(TAG, "\n🔧 MTU相关日志(${mtuLogs.size}条):")
                    mtuLogs.take(3).forEach { log ->
                        Log.i(TAG, "  ${extractTime(log)} - ${extractMessage(log)}")

                        // 尝试提取MTU值
                        extractMTU(log)?.let { mtu ->
                            Log.i(TAG, "    🎯 MTU: $mtu")
                        }
                    }
                }

                if (qosLogs.isNotEmpty()) {
                    Log.i(TAG, "\n🎯 QoS相关日志(${qosLogs.size}条):")
                    qosLogs.forEach { log ->
                        Log.i(TAG, "  ${extractTime(log)} - ${extractMessage(log)}")
                    }
                }

            } else {
                Log.i(TAG, "ℹ️ 未在logcat中发现diag相关日志")
            }

        } catch (e: Exception) {
            Log.e(TAG, "读取logcat失败", e)
        }
    }

    private fun extractTime(log: String): String {
        // 尝试提取时间戳 (格式: MM-DD HH:MM:SS.mmm)
        val timeRegex = "\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}".toRegex()
        return timeRegex.find(log)?.value ?: "未知时间"
    }

    private fun extractMessage(log: String): String {
        // 提取日志消息部分（去掉时间戳和标签）
        val parts = log.split(": ", limit = 2)
        return if (parts.size == 2) parts[1].trim() else log
    }

    private fun extractNetID(log: String): String? {
        // 尝试提取NetID (格式: NetID ===: 450082295821)
        val regex = "(?i)netid\\s*[:=]\\s*(\\d+)".toRegex()
        return regex.find(log)?.groupValues?.get(1)
    }

    private fun extractMTU(log: String): String? {
        // 尝试提取MTU值 (格式: MTU: 1500)
        val regex = "(?i)mtu\\s*[:=]\\s*(\\d+)".toRegex()
        return regex.find(log)?.groupValues?.get(1)
    }
}