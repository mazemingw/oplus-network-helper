package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.records.CsvTable
import org.junit.Assert.*
import org.junit.Test

class DriveCsvTest {
    private fun point(slot: Int = 0): DrivePointEntity {
        val wgs = 31.1234567890123 to 121.1234567890123
        val gcj = Gcj02.wgs84ToGcj02(wgs.first, wgs.second)
        val state = NetworkPanelUiState(subId = slot + 1, operatorName = "运营商,\"甲\"\n测试", cellType = "NR", ci = "68719476001", arfcn = "520830", tac = "8", pci = "100")
        return DrivePointEntity(sessionId = "original", ts = 1720000000000, simSlot = slot, lat = gcj.first, lng = gcj.second,
            rsrpDbm = -95, isHandover = true, cellKey = DriveStyle.cellKey(state), snapshotJson = SnapshotJson.toJson(state),
            estimated = true, accuracyMeters = 12.5f, segmentStart = true, rawLat = wgs.first, rawLng = wgs.second, rawCoordType = "WGS84_ESTIMATED")
    }
    @Test fun bothSimsAndBothCoordinatesAndStationSnapshotRoundTripWithoutOverwritingTheOriginalSession() {
        val a = point(); val b = point(1)
        val snapshot = DriveStationSnapshot(rat = "NR", latitude = 31.1, longitude = 121.1, name = "服务站",
            sectors = listOf(DriveStationSector("68719476001", 8, 520830, 100, null, null, "服务站", 31.1, 121.1, 0)))
        val station = DriveStationEntity(a.sessionId, 0, a.cellKey, a.ts, a.lat, a.lng, DriveStationJson.encode(snapshot))
        val csv = DriveCsv.encode(listOf(a, b), listOf(station), "上海")
        val columns = CsvTable.decode(csv)
        assertEquals("1", columns[0]["sim_slot"]); assertEquals("2", columns[1]["sim_slot"])
        assertEquals(a.rawLat.toString(), columns[0]["wgs84_latitude"])
        assertEquals("raw", columns[0]["wgs84_origin"])
        val imported = DriveCsv.decode(csv).single()
        assertNotEquals(a.sessionId, imported.points.first().sessionId)
        assertEquals(2, imported.points.size)
        assertEquals(a, imported.points[0].copy(sessionId = a.sessionId))
        assertEquals(b, imported.points[1].copy(sessionId = b.sessionId))
        assertEquals(station, imported.stations.single().copy(sessionId = station.sessionId))
        assertEquals("上海", imported.city)
    }
    @Test fun legacyCoordinatesAreLabeledConvertedAndNeverInventRawObservations() {
        val p = point().copy(rawLat = null, rawLng = null, rawCoordType = null)
        val csv = DriveCsv.encode(listOf(p), emptyList(), "")
        val row = CsvTable.decode(csv).single()
        assertEquals("", row["raw_latitude"])
        assertEquals("legacy_converted_from_gcj02", row["wgs84_origin"])
        assertNull(DriveCsv.decode(csv).single().points.single().rawLat)
    }
    @Test fun wgsOnlyExternalTableGetsConvertedOnceAndRetainsTheImportedSource() {
        val csv = CsvTable.encode(listOf("timestamp", "sim_slot", "wgs84_latitude", "wgs84_longitude"), listOf(listOf("1720000000000", "2", "31.1", "121.1")))
        val p = DriveCsv.decode(csv).single().points.single()
        assertEquals(1, p.simSlot)
        assertEquals(31.1, p.rawLat!!, 0.0)
        assertEquals("WGS84_IMPORTED", p.rawCoordType)
        assertEquals(Gcj02.wgs84ToGcj02(31.1, 121.1).first, p.lat, 0.00000001)
    }
    @Test fun invalidCoordinatesAndSlotsAndTimestampsRejectTheWholeFile() {
        listOf(listOf("bad", "1", "31", "121"), listOf("1000", "3", "31", "121"), listOf("1000", "1", "NaN", "121"), listOf("1000", "1", "91", "121")).forEach { row ->
            val csv = CsvTable.encode(listOf("timestamp", "sim_slot", "gcj02_latitude", "gcj02_longitude"), listOf(row))
            assertTrue(runCatching { DriveCsv.decode(csv) }.isFailure)
        }
    }
}
