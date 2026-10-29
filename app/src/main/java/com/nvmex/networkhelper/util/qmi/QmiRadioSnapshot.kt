/* SPDX-License-Identifier: GPL-3.0
 * NAS layouts adapted from Fronsipswu/qcom-band-menu, network/NasDecode.kt,
 * commit bff702b5b4916f68feb3bf9308b91be56846f2e6. See THIRD_PARTY_NOTICES.md.
 */
package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.*
import com.nvmex.networkhelper.util.network.LTE_GLOBAL_BANDS
import com.nvmex.networkhelper.util.network.NR_GLOBAL_BANDS
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class QmiCellSource { NEIGHBOR, HISTORY, CARRIER, SERVING }
data class QmiCellCandidate(val input: QmiCellInput, val source: QmiCellSource, val rsrp: Double? = null, val isCurrent: Boolean = false) {
    val key: String get() = "${input.mode}:${input.channel}:${input.pcis}"
}
data class QmiRadioSnapshot(
    val slot: Int, val updatedAt: Long,
    val neighbors: List<QmiCellCandidate> = emptyList(),
    val lteCa: LteCaInfo? = null, val nrServingChannels: List<Int> = emptyList(),
    val nrPrimaryChannel: Int? = null, val nrPrimaryPci: Int? = null,
    val locks: QmiLiveLocks? = null
)

internal fun qmiLteBand(channel: Int): Int? = LTE_GLOBAL_BANDS.firstOrNull { channel in it.earfcnStart..it.earfcnEnd }?.band
internal fun qmiNrBand(channel: Int): Int? {
    val mhz = when (channel) {
        in 0..599999 -> channel * 0.005
        in 600000..2016666 -> 3000 + (channel - 600000) * 0.015
        in 2016667..3279165 -> 24250.08 + (channel - 2016667) * 0.06
        else -> return null
    }
    return NR_GLOBAL_BANDS.filter { it.duplex != "SUL" && mhz in it.dlLowMhz..it.dlHighMhz }
        .map { it.band }.distinct().singleOrNull()
}

/** GET formats follow qcom-band-menu's NasDecode (GPL-3.0), with bounded independent decoding. */
object QmiRadioDecoder {
    val reads = listOf("0043", "00ac", "00d2", "0034", "00d7", "010f")
    private fun ByteArray.reader() = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.u8() = get().toInt() and 255
    private fun ByteBuffer.u16() = short.toInt() and 65535
    private fun ByteBuffer.channel(max: Int): Int = (int.toLong() and 0xffffffffL).also { require(it <= max) }.toInt()
    private fun ByteBuffer.skip(n: Int) { require(n <= remaining()); position(position() + n) }

