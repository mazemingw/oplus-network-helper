package dev.ririxi.bunnylock.root

import androidx.annotation.StringRes
import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.modem.Masks
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import org.json.JSONArray
import org.json.JSONObject

/**
 * Same AT commands as MediaTek's EngineerMode:
 *  - AT+EPBSE  : GSM / UMTS / LTE band masks (decimal 32-bit words)
 *  - AT+EPBSEH : NR band mask (hex words), plus GSM/UMTS/LTE echoed in hex
 *  - AT+EMMCHLCK=<on>,<act>,<gsm1900>,<arfcn>[,<pci>[,<mode>]] : channel / cell lock
 *    act: 0 GSM, 2 UMTS, 7 LTE, 11 NR
 *    mode: 0 idle only, 3 idle + connected
 *  - AT+ERAT=<rat>,<prefer> : network mode
 */
object Mediatek {
    val CANDIDATES =
        listOf(
            "/dev/radio/pttycmd1",
            "/dev/radio/atci1",
            "/dev/radio/pttyeng",
            "/dev/radio/atci-serv-fw",
            "/dev/ttyC0",
            "/dev/radio/ptty2cmd1",
            "/dev/radio/atci2",
        )

    // +EPBSE words: GSM, UMTS, then eight LTE words (bands 1-32, 33-64 and so on).
    private const val GSM_WORD = 0
    private const val UMTS_WORD = 1
    private const val FIRST_LTE_WORD = 2
    private const val LTE_WORD_COUNT = 8
    private const val EPBSE_WORD_COUNT = FIRST_LTE_WORD + LTE_WORD_COUNT

    // The short form of +EPBSE stops after the first two LTE words.
    private const val EPBSE_SHORT_FIELD_COUNT = 4

    // +EPBSEH fields: GSM, UMTS, LTE, NR. Only the first three LTE words are sent in it.
    private const val EPBSEH_LTE_FIELD = 2
    private const val EPBSEH_NR_FIELD = 3
    private const val EPBSEH_LTE_WORD_COUNT = 3
    private const val HEX_DIGITS_PER_WORD = 8

    private const val CELL_LOCK_READBACK_RETRY_MS = 1000L

    /** Bit of each GSM band, by its MHz name, in the GSM word. */
    private val GSM_BITS = mapOf(900 to 1, 1800 to 3, 1900 to 4, 850 to 7)

    private val GENERATIONS = Masks.G2 or Masks.G3 or Masks.G4 or Masks.G5

    // The modes with C2K are only read, never set.
    private val ERAT_MASKS =
        mapOf(
            0 to Masks.G2,
            1 to Masks.G3,
            2 to (Masks.G2 or Masks.G3),
            3 to Masks.G4,
            4 to (Masks.G2 or Masks.G4),
            5 to (Masks.G3 or Masks.G4),
            6 to (Masks.G2 or Masks.G3 or Masks.G4),
            15 to Masks.G5,
            19 to (Masks.G4 or Masks.G5),
            21 to (Masks.G3 or Masks.G4 or Masks.G5),
            22 to (Masks.G2 or Masks.G3 or Masks.G4 or Masks.G5),
            7 to Masks.CDMA,
            8 to (Masks.G2 or Masks.CDMA),
            10 to (Masks.G2 or Masks.G3 or Masks.CDMA),
            11 to (Masks.G4 or Masks.CDMA),
            12 to (Masks.G2 or Masks.G4 or Masks.CDMA),
            14 to (Masks.G2 or Masks.G3 or Masks.G4 or Masks.CDMA),
            26 to (Masks.G4 or Masks.G5 or Masks.CDMA),
            30 to (Masks.G2 or Masks.G3 or Masks.G4 or Masks.G5 or Masks.CDMA),
        )

    // The <prefer> values MediaTek's RIL sends.
    private const val ERAT_PREFER_NONE = 0
    private const val ERAT_PREFER_3G = 2
    private const val ERAT_PREFER_LTE = 4
    private const val ERAT_PREFER_NR = 128

    fun probe(node: String): Boolean = runCatching { At.send(node, "AT", 1500).lines().any { it == "OK" } }.getOrDefault(false)

