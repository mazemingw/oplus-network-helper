package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NrCellParamItem
import org.junit.Assert.*
import org.junit.Test

class DriveStationJsonTest {
    @Test fun lteSnapshotRetainsAllAntennaFacesAndIdentityWithoutAnyLookup() {
        val rows = listOf(0, 120, 240).mapIndexed { i, angle -> LteCellParam(eci = 25601L + i,
            enodeb_id = 100, cell_id = i + 1, tac = 9, earfcn = 1650, pci = i + 10,
            cell_name = "站点甲-$i", latitude = 31.0, longitude = 121.0, azimuth = angle) }
        val saved = DriveStationSnapshot(rat = "LTE", latitude = 31.0, longitude = 121.0,
            name = "站点甲", sectors = rows.map { DriveStationSector.from(it) })
        val text = DriveStationJson.encode(saved)
        assertTrue(text.contains("\"azimuth\":0"))
        val restored = DriveStationJson.decode(text)!!
        assertEquals(saved, restored)
        assertEquals(listOf(0, 120, 240), restored.sectors.map { it.lte().azimuth })
        assertEquals(rows.map { it.eci }, restored.sectors.map { it.lte().eci })
        assertEquals(rows.map { it.earfcn }, restored.sectors.map { it.lte().earfcn })
    }
    @Test fun nrSnapshotPreservesLongNciAndDirectionAlongsideOtherFrequencyFaces() {
        val rows = listOf(620000, 633334).mapIndexed { i, channel -> NrCellParamItem(gcell_id = "6871947600$i",
            nr_tac = 8, nr_arfcn = channel, nr_pci = 100 + i, cell_name = "5G-$i",
            latitude = 31.0, longitude = 121.0, azimuth = 90 + i * 120) }
        val saved = DriveStationSnapshot(rat = "NR", latitude = 31.0, longitude = 121.0,
            name = "5G", sectors = rows.map { DriveStationSector.from(it) })
        val restored = DriveStationJson.decode(DriveStationJson.encode(saved))!!
        assertEquals(rows.map { it.gcell_id }, restored.sectors.map { it.nr().gcell_id })
        assertEquals(rows.map { it.nr_arfcn }, restored.sectors.map { it.nr().nr_arfcn })
        assertEquals(rows.map { it.azimuth }, restored.sectors.map { it.nr().azimuth })
    }
    @Test fun malformedOrUnsupportedSavedDataCannotBreakReplay() {
        assertNull(DriveStationJson.decode("not json"))
        assertNull(DriveStationJson.decode("{}"))
        assertNull(DriveStationJson.decode("{\"version\":999,\"rat\":\"NR\",\"latitude\":31,\"longitude\":121,\"sectors\":[{}]}"))
    }

    @Test fun offlineReplayKeepsTheSelectedSimAndTheOriginalConnectionOrigin() {
        val sector = DriveStationSector("25601", 9, 1650, 10, 100, 1, "站点", 31.0, 121.0, 0)
        val json = DriveStationJson.encode(DriveStationSnapshot(rat = "LTE", latitude = 31.0,
            longitude = 121.0, name = "站点", sectors = listOf(sector)))
        val first = DriveStationEntity("session", 0, "LTE|25601", 1000, 31.001, 121.002, json)
        val second = first.copy(simSlot = 1, cellKey = "LTE|25602", userLat = 32.0)
        val corrupt = first.copy(cellKey = "broken", snapshotJson = "bad json")
        val saved = listOf(first, second, corrupt)
        assertEquals(listOf(first), driveRecordedStations(saved, 0).map { it.first })
        assertEquals(32.0, driveRecordedStations(saved, 1).single().first.userLat, 0.0)
        assertEquals(0, driveRecordedStations(saved, 0).single().second.sectors.single().azimuth)
    }

    @Test fun replayExpandsOnlyTheSelectedPointsServingStationAndNeverAFallbackStation() {
        val a = DriveStationSector("25601", 9, 1650, 10, 100, 1, "A", 31.0, 121.0, 0)
        val b = a.copy(ci = "25602", name = "B", longitude = 121.01, azimuth = 120)
        fun saved(key: String, sector: DriveStationSector, slot: Int = 0) = DriveStationEntity("session", slot,
            key, 1000, 31.001, 121.002, DriveStationJson.encode(DriveStationSnapshot(rat = "LTE",
                latitude = sector.latitude!!, longitude = sector.longitude!!, name = sector.name, sectors = listOf(sector))))
        val rows = listOf(saved("A", a), saved("B", b), saved("C", a, slot = 1))
        val stations = driveRecordedStations(rows, 0)
        assertEquals("B", driveHistoryServingStation(stations, "B")!!.second.name)
        assertEquals(listOf(120), driveHistoryServingStation(stations, "B")!!.second.sectors.map { it.azimuth })
        assertNull(driveHistoryServingStation(stations, "missing"))
        assertNull(driveHistoryServingStation(stations, "C"))
        assertNull(driveHistoryServingStation(stations, null))
    }
}
