package com.nvmex.networkhelper.iperf.client

object IperfArgsBuilder {

    fun buildClientArgs(cfg: IperfClientConfig): List<String> {
        val args = mutableListOf<String>()

        // argv[0]：占位即可（iperf 的参数解析需要它）
        args += "iperf3"

        args += "-c"; args += cfg.serverHost
        args += "-p"; args += cfg.port.toString()

        // 强制 JSON 输出（你 UI 依赖它）
        args += "-J"
        args += "-i"; args += cfg.intervalSec.toString()
        args += "-t"; args += cfg.durationSec.toString()

        if (cfg.parallel > 1) {
            args += "-P"; args += cfg.parallel.toString()
        }
        if (cfg.reverse) args += "-R"
        if (cfg.omitSec > 0) { args += "-O"; args += cfg.omitSec.toString() }

        cfg.bindAddress?.takeIf { it.isNotBlank() }?.let { args += "-B"; args += it }
        cfg.tos?.let { args += "-S"; args += it.toString() }
        cfg.mss?.let { args += "-M"; args += it.toString() }

        if (cfg.protocol == IperfProtocol.UDP) {
            args += "-u"
            cfg.udpBandwidth.takeIf { it.isNotBlank() && it != "0" }?.let { args += "-b"; args += it }
            cfg.udpPacketLen?.let { args += "-l"; args += it.toString() }
        }

        args += cfg.extraArgs
        return args
    }
}
