package com.nvmex.networkhelper.xposed

import android.util.Log
import java.io.*
import java.net.*

/**
 * diag-router客户端 - 直接连接诊断端口
 */
class DiagRouterClient {

    companion object {
        private const val TAG = "Diag-Router"

        // 可能的diag端口（不同设备可能不同）
        private val DIAG_PORTS = arrayOf(
            "/dev/diag",           // 标准diag设备
            "/dev/diag_router",    // diag路由器
            "/dev/diag0",          // 多端口情况
            "/dev/diag1",
            "/sys/kernel/debug/diag", // debugfs接口
            "/data/diag_logs"          // 日志目录
        )

        // QMI服务ID（可能包含QoS信息）
        private const val QMI_NAS_SERVICE = 0x01  // 网络访问服务
        private const val QMI_WDS_SERVICE = 0x01  // 无线数据服务
    }

    /**
     * 探索可用的diag接口
     */
    fun exploreDiagInterfaces(): List<String> {
        Log.i(TAG, "🔍 探索diag接口...")

        val availableInterfaces = mutableListOf<String>()

        for (port in DIAG_PORTS) {
            try {
                val file = File(port)
                if (file.exists()) {
                    val permissions = getFilePermissions(file)
                    Log.i(TAG, "✅ 找到: $port ($permissions)")
                    availableInterfaces.add(port)

                    // 尝试读取一些信息
                    if (file.isDirectory) {
                        Log.i(TAG, "  是目录，内容:")
                        file.listFiles()?.take(5)?.forEach { child ->
                            Log.i(TAG, "    - ${child.name}")
                        }
                    }
                } else {
                    Log.d(TAG, "❌ 不存在: $port")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "⚠️ 权限不足: $port")
            } catch (e: Throwable) {
                Log.e(TAG, "检查 $port 失败", e)
            }
        }

        return availableInterfaces
    }

    /**
     * 获取文件权限
     */
    private fun getFilePermissions(file: File): String {
        return try {
            val canRead = file.canRead()
            val canWrite = file.canWrite()
            val canExecute = file.canExecute()
            "R:$canRead W:$canWrite X:$canExecute"
        } catch (e: Exception) {
            "未知权限"
        }
    }

    /**
     * 尝试直接读取diag数据
     */
    fun tryReadDiagData(interfacePath: String) {
        Log.i(TAG, "📡 尝试读取diag数据: $interfacePath")

        try {
            when {
                interfacePath.startsWith("/dev/") -> {
                    // 字符设备文件
                    readCharacterDevice(interfacePath)
                }
                interfacePath.startsWith("/sys/") -> {
                    // sysfs接口
                    readSysFs(interfacePath)
                }
                interfacePath.startsWith("/data/") -> {
                    // 数据文件/目录
                    exploreDataDirectory(interfacePath)
                }
                else -> {
                    Log.w(TAG, "未知接口类型: $interfacePath")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "读取diag数据失败", e)
        }
    }

    /**
     * 读取字符设备
     */
    private fun readCharacterDevice(path: String) {
        Log.i(TAG, "读取字符设备: $path")

        try {
            // 注意：这需要root权限
            Runtime.getRuntime().exec("su -c 'ls -la $path'").let { process ->
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.i(TAG, "  $line")
                }
                process.waitFor()
            }

            // 尝试hexdump（只读少量数据）
            Log.i(TAG, "尝试hexdump前16字节:")
            Runtime.getRuntime().exec("su -c 'dd if=$path bs=1 count=16 2>/dev/null | hexdump -C'").let { process ->
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.i(TAG, "  $line")
                }
                process.waitFor()
            }

        } catch (e: Exception) {
            Log.e(TAG, "读取字符设备失败", e)
        }
    }

    /**
     * 读取sysfs接口
     */
    private fun readSysFs(path: String) {
        Log.i(TAG, "读取sysfs: $path")

        try {
            val dir = File(path)
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    try {
                        if (file.isFile && file.canRead()) {
                            val content = file.readText().trim()
                            if (content.isNotEmpty()) {
                                Log.i(TAG, "  ${file.name}: $content")
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略单个文件读取失败
                    }
                }
            } else if (dir.isFile && dir.canRead()) {
                val content = dir.readText().trim()
                Log.i(TAG, "  内容: $content")
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取sysfs失败", e)
        }
    }

    /**
     * 探索数据目录
     */
    private fun exploreDataDirectory(path: String) {
        Log.i(TAG, "探索数据目录: $path")

        try {
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles()
                Log.i(TAG, "  包含 ${files?.size ?: 0} 个文件")

                files?.take(10)?.forEach { file ->
                    val size = if (file.isFile) "${file.length()}B" else "目录"
                    val modified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(java.util.Date(file.lastModified()))
                    Log.i(TAG, "  ${file.name} ($size, 修改: $modified)")
                }

                // 查找可能的日志文件
                files?.filter { it.name.contains("log", ignoreCase = true) }?.forEach { logFile ->
                    Log.i(TAG, "📄 发现日志文件: ${logFile.name}")
                    tryReadLogFile(logFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "探索目录失败", e)
        }
    }

    /**
     * 尝试读取日志文件
     */
    private fun tryReadLogFile(logFile: File) {
        try {
            // 读取文件末尾（避免读取整个大文件）
            val process = Runtime.getRuntime().exec("su -c 'tail -n 20 ${logFile.absolutePath}'")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            Log.i(TAG, "  ${logFile.name} 最后20行:")
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                // 过滤可能包含QoS信息的内容
                if (line?.contains("QOS", ignoreCase = true) == true ||
                    line?.contains("AMBR", ignoreCase = true) == true ||
                    line?.contains("BEARER", ignoreCase = true) == true ||
                    line?.contains("qci", ignoreCase = true) == true) {
                    Log.i(TAG, "    🎯 $line")
                } else {
                    Log.d(TAG, "    $line")
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "读取日志文件失败", e)
        }
    }
}