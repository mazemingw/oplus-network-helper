package com.nvmex.networkhelper.util.map

import com.google.gson.JsonParser
import com.nvmex.networkhelper.util.map.matching.*
import org.junit.Assert.*
import org.junit.Test

class FalconPointCorrectionTest {
    private val time = 1_790_000_000_000L
    private val raw = RoutePosition(31.0, 121.0)
    private val trace = FalconTrace(1, 2, 3, listOf(listOf(FalconPoint(31.00001, 121.0, time),
        FalconPoint(31.00005, 121.0, time + 4000))))
    private fun metadata(source: MatchSource = MatchSource.GNSS) = trace.metadata(
        MatchFix(raw, 1000, source, 10.0, 0.0, 5.0, true, time + 2000))

    @Test fun delayedResultsCorrectRecordedTimestampWithoutMovingCurrentFixToOldEndpoint() {
        assertNull(trace.correctionFor(time + 30_000, raw, 5.0))
        val result = FalconCorrectionIndex(trace).apply(metadata(), time + 2100, raw, 5.0, false)
        assertNotNull(result)
        val match = JsonParser.parseString(result).asJsonObject
        assertEquals("ROAD", match["type"].asString)
        assertEquals("AMAP_FALCON", match["source"].asString)
        assertEquals(time + 2000, match["timestamp"].asLong)
        assertEquals(31.00003, falconRecordedPosition(result)!!.lat, .000001)
    }

    @Test fun updatesAreIdempotentAndCannotCrossResumedTrackIdsOrImuSources() {
        val index = FalconCorrectionIndex(trace)
        val first = index.apply(metadata(), time + 2100, raw, 5.0, false)!!
        assertNull(index.apply(first, time + 2100, raw, 5.0, false))
        assertNull(index.apply(metadata(MatchSource.IMU), time + 2100, raw, 5.0, false))
        assertNull(index.apply(metadata(), time + 2100, raw, 5.0, true))
        assertNull(FalconCorrectionIndex(trace.copy(trid = 4)).apply(metadata(), time + 2100, raw, 5.0, false))
    }

    @Test fun noInterpolationAcrossLongOutagesInvalidTimesOrLargeSpatialOffsets() {
        val gap = FalconCorrectionIndex(trace.copy(segments = listOf(listOf(FalconPoint(31.0, 121.0, time),
            FalconPoint(31.0, 121.0, time + 60_000)))))
        assertNull(gap.correction(time + 30_000, raw, 5.0))
        assertNull(FalconCorrectionIndex(trace.copy(segments = listOf(listOf(FalconPoint(31.0, 121.0))))).correction(time, raw, 5.0))
        assertNull(FalconCorrectionIndex(trace).correction(time + 2000, RoutePosition(32.0, 121.0), 5.0))
        assertNull(FalconCorrectionIndex(trace).correction(time, raw, Double.NaN))
    }

    @Test fun persistedCorrectionsKeepRawRadioSnapshotAndCoordinates() {
        val metadata = FalconCorrectionIndex(trace).apply(metadata(), time + 2100, raw, 5.0, false)!!
        val snapshot = SnapshotJson.toJson(com.nvmex.networkhelper.model.network.NetworkPanelUiState(band = "41", ci = "1234"))
        val saved = SnapshotJson.withMapMatch(snapshot, metadata)
        assertEquals("41", SnapshotJson.fromJson(saved).band)
        assertEquals("1234", SnapshotJson.fromJson(saved).ci)
        assertEquals(31.0, JsonParser.parseString(SnapshotJson.mapMatch(saved)).asJsonObject["raw_wgs84_lat"].asDouble, 0.0)
        assertNotNull(falconRecordedPosition(SnapshotJson.mapMatch(saved)))
    }
}