    /**
     * A modem tty when it answers (root), otherwise the RIL passthrough for [sim] (works through Shizuku too).
     * The passthrough was tested when it was found, so it isn't asked again here.
     */
    fun findNode(sim: Int = 1): String? =
        CANDIDATES.firstOrNull { At.exists(it) && probe(it) }
            ?: "${OemHook.PREFIX}$sim".takeIf { At.exists(it) }

    private class Epbse(
        val words: LongArray,
        val fieldCount: Int,
        /** Read from +EPBSEH because the modem doesn't answer +EPBSE. */
        val fromEpbseh: Boolean = false,
    ) {
        val hasExtendedLte get() = fieldCount > 5
    }

    private fun parseEpbse(reply: String): Epbse? {
        val line = reply.lines().firstOrNull { it.startsWith("+EPBSE:") } ?: return null
        val fields = line.removePrefix("+EPBSE:").split(',').map { it.trim() }
        val words = LongArray(EPBSE_WORD_COUNT) { index -> fields.getOrNull(index)?.toLongOrNull() ?: 0L }
        return Epbse(words, fields.size)
    }

    /** Each field is a run of 8-digit hex words. A shorter last word is padded with zeros on the right. */
    private fun parseEpbseh(reply: String): List<LongArray>? {
        val line = reply.lines().firstOrNull { it.startsWith("+EPBSEH:") } ?: return null
        return line.removePrefix("+EPBSEH:").split(',').map { field ->
            val hex = field.trim().trim('"')
            hex.chunked(HEX_DIGITS_PER_WORD).map { it.padEnd(HEX_DIGITS_PER_WORD, '0').toLong(16) }.toLongArray()
        }
    }

    private fun queryEpbseh(
        node: String,
        command: String,
    ): List<LongArray>? = runCatching { parseEpbseh(At.send(node, command)) }.getOrNull()

    private fun nrWordsOf(epbseh: List<LongArray>?): LongArray? = epbseh?.getOrNull(EPBSEH_NR_FIELD)?.takeIf { it.isNotEmpty() }

    /** The GSM, UMTS and LTE fields of a +EPBSEH reply, for a modem that doesn't answer +EPBSE. */
    private fun epbseOf(epbseh: List<LongArray>): Epbse? {
        if (epbseh.size <= EPBSEH_LTE_FIELD) return null
        val words = LongArray(EPBSE_WORD_COUNT)
        words[GSM_WORD] = epbseh[GSM_WORD].firstOrNull() ?: 0L
        words[UMTS_WORD] = epbseh[UMTS_WORD].firstOrNull() ?: 0L
        val lteWords = epbseh[EPBSEH_LTE_FIELD]
        lteWords.copyInto(words, destinationOffset = FIRST_LTE_WORD, endIndex = minOf(lteWords.size, LTE_WORD_COUNT))
        return Epbse(words, fieldCount = FIRST_LTE_WORD + lteWords.size, fromEpbseh = true)
    }

    private fun gsmOf(word: Long) = GSM_BITS.filter { (_, bit) -> word and (1L shl bit) != 0L }.keys.sorted()

    private fun umtsOf(word: Long) = (0 until 32).filter { word and (1L shl it) != 0L }.map { it + 1 }

    private fun lteOf(words: LongArray) = Bands.fromWords(words.copyOfRange(FIRST_LTE_WORD, EPBSE_WORD_COUNT))

    private fun wordOf(bits: List<Int>): Long = bits.fold(0L) { word, bit -> word or (1L shl bit) }

    private fun gsmWord(bands: List<Int>): Long = wordOf(bands.mapNotNull { GSM_BITS[it] })

    private fun umtsWord(bands: List<Int>): Long = wordOf(bands.map { it - 1 })

    private fun bandsJson(words: LongArray): JSONObject =
        JSONObject()
            .put("gsm", JSONArray(gsmOf(words[GSM_WORD])))
            .put("wcdma", JSONArray(umtsOf(words[UMTS_WORD])))
            .put("lte", JSONArray(lteOf(words)))

