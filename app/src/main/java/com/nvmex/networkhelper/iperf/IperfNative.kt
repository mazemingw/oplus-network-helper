package com.nvmex.networkhelper.iperf

import androidx.annotation.Keep

object IperfNative {
    init { System.loadLibrary("nh_iperf") }

    /**
     * 流式回调：native 每输出一行 NDJSON，就调用一次 onLine(line)
     * 注意：release 混淆会导致 JNI 找不到 onLine，所以必须 Keep
     */
    @Keep
    interface StreamCallback {
        fun onLine(line: String)
    }

    /**
     * 对齐 iperf3 CLI：
     * - host: -c host
     * - port: -p
     * - seconds: -t
     * - parallel: -P
     * - reverse: -R
     * - udp: -u
     * - udpBitrateBps: -b (bps). 例如 1G = 1_000_000_000
     */
    external fun runJson(
        host: String,
        port: Int,
        seconds: Int,
        parallel: Int,
        reverse: Boolean,
        udp: Boolean,
        udpBitrateBps: Long
    ): String

    /**
     * 流式（NDJSON，每行回调一次）
     * 仅发送停止信号；是否立即返回取决于 native/iperf 内部退出时机
     */
    external fun runJsonStream(
        host: String,
        port: Int,
        seconds: Int,
        parallel: Int,
        reverse: Boolean,
        udp: Boolean,
        udpBitrateBps: Long,
        cb: StreamCallback
    ): String

    /** 请求停止当前测试（best-effort） */
    external fun stop()


    // 服务端流式（NDJSON，每行回调一次）
    external fun runServerStream(
        port: Int,
        cb: StreamCallback
    ): String

}
