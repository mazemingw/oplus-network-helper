package com.nvmex.networkhelper.iperf.client

enum class IperfProtocol { TCP, UDP }

data class IperfClientConfig(
    val serverHost: String = "192.168.1.1",
    val port: Int = 5201,

    val protocol: IperfProtocol = IperfProtocol.TCP,

    // 常用
    val durationSec: Int = 10,      // -t
    val parallel: Int = 4,          // -P（压榨吞吐必备）
    val reverse: Boolean = false,   // -R（反向：服务端发给客户端）
    val intervalSec: Int = 1,       // -i（报告间隔）

    // UDP 专用
    val udpBandwidth: String = "0", // -b，如 "200M"；"0"=不设（注意 UDP 建议明确带宽）
    val udpPacketLen: Int? = null,  // -l，UDP payload length（可选）

    // 高级
    val omitSec: Int = 0,           // -O 忽略起始秒（避免慢启动干扰）
    val mss: Int? = null,           // -M MSS
    val tos: Int? = null,           // -S TOS/DSCP (0-255)
    val bindAddress: String? = null,// -B 绑定本地地址（多网卡/热点场景很有用）
    val extraArgs: List<String> = emptyList() // 给你留“逃生舱”
)
