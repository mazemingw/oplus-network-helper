package com.nvmex.networkhelper.ui.home

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.model.network.NrCaCarrier
import com.nvmex.networkhelper.model.network.NrCaInfo
import com.nvmex.networkhelper.util.network.NrcaBandwidthEncoding
import com.nvmex.networkhelper.viewmodel.signal.parseNrCaJson
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NrcaRecordingTest {
    private fun carrier(bw: Int, state: Int = 2) = NrCaCarrier(
        ccId = 0, sccId = 0, pci = 100, bandRaw = 40, bandText = "-", dlArfcn = 520830,
        dlStateRaw = state, dlStateText = "-", dlBwRaw = bw, dlBwText = "-",
        ulStateRaw = 2, ulStateText = "-", ulBwRaw = bw, ulBwText = "-"
    )

    private fun sample(encoding: NrcaBandwidthEncoding, vararg carriers: NrCaCarrier): HomeLogSample {
        val info = NrCaInfo(0, 0, carriers.toList(), 1720000000000, encoding)
        return HomeLogSample(1720000000000, mapOf(1 to NetworkPanelUiState(
            subId = 1, dataNetworkType = "NR", nrCaInfo = info
        )), emptyMap(), null)
    }

    @Test fun exportsSameConfiguredAndActiveBandwidthForOldAndNew100MhzCarriers() {
        for ((encoding, raw100, raw20) in listOf(
            Triple(NrcaBandwidthEncoding.LEGACY, 14, 3),
            Triple(NrcaBandwidthEncoding.WITH_3_MHZ, 15, 4)
        )) {
            val panel = sample(encoding, carrier(raw100), carrier(raw20, 1), carrier(raw100, 0)).panel
            val columns = panelCsvHeaders("s").zip(panelCsvValues(panel)).toMap()
            assertEquals("120MHz/100MHz", columns["s_nrTotalActiveBw"])
            assertTrue(columns.getValue("s_nrCa").contains("dlBw=NR5G_BW_100MHZ"))
        }
    }

    @Test fun jsonAndCsvReplayKeepTheOriginalEncodingAndRawValues() {
        for (encoding in NrcaBandwidthEncoding.entries) {
            val original = sample(encoding, carrier(15))
            val jsonCopy = samplesFromJson(samplesToJson(listOf(original))).single()
            val csvCopy = HomeLogFiles.importText(HomeLogFiles.csv(listOf(original))).single()
            assertEquals(original, jsonCopy)
            assertEquals(original, csvCopy)
            val restored = requireNotNull(jsonCopy.panel.nrCaInfo)
            assertEquals(15, restored.carriers.single().dlBwRaw)
            val mhz = if (encoding == NrcaBandwidthEncoding.LEGACY) 200 else 100
            assertEquals(mhz, NrcaTranslator.bandwidthMhzOrNull(15, restored.bandwidthEncoding))
        }
    }

    @Test fun oldRecordingsWithoutEncodingContinueToUseLegacy100Mhz() {
        val original = sample(NrcaBandwidthEncoding.LEGACY, carrier(14))
        val json = samplesToJson(listOf(original))
        json.getJSONObject(0).getJSONObject("panels").getJSONObject("1")
            .getJSONObject("nrCaInfo").remove("bandwidthEncoding")
        val restored = samplesFromJson(json).single()
        assertEquals(original, restored)
        assertEquals("100MHz/100MHz", panelCsvHeaders("s").zip(panelCsvValues(restored.panel)).toMap()["s_nrTotalActiveBw"])
    }

    @Test fun broadcastEncodingWinsOverReceiverDeviceAndOldModulesUseDeviceFallback() {
        val payload = JSONObject().put("list", JSONArray().put(JSONObject()
            .put("dlBw", 15).put("ulBw", 15)))
        val extended = NrcaBandwidthEncoding.WITH_3_MHZ
        val legacy = NrcaBandwidthEncoding.LEGACY
        val oldModule = parseNrCaJson(0, 0, payload.toString(), extended)
        assertEquals(extended, oldModule.bandwidthEncoding)
        assertEquals(100, oldModule.bandwidthEncoding.mhzOrNull(oldModule.carriers.single().dlBwRaw))
        payload.put("bandwidthEncoding", extended.wireValue)
        assertEquals(extended, parseNrCaJson(0, 0, payload.toString(), legacy).bandwidthEncoding)
        payload.put("bandwidthEncoding", legacy.wireValue)
        assertEquals(legacy, parseNrCaJson(0, 0, payload.toString(), extended).bandwidthEncoding)
    }
}
