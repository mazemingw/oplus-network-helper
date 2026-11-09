package dev.qcom.efs.bulk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NvImportParserTest {

    private fun parse(json: String) = NvImportParser.parse(json)

    private fun write(path: String, hex: String, tag: String = "SIM0") =
        BulkCommand(BulkOp.WRITE, path, hex, tag)

    private fun delete(path: String, tag: String = "SIM0") =
        BulkCommand(BulkOp.DELETE, path, null, tag)

    // ---- happy paths ----------------------------------------------------

    @Test
    fun `parses a simple sim0 write`() {
        val cmds = parse("""{"sim0":{"/nv/item_files/modem/mmode/":{"cm_mode_pref":{"op":"w","data":"0500"}}}}""")
        assertEquals(listOf(write("/nv/item_files/modem/mmode/cm_mode_pref", "0500")), cmds)
    }

    @Test
    fun `sim1 appends the subscription suffix`() {
        val cmds = parse("""{"sim1":{"/nv/item_files/modem/mmode/":{"cm_mode_pref":{"op":"w","data":"0500"}}}}""")
        assertEquals(
            listOf(write("/nv/item_files/modem/mmode/cm_mode_pref_Subscription01", "0500", "SIM1")),
            cmds,
        )
    }

    @Test
    fun `sim1 does not double-append the suffix`() {
        val cmds = parse("""{"sim1":{"/nv/x/":{"a_Subscription01":{"op":"d"}}}}""")
        assertEquals(listOf(delete("/nv/x/a_Subscription01", "SIM1")), cmds)
    }

    @Test
    fun `dualsim expands to sim0 then sim1`() {
        val cmds = parse("""{"dualsim":{"/nv/x/":{"a":{"op":"w","data":"ff"}}}}""")
        assertEquals(
            listOf(
                write("/nv/x/a", "ff", "SIM0"),
                write("/nv/x/a_Subscription01", "ff", "SIM1"),
            ),
            cmds,
        )
    }

    @Test
    fun `dualsim delete expands the same way`() {
        val cmds = parse("""{"dualsim":{"/nv/x/":{"a":{"op":"d"}}}}""")
        assertEquals(
            listOf(
                delete("/nv/x/a", "SIM0"),
                delete("/nv/x/a_Subscription01", "SIM1"),
            ),
            cmds,
        )
    }

    @Test
    fun `blocks are processed in document order`() {
        val cmds = parse(
            """{"sim1":{"/nv/x/":{"a":{"op":"w","data":"01"}}},""" +
                """"sim0":{"/nv/x/":{"a":{"op":"w","data":"02"}}}}""",
        )
        assertEquals(
            listOf(
                write("/nv/x/a_Subscription01", "01", "SIM1"),
                write("/nv/x/a", "02", "SIM0"),
            ),
            cmds,
        )
    }

    @Test
    fun `uppercase hex is accepted and kept verbatim`() {
        val cmds = parse("""{"sim0":{"/nv/x/":{"a":{"op":"w","data":"0A1F"}}}}""")
        assertEquals(listOf(write("/nv/x/a", "0A1F")), cmds)
    }

    @Test
    fun `delete entries omit data`() {
        val cmds = parse("""{"sim0":{"/nv/x/":{"a":{"op":"d"}}}}""")
        assertEquals(listOf(delete("/nv/x/a")), cmds)
    }

    @Test
    fun `pretty-printed json is accepted`() {
        val cmds = parse(
            """
            {
              "dualsim": {
                "/nv/x/": {
                  "a": { "op": "w", "data": "ff" }
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(listOf(write("/nv/x/a", "ff", "SIM0"), write("/nv/x/a_Subscription01", "ff", "SIM1")), cmds)
    }

    @Test
    fun `empty slot block is a valid no-op`() {
        assertEquals(emptyList<BulkCommand>(), parse("""{"sim0":{}}"""))
    }

    // ---- mtbtool-identical error cases -----------------------------------

    private fun expectError(json: String, expected: String) {
        val e = assertThrows(NvImportParseException::class.java) { parse(json) }
        assertTrue("message was: ${e.message}", e.message!!.contains(expected))
    }

    @Test
    fun `empty root is rejected`() {
        expectError("{}", "No sim slot key found in JSON")
    }

    @Test
    fun `unknown top-level keys are rejected`() {
        expectError("""{"sim0":{},"sim9":{}}""", "Unknown top-level key(s): sim9")
    }

    @Test
    fun `non-object slot value is rejected`() {
        expectError("""{"sim0":"x"}""", "Expected object for slot key: sim0")
    }

    @Test
    fun `non-object path value is rejected`() {
        expectError("""{"sim0":{"/nv/x/":"x"}}""", "Expected object for path: /nv/x/")
    }

    @Test
    fun `non-object entry value is rejected`() {
        expectError("""{"sim0":{"/nv/x/":{"a":"y"}}}""", "Expected object for entry: a")
    }

    @Test
    fun `missing op is rejected`() {
        expectError("""{"sim0":{"/nv/x/":{"a":{"data":"00"}}}}""", "Missing or invalid 'op' for entry: /nv/x/a")
    }

    @Test
    fun `unknown op is rejected`() {
        expectError("""{"sim0":{"/nv/x/":{"a":{"op":"x"}}}}""", "Unknown op 'x' at: /nv/x/a")
    }

    @Test
    fun `write without data is rejected`() {
        expectError("""{"sim0":{"/nv/x/":{"a":{"op":"w"}}}}""", "Missing 'data' for write op at: /nv/x/a")
    }

    @Test
    fun `empty data is rejected`() {
        expectError("""{"sim0":{"/nv/x/":{"a":{"op":"w","data":""}}}}""",
            "data must not be empty for write op at /nv/x/a")
    }

    @Test
    fun `odd-length hex is rejected`() {
        expectError("""{"sim0":{"/nv/x/":{"a":{"op":"w","data":"0f0"}}}}""",
            "Invalid hex data (odd length) for: /nv/x/a")
    }

    @Test
    fun `non-hex characters are rejected`() {
        expectError("""{"sim0":{"/nv/x/":{"a":{"op":"w","data":"zz"}}}}""",
            "Invalid hex data (non-hex chars) for: /nv/x/a")
    }

    @Test
    fun `malformed json is wrapped`() {
        expectError("""{"sim0":{""", "Malformed JSON")
    }

    @Test
    fun `unsupported value types are wrapped`() {
        expectError("""{"sim0":{"/nv/x/":{"a":{"op":"w","data":5}}}}""", "Malformed JSON")
    }
}