    fun decode(slot: Int, now: Long, replies: Map<Int, String>): QmiRadioSnapshot {
        require(slot in 0..1)
        val neighbors = mutableListOf<QmiCellCandidate>()
        var nrPrimaryChannel: Int? = null
        var nrPrimaryPci: Int? = null
        replies[0x43]?.let { hex -> runCatching {
            val tlvs = responseTlvs(hex, 0x43)
            nrPrimaryChannel = tlvs[0x2e]?.let { runCatching { it.reader().channel(3279165).takeIf { ch -> ch > 0 } }.getOrNull() }
            nrPrimaryPci = tlvs[0x2f]?.let { runCatching { it.reader().let { r -> r.skip(14); r.u16().takeIf { pci -> pci in 0..1007 } } }.getOrNull() }
            fun measurements(r: ByteBuffer, count: Int, channel: Int, servingPci: Int? = null): List<QmiCellCandidate> = buildList {
                require(count <= 64)
                repeat(count) {
                    val pci = r.u16(); r.short
                    val rsrp = r.short / 10.0; r.skip(4)
                    if (pci in 0..503 && pci != servingPci && channel in 0..262143)
                        add(QmiCellCandidate(QmiCellInput(QmiCellMode.LTE, channel.toString(), pci.toString(),
                            qmiLteBand(channel)?.toString().orEmpty()), QmiCellSource.NEIGHBOR,
                            rsrp.takeIf { it in -160.0..-20.0 }))
                }
            }
            tlvs[0x13]?.let { bytes -> runCatching {
                val r = bytes.reader(); r.skip(10)
                val legacyChannel = r.u16(); val pci = r.u16(); r.skip(4); val count = r.u8()
                val channel = tlvs[0x27]?.reader()?.channel(262143) ?: legacyChannel
                val decoded = measurements(r, count, channel, pci)
                require(!r.hasRemaining())
                neighbors += decoded
            } }
            tlvs[0x14]?.let { bytes -> runCatching {
                val r = bytes.reader(); r.u8(); val count = r.u8(); require(count <= 64)
                val ext = tlvs[0x28]?.reader()?.also { require(it.u8() == count) }
                val decoded = buildList { repeat(count) {
                    val legacyChannel = r.u16(); r.skip(3); val cells = r.u8()
                    addAll(measurements(r, cells, ext?.channel(262143) ?: legacyChannel))
                } }
                require(!r.hasRemaining() && (ext == null || !ext.hasRemaining()))
                neighbors += decoded
            } }
        } }
        var nrServing = emptyList<Int>()
        replies[0xd2]?.let { hex -> runCatching {
            val tlvs = responseTlvs(hex, 0xd2)
            fun channels(tag: Int): List<Int> = tlvs[tag]?.reader()?.let { r ->
                val count = r.u8(); require(count <= 64)
                List(count) { r.channel(3279165) }.also { require(!r.hasRemaining()) }
                    .filter { it > 0 }.distinct()
            }.orEmpty()
            nrServing = channels(0x1c)
            // QMI supplies no neighbor PCI. Preserve this as a frequency-only target.
            channels(0x1d).filterNot { it in nrServing }.forEach { channel ->
                neighbors += QmiCellCandidate(QmiCellInput(QmiCellMode.NR_ARFCN, channel.toString(),
                    band = qmiNrBand(channel)?.toString().orEmpty()), QmiCellSource.NEIGHBOR)
            }
        } }
        val lteCa = replies[0xac]?.let { hex -> runCatching {
            val tlvs = responseTlvs(hex, 0xac)
            fun carrier(r: ByteBuffer, primary: Boolean): LteCaCarrier? {
                val pci = r.u16()
                val legacyChannel = if (primary) r.u16().toLong() else r.int.toLong() and 0xffffffffL
                val channelRaw = if (primary) tlvs[0x16]?.reader()?.channel(262143)?.toLong() ?: legacyChannel else legacyChannel
                val bw = r.int; val bandEnum = r.u16()
                val state = if (primary) null else r.int
                val idx = if (primary) 0 else r.u8() + 1
                val ul = if (primary) null else r.u8().takeIf { it in 0..1 }
                // Deconfigured records commonly contain sentinel PCI/channel values.
                if (!primary && state !in 1..2) return null
                require(pci in 0..503 && channelRaw in 0..262143)
                val channel = channelRaw.toInt()
                val band = (bandEnum - 119).takeIf { it in 1..256 } ?: qmiLteBand(channel)
                val width = listOf("1.4", "3", "5", "10", "15", "20").getOrNull(bw)?.plus(" MHz")
                return LteCaCarrier(idx, pci, channel, -1, band, band?.let { "B$it" }, bw.takeIf { width != null },
                    width, state, when(state) { 0 -> "Deconfigured"; 1 -> "Configured"; 2 -> "Activated"; else -> null },
                    ul, null, null, null)
            }
            val carriers = buildList {
                tlvs[0x13]?.let { carrier(it.reader(), true)?.let(::add) }
                tlvs[0x19]?.let { bytes ->
                    val r = bytes.reader(); val count = r.u8(); require(count <= 32)
                    repeat(count) { carrier(r, false)?.let(::add) }; require(!r.hasRemaining())
                }
            }.filter { it.scellState == null || it.scellState in 1..2 }.distinctBy { it.dlEarfcn to it.pci }
            carriers.takeIf { it.isNotEmpty() }?.let { LteCaInfo(slot, it, now) }
        }.getOrNull() }
        return QmiRadioSnapshot(slot, now, neighbors.distinctBy { it.key }.sortedByDescending { it.rsrp ?: -999.0 },
            lteCa, nrServing, nrPrimaryChannel, nrPrimaryPci, qmiDecodeLiveLocks(replies))
    }
}

