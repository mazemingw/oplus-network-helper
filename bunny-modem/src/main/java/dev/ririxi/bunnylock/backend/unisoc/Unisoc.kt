package dev.ririxi.bunnylock.backend.unisoc

import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.root.At
import dev.ririxi.bunnylock.root.fail
import dev.ririxi.bunnylock.root.rilNode
import dev.ririxi.bunnylock.root.toTextJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Same AT commands as Unisoc's EngineerMode, sent through the RIL passthrough:
 *  - AT+SPLBAND=0 / =1,<words> : LTE band mask
 *  - AT+SPLBAND=3 / =2,<words> : NR band mask
 *  - AT+SP5GCMDS="get nr support_band" : the NR bands the modem has
 * An all-zero mask lets the modem use every band it has.
 */
object Unisoc {
    private class BandMask(
        val readCommand: String,
        val writeCommand: String,
        val prefix: String,
        // The band behind each bit of each word, from bit 0.
        val words: List<List<Int>>,
    ) {
        fun settableBands(wordCount: Int): List<Int> = words.take(wordCount).flatten()

        fun bandsOf(values: List<Long>): List<Int> =
            values.flatMapIndexed { index, value ->
                words.getOrNull(index).orEmpty().filterIndexed { bit, _ -> value and (1L shl bit) != 0L }
            }

        fun valuesOf(
            bands: Collection<Int>,
            wordCount: Int,
        ): List<Long> =
            words.take(wordCount).map { word ->
                word.foldIndexed(0L) { bit, value, band -> if (band in bands) value or (1L shl bit) else value }
            }
    }

    // 16 bands a word, in the order 49, 33, 17, 1, then 65. Older modems leave out the last word.
    private val LTE =
        BandMask(
            readCommand = "AT+SPLBAND=0",
            writeCommand = "AT+SPLBAND=1",
            prefix = "B",
            words = listOf(49, 33, 17, 1, 65).map { first -> (first until first + 16).toList() },
        )

    // FDD, a word that is always 0, TDD, then on newer modems the rest.
    private val NR =
        BandMask(
            readCommand = "AT+SPLBAND=3",
            writeCommand = "AT+SPLBAND=2",
            prefix = "n",
            words =
                listOf(
                    listOf(1, 2, 3, 5, 7, 8, 12, 20, 25, 28, 66, 70, 71, 74),
                    emptyList(),
                    listOf(34, 38, 39, 40, 41, 50, 51, 77, 78, 79),
                    listOf(75, 76, 80, 81, 82, 83, 84, 86),
                ),
        )

    private const val NR_SUPPORT_QUERY = "AT+SP5GCMDS=\"get nr support_band\""

    private fun isErrorLine(line: String) = line == "ERROR" || line.startsWith("+CME ERROR")

    private fun readValues(
        node: String,
        mask: BandMask,
    ): List<Long> {
        val lines = At.send(node, mask.readCommand).lines()
        val line = lines.firstOrNull { it.startsWith("+SPLBAND:") }
        if (line === null && lines.any(::isErrorLine)) fail(R.string.at_not_accepted, mask.readCommand, lines.joinToString(" "))
        val values = line?.removePrefix("+SPLBAND:")?.split(',')?.map { it.trim().toLongOrNull() }
        if (values.isNullOrEmpty() || values.any { it === null }) fail(R.string.unisoc_reply_unreadable, mask.readCommand, lines.joinToString(" "))
        return values.filterNotNull()
    }

    // +SP5GCMDS: get nr support_band,<count>,<band>,...
    private fun nrSupport(node: String): List<Int> {
        val lines = At.send(node, NR_SUPPORT_QUERY).lines()
        val numbers = lines.firstOrNull { it.startsWith("+SP5GCMDS:") }?.substringAfter("support_band")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val count = numbers.firstOrNull() ?: fail(R.string.unisoc_reply_unreadable, NR_SUPPORT_QUERY, lines.joinToString(" "))
        return numbers.drop(1).take(count)
    }

    fun state(
        sim: Int,
        nr: Boolean,
    ): JSONObject {
        val node = rilNode(sim)
        val lte = readValues(node, LTE)
        val out =
            JSONObject()
                .put("lte", JSONArray(LTE.bandsOf(lte)))
                .put("lteSettable", JSONArray(LTE.settableBands(lte.size)))
        if (!nr) return out
        runCatching {
            val nrValues = readValues(node, NR)
            val supported = nrSupport(node)
            out.put("nr", JSONArray(NR.bandsOf(nrValues)))
            out.put("nrSupported", JSONArray(supported.filter { it in NR.settableBands(nrValues.size) }))
        }.onFailure { out.put("nrError", it.toTextJson()) }
        return out
    }

    fun setBands(
        sim: Int,
        lte: List<Int>?,
        nr: List<Int>?,
    ): JSONObject {
        val node = rilNode(sim)
        if (lte !== null) write(node, LTE, lte)
        if (nr !== null) {
            // A band the modem doesn't list leaves it searching until it hangs.
            val unlisted = if (nr.isEmpty()) emptyList() else nr - nrSupport(node).toSet()
            if (unlisted.isNotEmpty()) fail(R.string.unisoc_bands_unsettable, unlisted.map { NR.prefix + it })
            write(node, NR, nr)
        }
        return JSONObject()
    }

    // The modem takes as many words as it reports.
    private fun write(
        node: String,
        mask: BandMask,
        bands: List<Int>,
    ) {
        val wordCount = readValues(node, mask).size
        val unsettable = bands - mask.settableBands(wordCount).toSet()
        if (unsettable.isNotEmpty()) fail(R.string.unisoc_bands_unsettable, unsettable.map { mask.prefix + it })
        At.ok(node, mask.writeCommand + "," + mask.valuesOf(bands, wordCount).joinToString(","))
    }
}
