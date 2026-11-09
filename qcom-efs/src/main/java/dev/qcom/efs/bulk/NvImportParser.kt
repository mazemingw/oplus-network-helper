package dev.qcom.efs.bulk

class NvImportParseException(message: String) : Exception(message)

object NvImportParser {

    /**
     * mtb writes per-SIM item files with a subscription suffix; SIM0 has none.
     * Hand-written files that already carry it are left alone.
     */
    const val SUBSCRIPTION_SUFFIX = "_Subscription01"

    private const val TAG_SIM0 = "SIM0"
    private const val TAG_SIM1 = "SIM1"

    private val VALID_SLOT_KEYS = setOf("sim0", "sim1", "dualsim")

    @Throws(NvImportParseException::class)
    fun parse(jsonString: String): List<BulkCommand> {
        return try {
            parseInternal(jsonString)
        } catch (e: NvImportParseException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw NvImportParseException("Malformed JSON: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            throw NvImportParseException("Malformed JSON: unexpected end of input")
        }
    }

    private fun parseInternal(jsonString: String): List<BulkCommand> {
        val root = parseObject(jsonString.trim())

        if (root.isEmpty()) {
            throw NvImportParseException("No sim slot key found in JSON")
        }

        val unknownKeys = root.keys.filter { it !in VALID_SLOT_KEYS }
        if (unknownKeys.isNotEmpty()) {
            throw NvImportParseException("Unknown top-level key(s): ${unknownKeys.joinToString()}")
        }

        val commands = mutableListOf<BulkCommand>()

        for ((slotKey, slotValue) in root.entries) {
            val slots: List<Int> = when (slotKey) {
                "sim0"    -> listOf(0)
                "sim1"    -> listOf(1)
                "dualsim" -> listOf(0, 1)
                else      -> error("unreachable: unknown key '$slotKey' should have been rejected above")
            }

            val slotObj = slotValue as? OrderedMap
                ?: throw NvImportParseException("Expected object for slot key: $slotKey")

            for (slot in slots) {
                for ((pathKey, pathValue) in slotObj.entries) {
                    val fileObj = pathValue as? OrderedMap
                        ?: throw NvImportParseException("Expected object for path: $pathKey")

                    for ((filename, entryValue) in fileObj.entries) {
                        val entryObj = entryValue as? OrderedMap
                            ?: throw NvImportParseException("Expected object for entry: $filename")
                        val basePath = pathKey + filename

                        val op = entryObj["op"] as? String
                            ?: throw NvImportParseException("Missing or invalid 'op' for entry: $basePath")

                        when (op) {
                            "w" -> {
                                val hexStr = entryObj["data"] as? String
                                    ?: throw NvImportParseException("Missing 'data' for write op at: $basePath")
                                if (hexStr.isEmpty()) {
                                    throw NvImportParseException("data must not be empty for write op at $basePath")
                                }
                                parseHex(hexStr, basePath)
                                commands.add(
                                    BulkCommand(
                                        op = BulkOp.WRITE,
                                        efsPath = resolvePath(basePath, slot),
                                        dataHex = hexStr,
                                        simTag = if (slot == 0) TAG_SIM0 else TAG_SIM1,
                                    )
                                )
                            }
                            "d" -> commands.add(
                                BulkCommand(
                                    op = BulkOp.DELETE,
                                    efsPath = resolvePath(basePath, slot),
                                    dataHex = null,
                                    simTag = if (slot == 0) TAG_SIM0 else TAG_SIM1,
                                )
                            )
                            else -> throw NvImportParseException("Unknown op '$op' at: $basePath")
                        }
                    }
                }
            }
        }

        return commands
    }

    /** Slot 0 keeps the path as-is; slot 1 gets the subscription suffix. */
    private fun resolvePath(path: String, slot: Int): String =
        if (slot == 0 || path.endsWith(SUBSCRIPTION_SUFFIX)) path
        else path + SUBSCRIPTION_SUFFIX

    /** Throws unless [hexStr] is even-length hex; nothing is returned — [BulkCommand.dataHex] keeps the input verbatim. */
    private fun parseHex(hexStr: String, context: String) {
        if (hexStr.length % 2 != 0) {
            throw NvImportParseException("Invalid hex data (odd length) for: $context")
        }
        if (!hexStr.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            throw NvImportParseException("Invalid hex data (non-hex chars) for: $context")
        }
    }

    // Byte-identical port of mtbtool's NvImportParser JSON section (same dialect: objects/strings only; same error strings). Do not swap for org.json.
    // ── Minimal JSON parser (objects only, strings and nested objects) ──────────

    /** LinkedHashMap preserving insertion order */
    private class OrderedMap : LinkedHashMap<String, Any>()

    private fun parseObject(s: String): OrderedMap {
        val (result, _) = parseObjectAt(s, 0)
        return result
    }

    /** Parse a JSON object starting at index [pos] (must point to `{`). Returns the map and end index. */
    private fun parseObjectAt(s: String, pos: Int): Pair<OrderedMap, Int> {
        var i = skipWs(s, pos)
        require(s[i] == '{') { "Expected '{' at $i" }
        i++
        val map = OrderedMap()
        i = skipWs(s, i)
        if (i < s.length && s[i] == '}') return Pair(map, i + 1)
        while (i < s.length) {
            i = skipWs(s, i)
            // parse key
            val (key, afterKey) = parseString(s, i)
            i = skipWs(s, afterKey)
            require(s[i] == ':') { "Expected ':' at $i" }
            i = skipWs(s, i + 1)
            // parse value
            val (value, afterValue) = parseValue(s, i)
            map[key] = value
            i = skipWs(s, afterValue)
            if (i < s.length && s[i] == '}') return Pair(map, i + 1)
            require(i < s.length && s[i] == ',') { "Expected ',' or '}' at $i" }
            i++
        }
        throw IllegalArgumentException("Unterminated object")
    }

    private fun parseValue(s: String, pos: Int): Pair<Any, Int> {
        val i = skipWs(s, pos)
        return when {
            s[i] == '{' -> parseObjectAt(s, i)
            s[i] == '"' -> parseString(s, i)
            else -> throw IllegalArgumentException("Unsupported JSON value at $i: '${s[i]}'")
        }
    }

    private fun parseString(s: String, pos: Int): Pair<String, Int> {
        var i = skipWs(s, pos)
        require(s[i] == '"') { "Expected '\"' at $i" }
        i++
        val sb = StringBuilder()
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> return Pair(sb.toString(), i + 1)
                '\\' -> {
                    i++
                    when (val esc = s[i]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> {
                            sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(esc)
                    }
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun skipWs(s: String, pos: Int): Int {
        var i = pos
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }
}
