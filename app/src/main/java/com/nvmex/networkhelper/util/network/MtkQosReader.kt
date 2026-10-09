package com.nvmex.networkhelper.util.network

import android.os.Build
import com.nvmex.networkhelper.model.network.ModemQosInfo
import com.nvmex.networkhelper.util.shell.SuShellRunner
import java.io.File

/** Experimental MTK AT backend. No DIAG, radio reset, node cache or RIL process kills. */
internal class MtkQosReader(private val su: SuShellRunner, private val cacheDir: File) {
    fun isPlatform(): Boolean = (listOf(Build.HARDWARE, Build.BOARD) +
        if (Build.VERSION.SDK_INT >= 31) listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL) else emptyList())
        .any { it.contains("mediatek", true) || Regex("(?i)^mt[0-9]{4}").containsMatchIn(it) }

    fun read(subId: Int, slot: Int, source: ModemQosInfo.Source, singleSim: Boolean): List<ModemQosInfo> {
        require(slot in 0..1) { "MTK AT 暂仅支持卡槽 1 / 2" }
        val command = if (source == ModemQosInfo.Source.NR_5GSM) "AT+C5GQOSRDP=" else "AT+CGEQOSRDP="
        val script = """
            command -v timeout >/dev/null && command -v flock >/dev/null || exit 10
            exec 9>'${cacheDir.absolutePath}/mtk-qos.lock'
            flock -n 9 || exit 11
            nh_busy=${'$'}(find /proc/[0-9]*/fd -maxdepth 1 -type l -printf '%l\n' 2>/dev/null)
            nh_query() {
                printf '%s\r' "${'$'}1" >&3 || return 1
                nh_count=0
                while [ ${'$'}nh_count -lt 24 ]; do
                    IFS= read -r -t 2 nh_line <&3 || return 1
                    nh_line=${'$'}(printf '%s' "${'$'}nh_line" | tr -d '\r')
                    printf '%s\n' "${'$'}nh_line"
                    case "${'$'}nh_line" in OK) return 0;; ERROR|+CME\ ERROR*|+CMS\ ERROR*) return 1;; esac
                    nh_count=${'$'}((nh_count+1))
                done
                return 1
            }
            for nh_dev in /dev/ttyC0 /dev/ttyC1 /dev/ttyC3 /dev/ttyC5 /dev/ttyC6; do
                [ -c "${'$'}nh_dev" ] || continue
                printf '%s\n' "${'$'}nh_busy" | grep -Fx "${'$'}nh_dev" >/dev/null && continue
                exec 3<>"${'$'}nh_dev" || continue
                if ! nh_query AT >/dev/null; then exec 3>&-; continue; fi
                nh_initial=${'$'}(nh_query 'AT+ESUO?' | sed -n 's/^+ESUO: *\([45]\).*${'$'}/\1/p')
                case "${'$'}nh_initial" in
                    4|5) trap 'nh_query "AT+ESUO=${'$'}nh_initial" >/dev/null; exec 3>&-' EXIT
                         nh_query 'AT+ESUO=${4 + slot}' >/dev/null || exit 12;;
                    *) ${if (singleSim) ":" else "exit 13"};;
                esac
                nh_cops=${'$'}(nh_query 'AT+COPS?') || exit 14
                printf '%s\n' "${'$'}nh_cops"
                nh_rat=${'$'}(printf '%s\n' "${'$'}nh_cops" | sed -n 's/^+COPS:.*,[ ]*\([0-9][0-9]*\)[ ]*${'$'}/\1/p')
                case "${'$'}nh_rat" in ${if (source == ModemQosInfo.Source.NR_5GSM) "11|12" else "7|13"}) :;; *) exit 15;; esac
                nh_contexts=${'$'}(nh_query 'AT+CGDCONT?') || exit 16
                printf '%s\n' "${'$'}nh_contexts"
                nh_cids=${'$'}(printf '%s\n' "${'$'}nh_contexts" | sed -n 's/^+CGDCONT: *\([0-9][0-9]*\),.*${'$'}/\1/p')
                nh_total=0
                for nh_cid in ${'$'}nh_cids; do
                    [ ${'$'}nh_total -lt 16 ] || break
                    nh_query '${command}'"${'$'}nh_cid" || :
                    nh_total=${'$'}((nh_total+1))
                done
                exit 0
            done
            exit 17
        """.trimIndent()
        // Timeout is inside the root shell so a timed-out probe cannot keep the AT node open.
        val escaped = script.replace("'", "'\\''")
        val result = su.execSu("timeout -s TERM 45 /system/bin/sh -c '$escaped'", 50_000)
        check(result.code == 0) {
            when (result.code) {
                13 -> "MTK AT 不支持确认双卡栈归属，未将结果分配给任意 SIM"
                15 -> "AT 上报的网络与界面不一致，未使用其他网络的 QoS"
                17 -> "没有可用的空闲 MTK AT 节点；未抢占 RIL，设备可能未开放该接口"
                else -> "MTK AT 探测失败或超时（${result.code}），此兼容通道尚需 MTK 真机验证"
            }
        }
        return MtkQosParser.parse(result.out, subId, slot, source)
    }
}
