package com.nvmex.networkhelper.util.records

import org.junit.Assert.*
import org.junit.Test

class CsvTableTest {
    @Test fun quotedChineseCommasNewlinesAndNegativeNumbersRoundTrip() {
        val values = listOf("上海,站点\n\"A\"", "-95", "121.1234567890123", "", "文字")
        val headers = values.indices.map { "col$it" }
        assertEquals(values, CsvTable.decode(CsvTable.encode(headers, listOf(values))).single().values.toList())
    }
    @Test fun formulaLikeTextIsNeutralizedForSpreadsheetOpening() {
        val row = CsvTable.decode(CsvTable.encode(listOf("name", "rsrp"), listOf(listOf("=1+1", "-100")))).single()
        assertEquals("'=1+1", row["name"])
        assertEquals("-100", row["rsrp"])
    }
    @Test fun malformedOrAmbiguousTablesAreRejectedBeforeAnyImport() {
        listOf("a,a\n1,2", "a,b\n1", "a\n\"unterminated", "a\n\"x\"bad").forEach {
            assertTrue(it, runCatching { CsvTable.decode(it) }.isFailure)
        }
    }
    @Test fun readsExcelBomCrLfAndEscapedQuotes() {
        assertEquals("a\"b", CsvTable.decode("\uFEFFid,name\r\n1,\"a\"\"b\"\r\n").single()["name"])
    }
}
