package com.nvmex.networkhelper.util.wifi

import kotlin.math.abs
import com.nvmex.networkhelper.model.wifi.WifiChannelSample

/** Wide-channel occupancy is centered on centerFreq0, not the primary 20 MHz channel. */
fun WifiChannelSample.occupiedChannelSegments(): List<Pair<Int, Int>> {
    val width = (channelWidthMhz ?: 20).coerceAtLeast(20)
    val first = centerFrequency0Mhz?.let(WifiNetUtils::frequencyToChannel) ?: channel
    val second = centerFrequency1Mhz?.let(WifiNetUtils::frequencyToChannel)
    return if (second != null && second != first && width == 160) listOf(first to 80, second to 80)
    else listOf(first to width)
}

/** Non-linear channel axis: long unoccupied runs occupy only 1.5 channel slots. */
data class WifiChannelAxis(val positions: Map<Int, Float>, val ticks: List<Int>, val omissions: List<Float>) {
    fun position(channel: Float): Float {
        val channels = positions.keys.toList()
        if (channel <= channels.first()) return positions.getValue(channels.first())
        if (channel >= channels.last()) return positions.getValue(channels.last())
        val upper = channels.indexOfFirst { it >= channel }
        val left = channels[upper - 1]
        val right = channels[upper]
        return positions.getValue(left) + (positions.getValue(right) - positions.getValue(left)) * (channel - left) / (right - left)
    }

    companion object {
        fun build(pool: List<Int>, signals: List<Pair<Int, Int>>): WifiChannelAxis {
            require(pool.size >= 2 && pool == pool.distinct().sorted())
            val occupied = pool.map { channel -> signals.any { (center, width) ->
                abs(channel - center) * 5 <= width.coerceAtLeast(20) / 2 + 20
            } }
            val compressedEdges = mutableSetOf<Int>()
            val hiddenTicks = mutableSetOf<Int>()
            val gaps = mutableListOf<IntRange>()
            if (signals.isNotEmpty()) {
                var index = 0
                while (index < pool.size) {
                    if (occupied[index]) { index++; continue }
                    val start = index
                    while (index < pool.size && !occupied[index]) index++
                    val end = index - 1
                    if (end - start >= 3) {
                        gaps += start..end
                        compressedEdges += (start + 1)..end
                        hiddenTicks += (start + 1) until end
                    }
                }
            }
            val raw = mutableListOf(0f)
            for (index in 1 until pool.size) {
                val gap = gaps.firstOrNull { index in it && index in compressedEdges }
                val increment = if (gap != null) 1.5f / (gap.last - gap.first) else 1f
                raw += raw.last() + increment
            }
            val total = raw.last().coerceAtLeast(1f)
            val visible = pool.indices.filter { it !in hiddenTicks }
            val ticks = if (visible.size <= 8) visible else (0..7).map { visible[it * visible.lastIndex / 7] }.distinct()
            return WifiChannelAxis(pool.indices.associate { pool[it] to raw[it] / total }, ticks.map { pool[it] },
                gaps.map { (raw[it.first] + raw[it.last]) / (2 * total) })
        }
    }
}
