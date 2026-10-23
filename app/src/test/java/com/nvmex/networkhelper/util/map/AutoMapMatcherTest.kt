package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.ui.settings.AppSettings
import com.nvmex.networkhelper.util.map.matching.*
import com.nvmex.networkhelper.util.records.CsvTable
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AutoMapMatcherTest {
    private fun realNetwork(): RouteNetwork = RouteNetwork.parse(File("src/main/assets/map_matching/beijing_sample.geojson").readText())
    // Artificial geometry ONLY for adversarial unit cases; the end-to-end cases below use original OSM nodes.
    private fun line(id: String = "road", type: RouteType = RouteType.ROAD, lat: Double = 31.0, lng: Double = 121.0,
        endLat: Double = lat, endLng: Double = lng + .02, start: String = id + "a", end: String = id + "b") =
        MatchRoute(id, id, type, listOf(RoutePosition(lat, lng), RoutePosition(endLat, endLng)), start, end)
    private fun fix(route: MatchRoute, i: Int, source: MatchSource = MatchSource.GNSS, offset: Double = 0.0): MatchFix {
        val p = route.at(40.0 + i * 10)
        return MatchFix(p.position.copy(lat = p.position.lat + offset / 111320), 1000L + i * 1000,
            source, 10.0, p.bearing, 5.0, true)
    }
    private fun confirmed(matcher: AutoMapMatcher, route: MatchRoute) = (0..4).map { matcher.match(fix(route, it)) }.last()

    @Test fun defaultOffAndRawSignalSnapshotRemainUnchanged() {
        assertFalse(AppSettings().enableMapMatching)
        val raw = SnapshotJson.toJson(NetworkPanelUiState(subId = 22, ci = "12345", rsrp = "-95"))
        assertEquals(raw, SnapshotJson.withMapMatch(raw, null))
        val route = line(); val m = AutoMapMatcher(RouteNetwork(listOf(route), "test"))
        val result = confirmed(m, route)
        val enriched = SnapshotJson.withMapMatch(raw, result.toJson())
        assertEquals(SnapshotJson.fromJson(raw), SnapshotJson.fromJson(enriched))
        assertEquals(result.toJson(), SnapshotJson.mapMatch(enriched))
    }
    @Test fun realRoadAndRealRailReplayConfirmAdvanceAndRevalidateGnss() {
        val network = realNetwork()
        assertTrue(network.routes.size in 15..20)
        listOf(RouteType.ROAD, RouteType.RAIL).forEach { type ->
            val report = MapMatchReplay.run(network, type)
            assertTrue("$type ${report.route}: ${report.results.joinToString { it.status }}", report.passed)
            assertTrue(report.results.filter { it.corrected != null }.all { it.type == type })
            assertTrue(report.results.filter { it.fix.source == MatchSource.IMU }.all { it.degraded })
            println("REAL $type: ${report.route}; GNSS=${report.gnssMatches}, IMU=${report.imuMatches}, recovery=${report.recoveryMatches}, progress=${report.movementMeters}m")
        }
    }
    @Test fun tunnelAdvancesBySpeedEvenWhenRawCoordinateIsStationaryAndNeverMutatesRawFix() {
        val route = line(); val m = AutoMapMatcher(RouteNetwork(listOf(route), "test"))
        val first = confirmed(m, route)
        val raw = first.fix.position
        val results = (5..9).map { i -> m.match(fix(route, i, MatchSource.IMU).copy(position = raw)) }
        assertTrue(results.all { it.corrected != null && it.fix.position == raw })
        assertEquals(50.0, routeDistance(first.corrected!!, results.last().corrected!!), 0.3)
    }
    @Test fun equallyPlausibleParallelRoadAndRailRemainUnknown() {
        val road = line(); val rail = line("rail", RouteType.RAIL, lat = 31.00005)
        val m = AutoMapMatcher(RouteNetwork(listOf(road, rail), "parallel synthetic"))
        val results = (0..5).map { m.match(fix(road, it, offset = 2.5)) }
        assertTrue(results.all { it.type == RouteType.UNKNOWN && it.corrected == null })
    }
    @Test fun perpendicularRailCrossingDoesNotStealRoadLockOrCreateTopology() {
        val road = line(); val rail = line("rail", RouteType.RAIL, lat = 30.99, lng = 121.001, endLat = 31.01, endLng = 121.001)
        val net = RouteNetwork(listOf(road, rail), "crossing synthetic")
        assertTrue(net.connected(road, 1).isEmpty())
        val m = AutoMapMatcher(net); assertEquals(RouteType.ROAD, confirmed(m, road).type)
        val results = (5..9).map { m.match(fix(road, it, MatchSource.IMU)) }
        assertTrue(results.all { it.type == RouteType.ROAD && it.routeId == road.id })
    }
    @Test fun branchesStopPredictionAndUniqueSharedNodeContinuationWorks() {
        val a = line(endLng = 121.001, end = "joint")
        val b = line("b", lng = 121.001, endLng = 121.01, start = "joint")
        val branch = line("branch", lng = 121.001, endLat = 31.001, endLng = 121.01, start = "joint")
        fun run(network: RouteNetwork): MatchResult {
            val m = AutoMapMatcher(network); confirmed(m, a)
            return (5..9).map { m.match(fix(a, it, MatchSource.IMU).copy(position = b.at((40.0 + it * 10 - a.length).coerceAtLeast(0.0)).position)) }.last()
        }
        assertEquals("b", run(RouteNetwork(listOf(a, b), "connected synthetic")).routeId)
        val refused = run(RouteNetwork(listOf(a, b, branch), "fork synthetic"))
        assertNull(refused.corrected)
    }
    @Test fun badSpeedHeadingDistanceNetworkAndStaleObservationsFallBack() {
        val route = line()
        val cases = listOf<(MatchFix) -> MatchFix>(
            { it.copy(speedReliable = false) }, { it.copy(speedMps = Double.NaN) },
            { it.copy(bearing = 270.0) }, { it.copy(position = RoutePosition(32.0, 121.0)) },
            { it.copy(accuracy = 180.0) }, { it.copy(elapsedMs = 100_000) })
        cases.forEach { alter ->
            val m = AutoMapMatcher(RouteNetwork(listOf(route), "test")); confirmed(m, route)
            val result = m.match(alter(fix(route, 5, MatchSource.IMU)))
            assertNull(result.status, result.corrected); assertTrue(result.degraded)
        }
        val m = AutoMapMatcher(RouteNetwork(emptyList(), "empty"))
        assertNull(confirmed(m, route).corrected)
    }
    @Test fun resetAndGnssRecoveryOffRouteDropOldConstraint() {
        val route = line(); val m = AutoMapMatcher(RouteNetwork(listOf(route), "test"))
        confirmed(m, route); m.reset()
        assertNull(m.match(fix(route, 5, MatchSource.IMU)).corrected)
        confirmed(m, route)
        assertNotNull(m.match(fix(route, 5, MatchSource.IMU)).corrected)
        assertNull(m.match(fix(route, 6, offset = 300.0)).corrected)
        assertNull(m.match(fix(route, 7, MatchSource.IMU)).corrected)
    }
    @Test fun matchedCsvKeepsBothSimRawCoordinatesSignalAndMetadataOnRoundTrip() {
        val route = line(); val m = AutoMapMatcher(RouteNetwork(listOf(route), "test"))
        val result = confirmed(m, route); val raw = result.fix.position
        val gcj = Gcj02.wgs84ToGcj02(raw.lat, raw.lng)
        val points = (0..1).map { slot -> DrivePointEntity(sessionId = "original", ts = 1720000000000L, simSlot = slot,
            lat = gcj.first, lng = gcj.second, rsrpDbm = -95, isHandover = true, cellKey = "cell$slot",
            snapshotJson = SnapshotJson.withMapMatch(SnapshotJson.toJson(NetworkPanelUiState(subId = slot + 10, ci = "1234", rsrp = "-95")), result.toJson()),
            rawLat = raw.lat, rawLng = raw.lng, rawCoordType = "WGS84") }
        val csv = DriveCsv.encode(points, emptyList(), "test")
        val columns = CsvTable.decode(csv)
        assertEquals("ROAD", columns.first()["match_type"])
        assertEquals(result.corrected!!.lat, columns.first()["matched_wgs84_latitude"]!!.toDouble(), 0.0)
        val restored = DriveCsv.decode(csv).single().points
        points.zip(restored).forEach { (before, after) -> assertEquals(before, after.copy(sessionId = before.sessionId)) }
    }
    @Test fun geoJsonRejectsUnboundedBadOrWrongCoordinateSystemData() {
        val source = File("src/main/assets/map_matching/beijing_sample.geojson").readText()
        assertTrue(runCatching { RouteNetwork.parse(source.replace("WGS84", "GCJ02")) }.isFailure)
        assertTrue(runCatching { RouteNetwork.parse("{}") }.isFailure)
        assertTrue(runCatching { RouteNetwork.parse("x".repeat(RouteNetwork.MAX_BYTES + 1)) }.isFailure)
    }
}