    fun state(node: String): JSONObject {
        val out = JSONObject().put("node", node)
        val supportedHex = queryEpbseh(node, "AT+EPBSEH=?")
        val currentHex = queryEpbseh(node, "AT+EPBSEH?")
        // Some modems answer +EPBSEH but not +EPBSE, and it carries the same bands.
        val supported = parseEpbse(At.send(node, "AT+EPBSE=?")) ?: supportedHex?.let(::epbseOf)
        val current = parseEpbse(At.send(node, "AT+EPBSE?")) ?: currentHex?.let(::epbseOf)
        if (supported !== null) {
            out.put("supported", bandsJson(supported.words))
            out.put("lteExtended", supported.hasExtendedLte)
        }
        if (current !== null) out.put("current", bandsJson(current.words))
        nrWordsOf(supportedHex)?.let { nrWords ->
            out.optJSONObject("supported")?.put("nr", JSONArray(Bands.fromWords(nrWords)))
            out.put("nrWords", nrWords.size)
        }
        nrWordsOf(currentHex)?.let { out.optJSONObject("current")?.put("nr", JSONArray(Bands.fromWords(it))) }
        runCatching { out.put("cellLock", cellLockState(node)) }
        runCatching { ratMask(node)?.let { out.put("ratMask", it) } }
        return out
    }

    private fun ratMask(node: String): Long? {
        // +ERAT: <current rat>,<gprs status>,<rat>,<prefer>
        val line = At.send(node, "AT+ERAT?").lines().firstOrNull { it.startsWith("+ERAT:") } ?: return null
        val rat = line.removePrefix("+ERAT:").split(',').getOrNull(2)?.trim()?.toIntOrNull() ?: return null
        return ERAT_MASKS[rat]
    }

    private fun preferenceFor(generations: Long): Int =
        when {
            generations == Masks.G5 -> ERAT_PREFER_NONE
            generations and Masks.G5 != 0L -> ERAT_PREFER_NR
            generations == Masks.G2 or Masks.G3 or Masks.G4 -> ERAT_PREFER_LTE
            generations == Masks.G2 or Masks.G3 -> ERAT_PREFER_3G
            else -> ERAT_PREFER_NONE
        }

    /** A modem that already allows the same generations is left alone. */
    fun setRat(
        node: String,
        mask: Long,
    ): JSONObject {
        val wanted = mask and GENERATIONS
        val current = ratMask(node) ?: fail(R.string.mtk_no_network_mode)
        if (current and GENERATIONS != wanted) {
            val rat = ERAT_MASKS.entries.firstOrNull { it.value == wanted }?.key ?: fail(R.string.mtk_erat_no_mode)
            At.ok(node, "AT+ERAT=$rat,${preferenceFor(wanted)}")
        }
        val out = JSONObject()
        ratMask(node)?.let { out.put("ratMask", it) }
        return out
    }

    private fun cellLockState(node: String): JSONObject {
        val reply = At.send(node, "AT+EMMCHLCK?").lines()
        // A modem that answers with an error doesn't take the command at all, so no cell lock can be set through it.
        val refused = reply.any { it == "ERROR" || it.startsWith("+CME ERROR") }
        val line = reply.firstOrNull { it.startsWith("+EMMCHLCK:") } ?: return JSONObject().put("active", false).put("refused", refused)
        // +EMMCHLCK: <on>,<act>,<gsm1900>,<arfcn>,<pci>[,<mode>]
        val fields = line.removePrefix("+EMMCHLCK:").split(',').map { it.trim().toIntOrNull() ?: -1 }
        val lock = JSONObject().put("active", fields.getOrNull(0) == 1)
        if (fields.size >= 5) lock.put("act", fields[1]).put("arfcn", fields[3]).put("pci", fields[4])
        if (fields.size >= 6) lock.put("mode", fields[5])
        return lock
    }

    /** [epbseh] is the +EPBSEH reply to the same question, used when the modem doesn't answer +EPBSE. */
    private fun readEpbse(
        node: String,
        command: String,
        @StringRes problem: Int,
        epbseh: List<LongArray>?,
    ): Epbse {
        val reply = At.send(node, command)
        return parseEpbse(reply)
            ?: epbseh?.let(::epbseOf)
            ?: fail(R.string.mtk_epbse_unreadable, Text(problem, node), command, reply.lines().joinToString(" "))
    }

