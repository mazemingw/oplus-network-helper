package com.nvmex.networkhelper.util.map

import com.google.gson.JsonParser
import com.nvmex.networkhelper.util.map.matching.*
import org.junit.Assert.*
import org.junit.Test

class FalconTraceTest {
    private val time = 1720000000000L
    private val raw = RoutePosition(31.1, 121.1)
    @Test fun delayedAndUntimestampedCloudEndpointsNeverReplaceCurrentFix() {
        val trace = FalconTrace(1, 2, 3, listOf(listOf(FalconPoint(31.1, 121.1, time - 10000), FalconPoint(31.1, 121.1))))
        assertNull(trace.correctionFor(time, raw, 10.0))
        assertNull(trace.correctionFor(0, raw, 10.0))
    }
    @Test fun onlyCloseTimeAlignedCoordinatesCanBeApplied() {
        val aligned = FalconPoint(31.10001, 121.10001, time - 500)
        val trace = FalconTrace(1, 2, 3, listOf(listOf(aligned, FalconPoint(32.0, 121.0, time))))
        assertEquals(aligned, trace.copy(segments = listOf(listOf(aligned))).correctionFor(time, raw, 10.0))
        assertNull(trace.copy(segments = listOf(listOf(FalconPoint(32.0, 121.0, time)))).correctionFor(time, raw, 10.0))
        assertFalse(FalconPoint(Double.NaN, 121.1).valid)
        assertFalse(FalconPoint(91.0, 121.1).valid)
    }
    @Test fun recordingMetadataKeepsTrackIdsWithoutInventingCorrectedCoordinates() {
        val fix = MatchFix(raw, 1000, MatchSource.GNSS, 15.0, 90.0, 10.0, true, time)
        val metadata = JsonParser.parseString(FalconTrace(1, 2, 3, emptyList()).metadata(fix)).asJsonObject
        assertEquals("AMAP_FALCON", metadata["provider"].asString)
        assertEquals(3L, metadata["trid"].asLong)
        assertEquals("GNSS", metadata["source"].asString)
        assertEquals("UNKNOWN", metadata["type"].asString)
        assertFalse(metadata.has("matched_wgs84_lat"))
        val roundTrip = SnapshotJson.mapMatch(SnapshotJson.withMapMatch(SnapshotJson.toJson(com.nvmex.networkhelper.model.network.NetworkPanelUiState()), metadata.toString()))
        assertEquals(metadata, JsonParser.parseString(roundTrip))
    }
    @Test fun replayIncludesResumedTracksAndSplitsQueriesLongerThanOneDay() {
        val fix = MatchFix(raw, 1000, MatchSource.GNSS, 15.0, 90.0, 10.0, true, time)
        val first = FalconTrace(1, 2, 3, emptyList()).metadata(fix)
        val resumed = FalconTrace(1, 2, 4, emptyList()).metadata(fix)
        val points = listOf(time to first, (time + 90_000_000) to first,
            (time + 100_000_000) to resumed, (time + 100_003_000) to resumed, time to "bad-json")
        val slices = falconHistorySlices(points, time + 110_000_000)
        assertEquals(3, slices.size)
        assertEquals(setOf(3L, 4L), slices.map { it.trid }.toSet())
        assertTrue(slices.all { it.end - it.start < 86_400_000 })
        assertEquals(time + 100_003_000, slices.last().end)
    }
}
