package com.nvmex.networkhelper.util.menu

import java.io.File
import java.util.zip.ZipFile

/** Share bundles and legacy standalone Shadow plugin ZIPs. Framework is not flashed. */
object PluginArchive {
    const val MAX_BYTES = 200L * 1024 * 1024

    fun prepare(download: File, extracted: File): File {
        ZipFile(download).use { archive ->
            val entries = archive.entries().asSequence().toList()
            require(entries.all { entry ->
                val name = entry.name.replace('\\', '/')
                !name.startsWith('/') && !name.contains(':') && name.split('/').none { it == ".." }
            }) { "下载包包含非法路径" }
            val plugins = entries.filter { !it.isDirectory && it.name.replace('\\', '/').substringAfterLast('/') == "plugin-release.zip" }
            require(plugins.size <= 1) { "下载包包含多个插件，无法确定写入对象" }
            if (plugins.isEmpty()) {
                require(entries.none { it.name.replace('\\', '/').startsWith("system_ext/") }) { "分享包缺少 plugin-release.zip" }
                validate(download)
                return download
            }
            val entry = plugins.single()
            require(entry.size in 1..MAX_BYTES) { "插件大小超过限制或为空" }
            try {
                archive.getInputStream(entry).use { input ->
                    extracted.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            require(total <= MAX_BYTES) { "解压后的插件过大" }
                            output.write(buffer, 0, size)
                        }
                        require(total == entry.size) { "插件数据不完整" }
                    }
                }
                val crc = java.util.zip.CRC32()
                extracted.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val size = input.read(buffer); if (size < 0) break; crc.update(buffer, 0, size) }
                }
                require(crc.value == entry.crc) { "插件校验失败" }
                validate(extracted)
                return extracted
            } catch (error: Exception) { extracted.delete(); throw error }
        }
    }

    fun validate(plugin: File) {
        ZipFile(plugin).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            require(entries.any { it.name == "config.json" } && entries.any { it.name.endsWith(".apk", true) }) {
                "不是有效的 Shadow 工程插件（缺少 config.json 或 APK）"
            }
        }
    }
}
