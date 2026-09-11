package com.nvmex.networkhelper.iperf

import com.nvmex.networkhelper.iperf.client.IperfClientConfig
import com.nvmex.networkhelper.iperf.client.IperfProtocol
import org.json.JSONObject

data class IperfSummary(
    val protocol: IperfProtocol,
    val seconds: Double,
    val parallel: Int,
    val reverse: Boolean,
    val senderMbps: Double? = null,
    val receiverMbps: Double? = null,
    val jitterMs: Double? = null,
    val lostPercent: Double? = null,
    val error: String? = null
)

object IperfJsonParser {

    fun parseSummary(jsonText: String, cfg: IperfClientConfig): IperfSummary {
        return try {
            val root = JSONObject(jsonText)

            // ✅ 0) 兼容流式 NDJSON：{"event":"end","data":{...}}
            // ✅ 0) 兼容流式 NDJSON：{"event":"end","data":{...}}
            if (root.optString("event") == "end") {
                val data = root.optJSONObject("data")
                    ?: return IperfSummary(
                        protocol = cfg.protocol,
                        seconds = cfg.durationSec.toDouble(),
                        parallel = cfg.parallel,
                        reverse = cfg.reverse,
                        error = "iperf stream missing field: data"
                    )

                // ✅关键：有些实现是 data.end，有些实现 end 就是 data 本身
                val endObj = data.optJSONObject("end") ?: data

                val proto = cfg.protocol
                val reverse = cfg.reverse
                val parallel = cfg.parallel

                val seconds = when (proto) {
                    IperfProtocol.TCP ->
                        endObj.optJSONObject("sum_sent")?.optDouble("seconds")
                            ?: endObj.optJSONObject("sum_received")?.optDouble("seconds")
                            ?: cfg.durationSec.toDouble()

                    IperfProtocol.UDP ->
                        endObj.optJSONObject("sum")?.optDouble("seconds")
                            ?: cfg.durationSec.toDouble()
                }

                return when (proto) {
                    IperfProtocol.TCP -> {
                        val sumSent = endObj.optJSONObject("sum_sent")
                        val sumRecv = endObj.optJSONObject("sum_received")

                        val senderBps = sumSent?.optDouble("bits_per_second")
                        val receiverBps = sumRecv?.optDouble("bits_per_second")

                        IperfSummary(
                            protocol = proto,
                            seconds = seconds,
                            parallel = parallel,
                            reverse = reverse,
                            senderMbps = senderBps?.let { it / 1_000_000.0 },
                            receiverMbps = receiverBps?.let { it / 1_000_000.0 }
                        )
                    }

                    IperfProtocol.UDP -> {
                        val sum = endObj.optJSONObject("sum")
                        val bps = sum?.optDouble("bits_per_second")
                        val jitterMs = sum?.optDouble("jitter_ms")
                        val lostPercent = sum?.optDouble("lost_percent")

                        IperfSummary(
                            protocol = proto,
                            seconds = seconds,
                            parallel = parallel,
                            reverse = reverse,
                            senderMbps = bps?.let { it / 1_000_000.0 },
                            receiverMbps = null,
                            jitterMs = jitterMs,
                            lostPercent = lostPercent
                        )
                    }
                }
            }


            // ✅ 1) 先处理 JNI 的错误 JSON：{"error":true,"message":"..."}
            if (root.optBoolean("error", false)) {
                val msg = root.optString("message", "iperf error")
                return IperfSummary(
                    protocol = cfg.protocol,
                    seconds = cfg.durationSec.toDouble(),
                    parallel = cfg.parallel,
                    reverse = cfg.reverse,
                    error = msg
                )
            }

            // ——你原来的逻辑从这里开始保持不变——
            val start = root.optJSONObject("start")
            val testStart = start?.optJSONObject("test_start")

            val proto =
                if (testStart?.optString("protocol") == "UDP") IperfProtocol.UDP else IperfProtocol.TCP
            val reverse = testStart?.optInt("reverse", if (cfg.reverse) 1 else 0) == 1
            val parallel = testStart?.optInt("num_streams", cfg.parallel) ?: cfg.parallel

            val end = root.optJSONObject("end")
                ?: return IperfSummary(
                    protocol = proto,
                    seconds = cfg.durationSec.toDouble(),
                    parallel = parallel,
                    reverse = reverse,
                    error = "iperf JSON missing field: end"
                )

            val seconds = when (proto) {
                IperfProtocol.TCP -> end.optJSONObject("sum_sent")?.optDouble("seconds")
                    ?: cfg.durationSec.toDouble()
                IperfProtocol.UDP -> end.optJSONObject("sum")?.optDouble("seconds")
                    ?: cfg.durationSec.toDouble()
            }

            when (proto) {
                IperfProtocol.TCP -> {
                    val sumSent = end.optJSONObject("sum_sent")
                    val sumRecv = end.optJSONObject("sum_received")
                    val senderBps = sumSent?.optDouble("bits_per_second")
                    val receiverBps = sumRecv?.optDouble("bits_per_second")

                    IperfSummary(
                        protocol = proto,
                        seconds = seconds,
                        parallel = parallel,
                        reverse = reverse,
                        senderMbps = senderBps?.let { it / 1_000_000.0 },
                        receiverMbps = receiverBps?.let { it / 1_000_000.0 }
                    )
                }

                IperfProtocol.UDP -> {
                    val sum = end.optJSONObject("sum")
                    val bps = sum?.optDouble("bits_per_second")
                    val jitterMs = sum?.optDouble("jitter_ms")
                    val lostPercent = sum?.optDouble("lost_percent")

                    IperfSummary(
                        protocol = proto,
                        seconds = seconds,
                        parallel = parallel,
                        reverse = reverse,
                        senderMbps = bps?.let { it / 1_000_000.0 },
                        receiverMbps = null,
                        jitterMs = jitterMs,
                        lostPercent = lostPercent
                    )
                }
            }
        } catch (t: Throwable) {
            IperfSummary(
                protocol = cfg.protocol,
                seconds = cfg.durationSec.toDouble(),
                parallel = cfg.parallel,
                reverse = cfg.reverse,
                error = t.message ?: "JSON parse error"
            )
        }
    }

}