fun qmiCellCandidates(neighbors: List<QmiCellCandidate>, history: List<CellTimelinePoint>, state: NetworkPanelUiState?,
                      slot: Int? = null, now: Long = System.currentTimeMillis()): List<QmiCellCandidate> {
    fun candidate(type: String, channel: String, pci: String, band: String, source: QmiCellSource): QmiCellCandidate? {
        val nr = type.equals("NR", true)
        if (!nr && !type.equals("LTE", true)) return null
        val ch = channel.toIntOrNull()?.takeIf { it in if (nr) 1..3279165 else 0..262143 } ?: return null
        val id = pci.toIntOrNull()?.takeIf { it in if (nr) 0..1007 else 0..503 } ?: return null
        val bandNumber = band.trim().removePrefix("n").removePrefix("N").removePrefix("B").toIntOrNull()?.takeIf { it in 1..512 }
        // NR PCI locks require an actual band. Do not guess where channel ranges overlap.
        if (nr && bandNumber == null) return null
        return QmiCellCandidate(qmiCellWithDefaultScs(QmiCellInput(if (nr) QmiCellMode.NR_PCI else QmiCellMode.LTE, ch.toString(), id.toString(),
            bandNumber?.toString().orEmpty())), source)
    }
    val operatorKnown = state?.mcc?.matches(Regex("[0-9]{3}")) == true && state.mnc.matches(Regex("[0-9]{2,3}"))
    val servingCells = qmiServingCells(state, now)
    val pciNeighbors = neighbors.mapNotNull { neighbor ->
        val type = when (neighbor.input.mode) { QmiCellMode.LTE -> "LTE"; QmiCellMode.NR_PCI, QmiCellMode.NR_ARFCN -> "NR"; else -> return@mapNotNull null }
        candidate(type, neighbor.input.channel, neighbor.input.pcis, neighbor.input.band, QmiCellSource.NEIGHBOR)
            ?.copy(rsrp = neighbor.rsrp)
    }
    val caCells = buildList {
        // NRCA dlArfcn is a carrier frequency, not a confirmed cell-lock SSB frequency.
        // Keep NRCA for display only. LTE CA EARFCN + PCI can still prefill a cell lock.
        state?.lteCaInfo?.takeIf { (slot == null || it.slot == slot) && now - it.updatedAt in 0..12000 }?.carriers
            ?.filter { it.idx == 0 || it.scellState in 1..2 }?.forEach { carrier ->
                candidate("LTE", carrier.dlEarfcn.toString(), carrier.pci.toString(), carrier.band?.toString().orEmpty(), QmiCellSource.CARRIER)?.let(::add)
            }
    }
    return (pciNeighbors + history.filter { !operatorKnown ||
        ((it.mcc.isBlank() || state?.mcc == it.mcc) && (it.mnc.isBlank() || state?.mnc == it.mnc)) }
        .sortedByDescending { it.ts }.mapNotNull {
        candidate(it.cellType, it.arfcn, it.pci, it.band, QmiCellSource.HISTORY)?.copy(rsrp = qmiRsrp(it.rsrp))
    } + caCells + listOfNotNull(state, state?.nsaLteAnchor).mapNotNull {
        candidate(it.cellType, it.arfcn, it.pci, it.band, QmiCellSource.SERVING)
            ?.copy(rsrp = if (it.cellType.equals("NR", true)) qmiRsrp(it.ssRsrp) ?: qmiRsrp(it.rsrp) else qmiRsrp(it.rsrp))
    }).distinctBy { it.key }.map { candidate ->
        val serving = servingCells.firstOrNull { cell ->
            candidate.input.mode == (if (cell.rat == "NR") QmiCellMode.NR_PCI else QmiCellMode.LTE) &&
                candidate.input.channel.toIntOrNull() == cell.channel && candidate.input.pcis.toIntOrNull() == cell.pci
        }
        candidate.copy(isCurrent = serving != null, rsrp = serving?.rsrp ?: candidate.rsrp)
    }
}

fun qmiChooseNrCa(engineering: NrCaInfo?, fallback: NrCaInfo?, slot: Int?, now: Long): NrCaInfo? =
    listOfNotNull(engineering, fallback).firstOrNull { it.slot == slot && now - it.updatedAt in 0..12000 && it.carriers.isNotEmpty() }

fun qmiLteCaForState(snapshot: QmiRadioSnapshot?, state: NetworkPanelUiState, slot: Int?, now: Long): LteCaInfo? {
    if (snapshot == null || snapshot.slot != slot || now - snapshot.updatedAt !in 0..12000) return null
    val serving = if (state.cellType == "LTE") state else state.nsaLteAnchor
    if (serving == null && !state.nrMode.startsWith("NSA", true)) return null
    val ca = snapshot.lteCa?.takeIf { it.slot == slot } ?: return null
    val primary = ca.carriers.firstOrNull { it.idx == 0 }
    if (serving != null && primary != null &&
        (serving.arfcn.toIntOrNull()?.let { it != primary.dlEarfcn } == true ||
         serving.pci.toIntOrNull()?.let { it != primary.pci } == true)) return null
    return ca
}

fun qmiNrFrequencyFallback(snapshot: QmiRadioSnapshot?, state: NetworkPanelUiState, now: Long): NrCaInfo? {
    if (snapshot == null || now - snapshot.updatedAt !in 0..12000 || state.dataNetworkType != "NR") return null
    // A matching QMI serving PCI lets us use its own primary-channel vocabulary
    // when Android exposes an SSB channel rather than the carrier center.
    val primary = snapshot.nrPrimaryChannel?.takeIf { snapshot.nrPrimaryPci != null && snapshot.nrPrimaryPci == state.pci.toIntOrNull() }
        ?: state.arfcn.toIntOrNull() ?: return null
    if (primary !in snapshot.nrServingChannels) return null // Reject another cell's cached response.
    val secondary = snapshot.nrServingChannels.filter { it != primary }.distinct()
    if (secondary.isEmpty()) return null
    return NrCaInfo(snapshot.slot, -1, (listOf(primary) + secondary).mapIndexed { index, channel ->
        val bands = qmiNrBandsForChannel(channel, state.mcc, state.mnc)
        val primaryBand = state.band.removePrefix("N").removePrefix("n").toIntOrNull()?.takeIf { it in 1..512 }
        NrCaCarrier(index, index - 1, if (index == 0) state.pci.toIntOrNull()?.takeIf { it in 0..1007 } ?: -1 else -1,
            -1, if (index == 0 && primaryBand != null) "N$primaryBand" else bands.joinToString("/") { "N$it" }.ifEmpty { "N?" }, channel,
            -1, "—", -1, "—", -1, "—", -1, "—")
    }, snapshot.updatedAt, frequencyOnly = true)
}
