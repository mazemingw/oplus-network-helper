package com.nvmex.networkhelper.hotspot.utils

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class HostapdWidthCalibrator(
    private val tag: String = "HotspotVM"
) {
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    private val latestWidthMhz = AtomicReference<Int?>(null)
    private val latestTs = AtomicLong(0L)

    /** 最近一次解析到的真实宽度（MHz），并带时间戳 */
    data class Snapshot(val widthMhz: Int?, val ts: Long)

    fun snapshot(): Snapshot = Snapshot(latestWidthMhz.get(), latestTs.get())

    fun start(scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return

        job = scope.launch(Dispatchers.IO) {
            // 只看 hostapd，其他全部静音；用 su 读 logcat
            // -v time: 带时间，方便调试；也可用 -v brief
            val cmd = listOf("su", "-c", "logcat -v time -s hostapd:I *:S")

            Log.i(tag, "HostapdWidthCalibrator start: ${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd)
            val p = runCatching { pb.start() }.getOrNull()
            if (p == null) {
                Log.w(tag, "HostapdWidthCalibrator: cannot start logcat process")
                running.set(false)
                return@launch
            }

            try {
                BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                    while (isActive && running.get()) {
                        val line = br.readLine() ?: break
                        val w = parseWidthMhzFromHostapd(line)
                        if (w != null) {
                            latestWidthMhz.set(w)
                            latestTs.set(System.currentTimeMillis())
                            // 只在抓到关键事件时打点，避免刷屏
                            Log.i(tag, "hostapd width calibrate: $w MHz | $line")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "HostapdWidthCalibrator read loop error", e)
            } finally {
                runCatching { p.destroy() }
                running.set(false)
                Log.i(tag, "HostapdWidthCalibrator stopped")
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
    }

    private fun parseWidthMhzFromHostapd(line: String): Int? {
        // 1) 你的日志里最稳定： "ch_width=320 MHz"
        Regex("""\bch_width=(\d+)\s*MHz\b""").find(line)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        // 2) 备选： "width=10 (320 MHz)"
        Regex("""\bwidth=\d+\s*\((\d+)\s*MHz\)\b""").find(line)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }
}
