package dev.qcom.efs

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class EfsEntry(
    val name: String,
    val type: String,
    val mode: Int,
    val size: Int,
    val atime: Int,
    val mtime: Int,
    val ctime: Int,
    val entryType: Int,
) {
    val isDir: Boolean get() = type == "dir"
    val isLink: Boolean get() = type == "link"
    val isItem: Boolean get() = type == "item"

    companion object {
        fun from(o: JSONObject) = EfsEntry(
            name = o.optString("name"),
            type = o.optString("type", "unknown"),
            mode = o.optInt("mode"),
            size = o.optInt("size"),
            atime = o.optInt("atime"),
            mtime = o.optInt("mtime"),
            ctime = o.optInt("ctime"),
            entryType = o.optInt("entry_type"),
        )
    }
}

/** Sort keys offered by the browser's sort menu. */
enum class SortKey { NAME, SIZE, DATE }

/** Useful default direction when a key is first picked: Size/Date descending, Name ascending. */
val SortKey.defaultDescending: Boolean get() = this != SortKey.NAME

data class EfsStat(
    val path: String,
    val type: String,
    val mode: Int,
    val size: Int,
    val nlink: Int,
    val atime: Int,
    val mtime: Int,
    val ctime: Int,
    val target: String?,
) {
    companion object {
        fun from(o: JSONObject) = EfsStat(
            path = o.optString("path"),
            type = o.optString("type", "unknown"),
            mode = o.optInt("mode"),
            size = o.optInt("size"),
            nlink = o.optInt("nlink"),
            atime = o.optInt("atime"),
            mtime = o.optInt("mtime"),
            ctime = o.optInt("ctime"),
            target = if (o.has("target")) o.optString("target") else null,
        )
    }
}

/**
 * Only used until the helper has answered: it reports its own limit, so the
 * number lives in the helper and this is the stand-in for the moment before
 * the first reply.
 */
const val DEFAULT_MAX_INLINE = 512 * 1024

data class DaemonInfo(
    val version: String,
    val subsys: Int,
    /** How the helper reached the modem, e.g. "qrtr 0:25". */
    val transport: String,
    val readOnly: Boolean,
    /** Biggest file the helper will hand over inline, as the helper reports it. */
    val maxInline: Int = DEFAULT_MAX_INLINE,
)

data class NvResult(val item: Int, val status: Int, val statusText: String, val hex: String)

data class PullSummary(
    val dirs: Int,
    val files: Int,
    val links: Int,
    val bytes: Long,
    val errors: Int,
    val errorList: List<String>,
)

object Paths {
    fun parent(path: String): String {
        if (path == "/" || path.isEmpty()) return "/"
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx <= 0) "/" else trimmed.substring(0, idx)
    }

    fun child(dir: String, name: String): String =
        if (dir == "/") "/$name" else "${dir.trimEnd('/')}/$name"

    /** Mirrors efs_is_item_path() in the helper: where item files normally live. */
    fun looksLikeItemPath(path: String): Boolean =
        path.startsWith("/nv/item_files/") ||
        path.startsWith("/nv/reg_files/") ||
        path.startsWith("/cgps/nv/item_files/") ||
        path.startsWith("/sd/")

    fun crumbs(path: String): List<Pair<String, String>> {
        val out = mutableListOf("/" to "/")
        var acc = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            acc = "$acc/$part"
            out += part to acc
        }
        return out
    }
}

fun modeOctal(mode: Int): String = "0" + (mode and 0xFFF).toString(8).padStart(4, '0')

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KiB", bytes / 1024.0)
    else -> String.format("%.1f MiB", bytes / (1024.0 * 1024))
}

// ---- filtering and sorting, for the browser list ------------------------

/**
 * Case-insensitive substring match on the entry name.  A blank query matches
 * everything, so the caller never special-cases it.
 */
fun List<EfsEntry>.filterByName(query: String): List<EfsEntry> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { it.name.contains(q, ignoreCase = true) }
}

/**
 * Dirs first, always; within each group by [key].  [descending] flips the key
 * order but never the dirs-first grouping.  Every ordering ends with name
 * tiebreaks (case-insensitive, then case-sensitive, the latter running
 * opposite to the sort direction), so the result is fully deterministic.
 */
fun List<EfsEntry>.sortedBy(key: SortKey, descending: Boolean): List<EfsEntry> {
    val byName = compareBy<EfsEntry> { it.name.lowercase() }
    val byKey: Comparator<EfsEntry> = when (key) {
        SortKey.NAME -> byName
        SortKey.SIZE -> compareBy { it.size }
        SortKey.DATE -> compareBy { it.mtime }
    }
    val primary = if (descending) byKey.reversed() else byKey
    val byRawName = if (descending) compareBy<EfsEntry> { it.name } else compareByDescending<EfsEntry> { it.name }
    return sortedWith(
        compareByDescending<EfsEntry> { it.isDir }.then(primary).then(byName).then(byRawName),
    )
}

// ---- text and hex, for previewing and editing ---------------------------

/** Mostly printable, so a text view of it is worth offering. */
fun looksLikeText(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    val printable = bytes.count { val c = it.toInt() and 0xFF; c in 32..126 || c == 9 || c == 10 || c == 13 }
    return printable > bytes.size * 8 / 10
}

/**
 * UTF-8 when the bytes really decode as UTF-8, latin-1 otherwise.
 *
 * Latin-1 maps every one of the 256 byte values to a character and back, so a
 * file that is not text at all still survives being opened and saved untouched;
 * UTF-8 would replace whatever it could not decode.
 */
fun textCharset(bytes: ByteArray): Charset = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
    Charsets.UTF_8
} catch (_: CharacterCodingException) {
    Charsets.ISO_8859_1
}

/** Bytes as editable hex: lowercase pairs, sixteen to a line, nothing else. */
fun hexText(bytes: ByteArray): String = buildString(bytes.size * 3) {
    bytes.forEachIndexed { i, b ->
        if (i > 0) append(if (i % 16 == 0) '\n' else ' ')
        append(HEX[(b.toInt() shr 4) and 0xF])
        append(HEX[b.toInt() and 0xF])
    }
}

/**
 * Parses what [hexText] produces, and what a person is likely to type into it:
 * whitespace anywhere is ignored, everything else has to be a hex digit.
 * Throws [IllegalArgumentException] with a message meant for the screen.
 */
fun parseHexText(text: String): ByteArray {
    val digits = text.filterNot { it.isWhitespace() }
    val bad = digits.indexOfFirst { Character.digit(it, 16) < 0 }
    require(bad < 0) { "'${digits[bad]}' is not a hex digit" }
    require(digits.length % 2 == 0) { "${digits.length} hex digits: one is missing from the last byte" }
    return ByteArray(digits.length / 2) {
        ((Character.digit(digits[it * 2], 16) shl 4) or Character.digit(digits[it * 2 + 1], 16)).toByte()
    }
}

/**
 * A hue (degrees, 0 to under 360) for a byte value, for tinting hex bytes in
 * the preview sheet.  Multiplying by 97 scrambles the value over the wheel --
 * 97 is coprime with 256, so every byte gets its own hue and neighbouring
 * values land about 137 degrees apart instead of blending into each other.
 * The scale divides by 256 rather than 255, so no hue ever lands exactly on
 * 360 and wraps back onto 0's colour.  Pure and deterministic: a value always
 * reads the same colour, in any view, after any restart.
 */
fun byteHue(value: Int): Float = ((value and 0xFF) * 97 and 0xFF) / 256f * 360f

private const val HEX = "0123456789abcdef"
