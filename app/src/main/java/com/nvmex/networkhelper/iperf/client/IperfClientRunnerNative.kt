package com.nvmex.networkhelper.iperf.client

import com.nvmex.networkhelper.iperf.IperfNative
import com.nvmex.networkhelper.iperf.IperfRunOutput

class IperfClientRunnerNative {

    private val lock = Any()

    @Volatile private var running = false
    @Volatile private var stopRequested = false

    fun isRunning(): Boolean = running

    fun stop(onLine: ((String) -> Unit)? = null) {
        val shouldStop = synchronized(lock) {
            if (!running) return
            if (stopRequested) return
            stopRequested = true
            true
        }
        if (shouldStop) {
            onLine?.invoke("Stop requested -> native stop()")
            IperfNative.stop()
        }
    }

    fun runOnce(
        cfg: IperfClientConfig,
        onLine: (String) -> Unit = {}
    ): IperfRunOutput {
        synchronized(lock) {
            if (running) error("iperf is already running")
            running = true
            stopRequested = false
        }

        try {
            val args = IperfArgsBuilder.buildClientArgs(cfg)
            onLine("CLI (debug only): ${args.joinToString(" ")}")

            val host = cfg.serverHost.trim()
            val port = cfg.port.coerceIn(1, 65535)
            val seconds = cfg.durationSec.coerceAtLeast(1)
            val parallel = cfg.parallel.coerceAtLeast(1)
            val reverse = cfg.reverse
            val udp = (cfg.protocol == IperfProtocol.UDP)

            val udpBitrateBps = if (udp) {
                val parsed = parseBitrateToBps(cfg.udpBandwidth)
                if (parsed > 0L) parsed else 1_000_000L // 默认 1Mbps
            } else 0L

            onLine("JNI runJson: host=$host port=$port t=$seconds P=$parallel R=$reverse udp=$udp bps=$udpBitrateBps")

            val json = IperfNative.runJson(
                host = host,
                port = port,
                seconds = seconds,
                parallel = parallel,
                reverse = reverse,
                udp = udp,
                udpBitrateBps = udpBitrateBps
            )

            onLine("JNI json head: " + json.take(500))

            return IperfRunOutput(rawText = json, jsonText = json)
        } finally {
            synchronized(lock) {
                running = false
                stopRequested = false
            }
        }
    }

    /**
     * ✅新增：流式运行
     * onLine 会收到 NDJSON 的每一行（start/interval/end）
     */
    fun runOnceStream(
        cfg: IperfClientConfig,
        onLine: (String) -> Unit = {}
    ): IperfRunOutput {
        synchronized(lock) {
            if (running) error("iperf is already running")
            running = true
            stopRequested = false
        }

        try {
            val host = cfg.serverHost.trim()
            val port = cfg.port.coerceIn(1, 65535)
            val seconds = cfg.durationSec.coerceAtLeast(1)
            val parallel = cfg.parallel.coerceAtLeast(1)
            val reverse = cfg.reverse
            val udp = (cfg.protocol == IperfProtocol.UDP)

            val udpBitrateBps = if (udp) {
                val parsed = parseBitrateToBps(cfg.udpBandwidth)
                if (parsed > 0L) parsed else 1_000_000L
            } else 0L

            onLine("JNI runJsonStream: host=$host port=$port t=$seconds P=$parallel R=$reverse udp=$udp bps=$udpBitrateBps")

            val json = IperfNative.runJsonStream(
                host = host,
                port = port,
                seconds = seconds,
                parallel = parallel,
                reverse = reverse,
                udp = udp,
                udpBitrateBps = udpBitrateBps,
                cb = object : IperfNative.StreamCallback {
                    override fun onLine(line: String) {
                        onLine(line) // 每行 NDJSON
                    }
                }
            )

            onLine("JNI final json head: " + json.take(500))
            return IperfRunOutput(rawText = json, jsonText = json)
        } finally {
            synchronized(lock) {
                running = false
                stopRequested = false
            }
        }
    }

    private fun parseBitrateToBps(text: String): Long {
        val s = text.trim().uppercase()
        if (s.isEmpty() || s == "0") return 0L

        val multiplier = when {
            s.endsWith("G") -> 1_000_000_000L
            s.endsWith("M") -> 1_000_000L
            s.endsWith("K") -> 1_000L
            else -> 1L
        }
        val numberPart = if (multiplier == 1L) s else s.dropLast(1)
        val value = numberPart.toDoubleOrNull() ?: return 0L
        return (value * multiplier).toLong().coerceAtLeast(0L)
    }
}