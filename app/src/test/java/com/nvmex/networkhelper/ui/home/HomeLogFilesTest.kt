package com.nvmex.networkhelper.ui.home

import com.nvmex.networkhelper.model.network.*
import com.nvmex.networkhelper.util.records.CsvTable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HomeLogFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun sample() = HomeLogSample(1720000000000, mapOf(1 to NetworkPanelUiState(subId = 1, operatorName = "上海,\"卡1\"\n测试", cellType = "NR", ci = "68719476001", arfcn = "520830", rsrp = "-95"),
        2 to NetworkPanelUiState(subId = 2, cellType = "LTE", ci = "25601", arfcn = "1650")),
        mapOf(1 to QosData(timestamp = 1720000000000, subId = 1, rsrp = -95, rat = 11)), GlobalQosEvent(timestamp = 1720000000000, cellChangeCount = 3))
    @Test fun csvContainsReadableColumnsAndRestoresBothSimsQosAndGlobalEvents() {
        val sample = sample()
        val csv = HomeLogFiles.csv(listOf(sample))
        assertTrue(csv.contains("Sub1_cellType"))
        assertEquals(sample, HomeLogFiles.importText(csv).single())
    }
    @Test fun archivesKeepMultipleRecordingsAndCanRemoveTheLatestReplayCopy() {
        val root = temp.newFolder()
        val first = HomeLogFiles.save(root, listOf(sample()))
        val second = HomeLogFiles.save(root, listOf(sample().copy(ts = 1720000001000)))
        assertEquals(2, HomeLogFiles.list(root).size)
        assertTrue(first.exists())
        HomeLogFiles.delete(root, second)
        assertFalse(File(root, "home_log_recording.json").exists())
        assertEquals(listOf(first), HomeLogFiles.list(root))
    }
    @Test fun legacyLastRecordingIsMigratedOnlyOnceAndJsonIsStillImportable() {
        val root = temp.newFolder()
        val json = samplesToJson(listOf(sample())).toString()
        File(root, "home_log_recording.json").writeText(json)
        assertEquals(1, HomeLogFiles.list(root).size)
        assertEquals(1, HomeLogFiles.list(root).size)
        assertEquals(sample(), HomeLogFiles.importText(json).single())
    }
    @Test fun oldFlatCsvWithOnlyTheSecondSimRestoresItsOwnQos() {
        val csv = CsvTable.encode(listOf("timestamp", "Sub2_subId", "Sub2_operator", "Sub2_cellType", "Sub2_qos_rsrp"),
            listOf(listOf("1720000000000", "7", "卡2", "LTE", "-98")))
        val restored = HomeLogFiles.importText(csv).single()
        assertEquals(setOf(7), restored.panelBySubId.keys)
        assertEquals(-98, restored.qosBySubId.getValue(7).rsrp)
        assertEquals(7, restored.qosBySubId.getValue(7).subId)
    }
}
