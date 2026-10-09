package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.ModemQosInfo
import com.nvmex.networkhelper.model.network.ModemQosSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class ModemQosParserTest {
    @Test
    fun parsesNrImsDirectAndTransportWithoutInventingAnEpsQci() {
        val ims = hex("2e0388c21300000606001e06001e790006012041010105250403696d73")
        val direct = requireNotNull(ModemQosParser.parse5gDiagPayload(hex("01000000110000") + ims))
        assertEquals("ims", direct.dnn)
        assertNull(direct.qci)
        assertEquals(5, direct.fiveQi)
        assertEquals(1, direct.qfi)
        assertEquals(30_000L, direct.downlinkAmbrKbps)
        assertEquals(30_000L, direct.uplinkAmbrKbps)
        val transport = hex("7e006801") + byteArrayOf(0, ims.size.toByte()) + ims
        assertEquals(direct, ModemQosParser.parse5gDiagPayload(hex("01000000110000") + transport))
        assertNull(ModemQosParser.parse5gNas(transport.copyOf().apply { this[3] = 2 }))
        assertNull(ModemQosParser.parse5gNas(transport.copyOf().apply { this[1] = 2 }))
    }
    @Test
    fun parsesNewAdbCapturedImsApnQciAndAmbr() {
        val nas = hex("6210c10105" + "1703696d73066d6e63303032066d63633436300467707273" + "0101" + "5e04fefe10105833")
        val parsed = requireNotNull(ModemQosParser.parseLteNas(nas))
        assertEquals("ims.mnc002.mcc460.gprs", parsed.dnn)
        assertEquals(5, parsed.qci)
        assertEquals(10_200L, parsed.downlinkAmbrKbps)
        assertEquals(10_200L, parsed.uplinkAmbrKbps)
    }
    @Test
    fun parsesNrSessionAmbrMappedQciAndFiveQi() {
        val nas = hex(
            "2e0288c21300000603f4240261a8" +
                "75001350001042010506ffffffff0406fefeee9e0300" +
                "790009012042010106070100" +
                "250605636d6e6574"
        )

        val parsed = requireNotNull(ModemQosParser.parse5gNas(nas))
        assertEquals(6, parsed.qci)
        assertEquals(6, parsed.fiveQi)
        assertEquals(1, parsed.qfi)
        assertEquals(1_000_000L, parsed.downlinkAmbrKbps)
        assertEquals(100_000L, parsed.uplinkAmbrKbps)
        assertEquals("cmnet", parsed.dnn)
    }

    @Test
    fun parsesCapturedLteQciAndAdditiveApnAmbr() {
        val nas = hex(
            "520bc10108" + // Captured CMNET default EPS bearer: QCI 8.
                "0605636d6e6574" + // cmnet APN
                "0101" + // IPv4 PDN address type
                "5e06fefeee9e0300" // DL = 232 + 3*256 = 1000 Mbps, UL = 100 Mbps.
        )

        val parsed = requireNotNull(ModemQosParser.parseLteNas(nas))
        assertEquals(8, parsed.qci)
        assertEquals(1_000_000L, parsed.downlinkAmbrKbps)
        assertEquals(100_000L, parsed.uplinkAmbrKbps)
        assertEquals("cmnet", parsed.dnn)
    }

    @Test
    fun boundsShortApnAmbrToItsOwnIe() {
        val header = "5201c101050403696d730101"
        val fourOctets = requireNotNull(ModemQosParser.parseLteNas(hex(header + "5e04fefe10105833")))
        assertEquals(10_200L, fourOctets.downlinkAmbrKbps)
        assertEquals(10_200L, fourOctets.uplinkAmbrKbps)
        val twoOctets = requireNotNull(ModemQosParser.parseLteNas(hex(header + "5e02fefe5833")))
        assertEquals(8_640L, twoOctets.downlinkAmbrKbps)
        assertEquals(8_640L, twoOctets.uplinkAmbrKbps)
    }

    @Test
    fun decodesLargeExtendedApnAmbrAndIgnoresReservedUnits() {
        val header = "5201c101060605636d6e657401015e06fefeee9e0300"
        val large = requireNotNull(ModemQosParser.parseLteNas(hex(header + "5f06070064070044")))
        assertEquals(100_000_000L, large.downlinkAmbrKbps)
        assertEquals(68_000_000L, large.uplinkAmbrKbps)
        val reserved = requireNotNull(ModemQosParser.parseLteNas(hex(header + "5f060203e8020064")))
        assertEquals(1_000_000L, reserved.downlinkAmbrKbps)
        assertEquals(100_000L, reserved.uplinkAmbrKbps)
    }

    @Test
    fun usesQualcommDiagCrcX25() {
        assertEquals(0x9d61, ModemQosRepository.crc16X25(hex("7300000001000000")))
    }

    @Test
    fun parsesQualcomm5gMmStateAndTmsi() {
        val parsed = requireNotNull(
            ModemQosParser.parse5gMmStateDiagPayload(
                hex("0100000003000054f0800254f080a206001636ac480400a040fe")
            )
        )
        assertEquals(3, parsed.mmState)
        assertEquals(0, parsed.mmSubState)
        assertEquals("0448AC36", parsed.fiveGTmsi)
    }

    @Test
    fun storesBothGenerationsAndSelectsTheCurrentNetwork() {
        val nr = qosInfo(ModemQosInfo.Source.NR_5GSM, 6, 2_000_000L, 150_000L)
        val lte = qosInfo(ModemQosInfo.Source.LTE_ESM, 8, 1_000_000L, 100_000L)
        for (sessions in listOf(ModemQosSessions().withInfo(nr).withInfo(lte), ModemQosSessions().withInfo(lte).withInfo(nr))) {
            assertSame(lte, sessions.dataFor(ModemQosInfo.Source.forNetwork(13)))
            assertSame(nr, sessions.dataFor(ModemQosInfo.Source.forNetwork(20)))
            assertSame(lte, sessions.dataFor(ModemQosInfo.Source.forNetwork(13, "NSA")))
        }
    }

    @Test
    fun imsDoesNotBorrowThePreviousNetworksSession() {
        val nr = qosInfo(ModemQosInfo.Source.NR_5GSM, 5, 10_240L, 10_240L)
            .copy(sessionKind = ModemQosInfo.SessionKind.IMS, dnn = "ims")
        val lte = qosInfo(ModemQosInfo.Source.LTE_ESM, 5, 10_200L, 10_200L)
            .copy(sessionKind = ModemQosInfo.SessionKind.IMS, dnn = "ims")
        assertNull(ModemQosSessions().withInfo(nr).imsFor(ModemQosInfo.Source.LTE_ESM))
        for (sessions in listOf(ModemQosSessions().withInfo(nr).withInfo(lte), ModemQosSessions().withInfo(lte).withInfo(nr))) {
            assertSame(nr, sessions.imsFor(ModemQosInfo.Source.NR_5GSM))
            assertSame(lte, sessions.imsFor(ModemQosInfo.Source.LTE_ESM))
            assertNull(sessions.imsFor(null))
        }
    }

    @Test
    fun doesNotMislabelLteAs5gWhenNrCannotBeRead() {
        val lte = qosInfo(ModemQosInfo.Source.LTE_ESM, 8, 1_000_000L, 100_000L)
        val sessions = ModemQosSessions().withInfo(lte)
        assertSame(lte, sessions.dataFor(ModemQosInfo.Source.forNetwork(13)))
        assertNull(sessions.dataFor(ModemQosInfo.Source.forNetwork(20)))
        assertNull(sessions.dataFor(ModemQosInfo.Source.forNetwork(0)))
    }

    private fun qosInfo(
        source: ModemQosInfo.Source,
        qci: Int,
        downlinkKbps: Long,
        uplinkKbps: Long
    ) = ModemQosInfo(
        subId = 1,
        slotIndex = 0,
        sessionKind = ModemQosInfo.SessionKind.DATA,
        dnn = "cmnet",
        logCode = if (source == ModemQosInfo.Source.NR_5GSM) 0xb800 else 0xb0e2,
        qci = qci,
        fiveQi = if (source == ModemQosInfo.Source.NR_5GSM) qci else null,
        qfi = if (source == ModemQosInfo.Source.NR_5GSM) 1 else null,
        downlinkAmbrKbps = downlinkKbps,
        uplinkAmbrKbps = uplinkKbps,
        source = source
    )

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
