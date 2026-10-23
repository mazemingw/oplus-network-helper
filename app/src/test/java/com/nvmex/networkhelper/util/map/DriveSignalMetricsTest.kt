package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.records.CsvTable
import org.junit.Assert.*
import org.junit.Test

class DriveSignalMetricsTest {
    @Test fun nrUsesSsMetricsAndLteUsesGenericMeasurements() {
        val state = NetworkPanelUiState(cellType = "NR", rsrp = "-110", rsrq = "-17", sinr = "3", ssRsrp = "-95 dBm", ssRsrq = "-10 dB", ssSinr = "21.5 dB")
        val nr = DriveSignalMetrics.fromState(state)
        assertEquals("-95", nr.rsrp); assertEquals("-10", nr.rsrq); assertEquals("21.5", nr.sinr)
        assertEquals("SS-SINR", nr.sinrLabel)
        val lte = DriveSignalMetrics.fromState(state.copy(cellType = "LTE"))
        assertEquals("-17", lte.rsrq); assertEquals("3", lte.sinr); assertEquals("SINR", lte.sinrLabel)
    }
    @Test fun missingSsUsesGenericLabelAndMissingMeasurementsRemainMissing() {
        val value = DriveSignalMetrics.fromState(NetworkPanelUiState(cellType = "NR", ssSinr = "2147483647", sinr = "12", rsrq = "-14"))
        assertEquals("12", value.sinr); assertEquals("SINR", value.sinrLabel)
        assertEquals("RSRQ", value.rsrqLabel)
        assertNull(DriveSignalMetrics.fromState(NetworkPanelUiState()).sinr)
        assertNull(DriveSignalMetrics.value("unknown 12"))
    }
    @Test fun snapshotAndReadableCsvRetainBothSsAndGenericMetrics() {
        val state = NetworkPanelUiState(cellType = "NR", rsrp = "-110", rsrq = "-17", sinr = "3", ssRsrp = "-95", ssRsrq = "-10", ssSinr = "21")
        val json = SnapshotJson.toJson(state)
        assertEquals(state, SnapshotJson.fromJson(json))
        val point = DrivePointEntity(sessionId = "s", ts = 1720000000000, lat = 31.1, lng = 121.1, rsrpDbm = -95,
            isHandover = false, cellKey = "x", snapshotJson = json)
        val csv = DriveCsv.encode(listOf(point), emptyList(), "")
        val row = CsvTable.decode(csv).single()
        assertEquals("-17", row["rsrq_db"]); assertEquals("3", row["sinr_db"])
        assertEquals("-10", row["ss_rsrq_db"]); assertEquals("21", row["ss_sinr_db"])
        assertEquals(state, SnapshotJson.fromJson(DriveCsv.decode(csv).single().points.single().snapshotJson))
        val withoutSnapshot = CsvTable.encode(row.keys.toList().filter { it != "snapshot_json" }, listOf(row.filterKeys { it != "snapshot_json" }.values.toList()))
        val imported = SnapshotJson.fromJson(DriveCsv.decode(withoutSnapshot).single().points.single().snapshotJson)
        assertEquals("21", imported.ssSinr); assertEquals("-10", imported.ssRsrq)
    }
    @Test fun legendAndPointColorsUseIdenticalInclusiveBoundaries() {
        val thresholds = listOf(-80, -90, -100, -110, -140)
        thresholds.forEachIndexed { i, value -> assertEquals(DriveStyle.signalBands[i].color, DriveStyle.colorByRsrp(value)) }
        assertEquals(DriveStyle.signalBands[1].color, DriveStyle.colorByRsrp(-81))
        assertEquals(DriveStyle.signalBands[4].color, DriveStyle.colorByRsrp(-111))
        assertEquals(DriveStyle.signalBands.last().color, DriveStyle.colorByRsrp(null))
    }
}