    /** Any argument left null keeps the modem's current value. An empty list means "all supported". */
    fun setBands(
        node: String,
        gsm: List<Int>?,
        wcdma: List<Int>?,
        lte: List<Int>?,
        nr: List<Int>?,
    ): JSONObject {
        val supportedHex = queryEpbseh(node, "AT+EPBSEH=?")
        val currentHex = queryEpbseh(node, "AT+EPBSEH?")
        val supported = readEpbse(node, "AT+EPBSE=?", R.string.mtk_no_band_lock, supportedHex)
        val current = readEpbse(node, "AT+EPBSE?", R.string.mtk_current_bands_unreadable, currentHex)
        val words = current.words.copyOf()
        if (gsm !== null) words[GSM_WORD] = if (gsm.isEmpty()) supported.words[GSM_WORD] else gsmWord(gsm)
        if (wcdma !== null) words[UMTS_WORD] = if (wcdma.isEmpty()) supported.words[UMTS_WORD] else umtsWord(wcdma)
        if (lte !== null) {
            val lteWords =
                if (lte.isEmpty()) {
                    supported.words.copyOfRange(FIRST_LTE_WORD, EPBSE_WORD_COUNT)
                } else {
                    Bands.toWords(lte, LTE_WORD_COUNT)
                }
            lteWords.copyInto(words, destinationOffset = FIRST_LTE_WORD)
        }
        // A modem without +EPBSE takes every technology in one +EPBSEH, so the NR bands go along even when they don't change.
        val epbsehOnly = supported.fromEpbseh || current.fromEpbseh
        if (!epbsehOnly && (gsm !== null || wcdma !== null || lte !== null)) At.ok(node, epbseCommand(words, supported.hasExtendedLte))
        if (nr !== null || epbsehOnly) {
            val supportedNr = nrWordsOf(supportedHex) ?: fail(R.string.mtk_no_epbseh)
            val nrWords =
                when {
                    // The modem leaves trailing zero words out of its reply, so they are put back.
                    nr === null -> nrWordsOf(currentHex)?.copyOf(supportedNr.size) ?: supportedNr
                    nr.isEmpty() -> supportedNr
                    else -> Bands.toWords(nr, supportedNr.size)
                }
            At.ok(node, epbsehCommand(words, nrWords))
        }
        return state(node)
    }

    private fun epbseCommand(
        words: LongArray,
        extendedLte: Boolean,
    ): String {
        val fieldCount = if (extendedLte) EPBSE_WORD_COUNT else EPBSE_SHORT_FIELD_COUNT
        return "AT+EPBSE=" + (0 until fieldCount).joinToString(",") { words[it].toString() }
    }

    /** +EPBSEH sets every technology at once, so the GSM, UMTS and LTE words go in next to the NR ones. */
    private fun epbsehCommand(
        words: LongArray,
        nrWords: LongArray,
    ): String {
        val gsm = hex32(words[GSM_WORD])
        val umts = hex32(words[UMTS_WORD])
        val lte = (FIRST_LTE_WORD until FIRST_LTE_WORD + EPBSEH_LTE_WORD_COUNT).joinToString("") { hex32(words[it]) }
        val nr = nrWords.joinToString("") { hex32(it) }
        return "AT+EPBSEH=\"$gsm\",\"$umts\",\"$lte\",\"$nr\""
    }

    private fun hex32(word: Long): String = "%08x".format(word and 0xFFFFFFFFL)

    /** Without a [pci] this locks the channel only, and [mode] isn't sent. */
    fun cellLock(
        node: String,
        act: Int,
        arfcn: Long,
        pci: Int?,
        mode: Int,
    ): JSONObject {
        // <on> = 1, <gsm1900> = 0
        val channelLock = "AT+EMMCHLCK=1,$act,0,$arfcn"
        val command = if (pci === null) channelLock else "$channelLock,$pci,$mode"
        At.ok(node, command)
        val lock = cellLockState(node)
        if (lock.optBoolean("active")) return lock
        Thread.sleep(CELL_LOCK_READBACK_RETRY_MS)
        return cellLockState(node)
    }

    fun cellUnlock(node: String): JSONObject {
        At.ok(node, "AT+EMMCHLCK=0")
        return cellLockState(node)
    }
}
