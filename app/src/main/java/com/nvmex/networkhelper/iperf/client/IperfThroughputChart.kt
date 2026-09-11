package com.nvmex.networkhelper.iperf.client

import org.json.JSONObject

data class IperfPoint(
    val tSec: Double,     // 时间（秒）
    val mbps: Double      // 吞吐（Mbps）
)

/**
 * 从 iperf3 NDJSON（每行一个 JSON event）里提取 interval 的 bits_per_second。
 * 目标：只画吞吐（Mbps/Gbps），其它一律不画。
 *
 * 兼容策略：
 * - 优先识别 event == "interval"
 * - 然后在 data 内按顺序找：sum -> sum_received -> sum_sent -> streams[0]
 * - 找到 bits_per_second 即可
 *
 * 注意：不同 iperf 版本/分支，字段层级可能略变；这里用“宽松、尽量提取”的写法。
 */
object IperfStreamParser {

    fun parseIntervalPoint(line: String, fallbackIndex: Int): IperfPoint? {
        val s = line.trim()
        if (s.isEmpty() || !s.startsWith("{")) return null

        val obj = runCatching { JSONObject(s) }.getOrNull() ?: return null

        // 不是 interval 事件就跳过（start/end/error 不画）
        val event = obj.optString("event", "")
        if (event.isNotEmpty() && event != "interval") return null

        // 某些实现可能没有 event 字段，但 interval 行一般会有 data/streams/sum
        val data = obj.optJSONObject("data") ?: obj

        val bps = extractBps(data) ?: return null

        // 尝试取 interval 的 end 时间（更平滑）；取不到就用点序号近似
        val t = extractTimeSec(data) ?: fallbackIndex.toDouble()

        return IperfPoint(
            tSec = t,
            mbps = bps / 1_000_000.0
        )
    }

    private fun extractTimeSec(data: JSONObject): Double? {
        // 常见：interval: { start: 0.0, end: 1.0 } 或 intervals[...]
        data.optJSONObject("interval")?.let { interval ->
            val end = interval.optDouble("end", Double.NaN)
            if (!end.isNaN()) return end
        }
        val end = data.optDouble("seconds", Double.NaN)
        if (!end.isNaN()) return end
        return null
    }

    private fun extractBps(data: JSONObject): Double? {
        // 1) UDP 常见：data.sum.bits_per_second
        data.optJSONObject("sum")?.optDouble("bits_per_second")?.let { if (!it.isNaN()) return it }

        // 2) TCP 常见：data.sum_received / sum_sent
        data.optJSONObject("sum_received")?.optDouble("bits_per_second")?.let { if (!it.isNaN()) return it }
        data.optJSONObject("sum_sent")?.optDouble("bits_per_second")?.let { if (!it.isNaN()) return it }

        // 3) streams[0].bits_per_second（有些 interval 会是 per-stream）
        data.optJSONArray("streams")?.let { arr ->
            if (arr.length() > 0) {
                val s0 = arr.optJSONObject(0)
                s0?.optDouble("bits_per_second")?.let { if (!it.isNaN()) return it }
                // 有些是 sender/receiver 结构
                s0?.optJSONObject("sender")?.optDouble("bits_per_second")?.let { if (!it.isNaN()) return it }
                s0?.optJSONObject("receiver")?.optDouble("bits_per_second")?.let { if (!it.isNaN()) return it }
            }
        }

        // 4) 兜底：顶层直接给 bits_per_second（极少见）
        data.optDouble("bits_per_second", Double.NaN).let { if (!it.isNaN()) return it }

        return null
    }
}


private fun formatMbpsOrGbps(mbps: Double): String {
    return if (mbps >= 1000.0) {
        "${"%.2f".format(mbps / 1000.0)} Gbps"
    } else {
        "${"%.0f".format(mbps)} Mbps"
    }
}
