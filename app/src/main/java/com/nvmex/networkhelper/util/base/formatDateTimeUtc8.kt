package com.nvmex.networkhelper.util.base
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

 fun formatDateTimeUtc8(raw: String?): String {
    if (raw.isNullOrBlank()) return "-"

    val zone = ZoneId.of("Asia/Shanghai")
    val outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    return try {
        val text = raw.trim()

        when {
            // 1) 纯数字时间戳：支持秒 / 毫秒
            text.all { it.isDigit() } -> {
                val value = text.toLong()
                val instant = if (text.length >= 13) {
                    Instant.ofEpochMilli(value)
                } else {
                    Instant.ofEpochSecond(value)
                }
                outputFormatter.format(instant.atZone(zone))
            }

            // 2) 标准 UTC/ISO 字符串，例如 2026-03-27T11:21:53.000Z
            text.contains("T") && (text.endsWith("Z") || text.contains("+")) -> {
                val instant = Instant.parse(text)
                outputFormatter.format(instant.atZone(zone))
            }

            // 3) MySQL 样式：2026-03-27 11:21:53
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(text) -> {
                val ldt = LocalDateTime.parse(
                    text,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                )
                // 这里按 UTC 存储处理，再转东八区
                outputFormatter.format(ldt.atZone(ZoneOffset.UTC).withZoneSameInstant(zone))
            }

            // 4) MySQL 样式带毫秒：2026-03-27 11:21:53.000
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+""").matches(text) -> {
                val normalized = text.substringBefore(".") + "." +
                        text.substringAfter(".").padEnd(3, '0').take(3)

                val ldt = LocalDateTime.parse(
                    normalized,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                )
                outputFormatter.format(ldt.atZone(ZoneOffset.UTC).withZoneSameInstant(zone))
            }

            else -> raw
        }
    } catch (_: Throwable) {
        raw
    }
}