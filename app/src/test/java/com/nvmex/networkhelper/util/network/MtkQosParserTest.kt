package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.ModemQosInfo
import org.junit.Assert.*
import org.junit.Test

class MtkQosParserTest {
    @Test fun matchesContextsByCidAndKeepsKbpsUnitsAndBothDirections() {
        val text = "+CGDCONT: 0,\"IPV4V6\",\"cmnet\"\n+CGDCONT: 2,\"IPV4V6\",\"ims\"\n+CGEQOSRDP: 2,5,,,,,10200,10200\n+CGEQOSRDP: 0,8,,,,,1000000,100000"
        val values = MtkQosParser.parse(text, 5, 1, ModemQosInfo.Source.LTE_ESM)
        assertEquals(2, values.size)
        assertEquals(ModemQosInfo.SessionKind.IMS, values[0].sessionKind)
        assertEquals(8, values[1].qci)
        assertEquals(1000000L, values[1].downlinkAmbrKbps)
        assertEquals(100000L, values[1].uplinkAmbrKbps)
        assertTrue(values.all { it.subId == 5 && it.slotIndex == 1 })
    }
    @Test fun neverPromotesGbrMbrIntoAmbrOrLteInto5g() {
        val text = "+CGDCONT: 1,\"IP\",\"cmnet\"\n+CGEQOSRDP: 1,9,1000,2000,3000,4000"
        val value = MtkQosParser.parse(text, 1, 0, ModemQosInfo.Source.LTE_ESM).single()
        assertNull(value.downlinkAmbrKbps)
        assertNull(value.uplinkAmbrKbps)
        assertTrue(MtkQosParser.parse(text, 1, 0, ModemQosInfo.Source.NR_5GSM).isEmpty())
    }
    @Test fun reads5gSessionAmbrWithoutGuessingQfi() {
        val text = "+CGDCONT: 4,\"IPV4V6\",\"cmnet\"\n+C5GQOSRDP: 4,6,,,,,2000000,150000,100"
        val value = MtkQosParser.parse(text, 1, 0, ModemQosInfo.Source.NR_5GSM).single()
        assertEquals(6, value.fiveQi)
        assertEquals(2000000L, value.downlinkAmbrKbps)
        assertEquals(150000L, value.uplinkAmbrKbps)
        assertNull(value.qfi)
    }
    @Test fun ignoresUnattributedAndMalformedResponses() {
        assertTrue(MtkQosParser.parse("+CGEQOSRDP: 1,9,,,,,100,200\nERROR", 1, 0, ModemQosInfo.Source.LTE_ESM).isEmpty())
        val value = MtkQosParser.parse("+CGDCONT: 1,\"IP\",\"cmnet\"\n+CGEQOSRDP: 1,8,,,,,-20,overflow", 1, 0, ModemQosInfo.Source.LTE_ESM).single()
        assertNull(value.downlinkAmbrKbps)
        assertNull(value.uplinkAmbrKbps)
    }
}
