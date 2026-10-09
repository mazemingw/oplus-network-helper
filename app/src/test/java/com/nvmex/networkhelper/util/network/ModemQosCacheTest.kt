package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.ModemQosInfo
import com.nvmex.networkhelper.model.network.ModemQosSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModemQosCacheTest {
    @Test fun roundTripsAllGenerationsAndRebindsSubscription() {
        val original = ModemQosSessions(
            lteData = info(1, 0, ModemQosInfo.Source.LTE_ESM, ModemQosInfo.SessionKind.DATA, "cmnet", 8, 1_000_000, 100_000),
            nrData = info(1, 0, ModemQosInfo.Source.NR_5GSM, ModemQosInfo.SessionKind.DATA, "cmnet", 9, 2_000_000, 150_000),
            lteIms = info(1, 0, ModemQosInfo.Source.LTE_ESM, ModemQosInfo.SessionKind.IMS, "ims", 5, 10_200, 10_200),
            nrIms = info(1, 0, ModemQosInfo.Source.NR_5GSM, ModemQosInfo.SessionKind.IMS, "ims", 6, 10_000, 10_000)
        )
        val restored = ModemQosCacheCodec.decode(ModemQosCacheCodec.encode(original)!!, 9, 1)!!
        assertEquals(9, restored.nrData?.subId)
        assertEquals(1, restored.nrData?.slotIndex)
        assertEquals(2_000_000L, restored.nrData?.downlinkAmbrKbps)
        assertTrue(restored.nrData?.cached == true)
        assertEquals("ims", restored.lteIms?.dnn)
    }

    @Test fun roundTripsImsOnlyAndRejectsMalformedData() {
        val original = ModemQosSessions(lteIms = info(1, 0, ModemQosInfo.Source.LTE_ESM, ModemQosInfo.SessionKind.IMS, null, 5, 10_200, 10_200))
        val restored = ModemQosCacheCodec.decode(ModemQosCacheCodec.encode(original)!!, 2, 0)
        assertEquals(5, restored?.lteIms?.qci)
        assertNull(restored?.lteIms?.dnn)
        assertNull(ModemQosCacheCodec.decode("broken", 2, 0))
    }

    @Test fun cacheKeyDoesNotExposeIccid() {
        val iccid = "89860012345678901234"
        val key = ModemQosCacheCodec.keyForIccid(iccid)
        assertFalse(key.contains(iccid))
        assertTrue(key.startsWith("iccid_"))
    }

    @Test fun parsesOnlyActiveRootIccidsBySlot() {
        val output = """
            Row: 0 _id=1, icc_id=898600C82024F0019743, sim_id=0
            Row: 1 _id=2, icc_id=89861122347719791805, sim_id=-1
            Row: 2 _id=5, icc_id=89860125801144184902, sim_id=1
        """.trimIndent()
        val values = ModemQosIccidParser.parseBySlot(output)
        assertEquals(setOf(0, 1), values.keys)
        assertEquals("898600C82024F0019743", values[0])
    }

    private fun info(subId: Int, slot: Int, source: ModemQosInfo.Source, kind: ModemQosInfo.SessionKind, dnn: String?, qci: Int, dl: Long, ul: Long) =
        ModemQosInfo(subId, slot, kind, dnn, 0xb800, qci, qci, null, dl, ul, source, 1234L)
}
