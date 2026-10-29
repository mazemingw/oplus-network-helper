package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.records.CsvTable
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

/** Virtual-clock replay of the production selector, sampling loop, dual-SIM batch and write queue. */
class DriveTunnelPipelineTest {
    private class Rig {
        val engine = DeadReckoningEngine().apply { enabled = true }
        val selector = DrivePositionSelector()
        val batch = DriveSamplingBatch()
        var now = 1000L
        private var meters = 0.0
        val lat get() = 31.0
        val lng get() = 121.0 + Math.toDegrees(meters / (6371008.8 * cos(Math.toRadians(lat))))
        val snapshots = mapOf(
            0 to NetworkPanelUiState(subId = 1, cellType = "LTE", band = "B3", pci = "10", arfcn = "1650", rsrp = "-90"),
            1 to NetworkPanelUiState(subId = 2, cellType = "NR", band = "N41", pci = "20", arfcn = "504990", ssRsrp = "-80")
        )
        init { gps() }
        fun gps() {
            assertTrue(engine.onGps(now, lat, lng, 12.0, 90.0, 5.0))
            val gcj = Gcj02.wgs84ToGcj02(lat, lng)
            assertNull(selector.offerSdk(DriveSamplePosition(gcj.first, gcj.second, "GPS", now, 5f,
                lat, lng, "WGS84"), now, engine.reading(now)))
        }
        fun advance(ms: Long, gps: Boolean = false, sensors: Boolean = true) {
            repeat((ms / 50).toInt()) {
                now += 50
                meters += 12 * .05
                // A newer attitude/gyro callback may precede an older acceleration callback.
                if (sensors && driveSensorReferenceFresh(now, now + 10) && driveSensorReferenceFresh(now, now + 5))
                    engine.onMotion(now, .04, -.03, .1)
                if (gps && now % 1000 == 0L) gps()
            }
        }
        fun warmup() {
            advance(8000, gps = true)
            assertTrue("Moving auto-calibration: ${engine.diagnostic(now)}", engine.isReady(now))
        }
        fun sample(): List<DrivePoint> = batch.sample(1_700_000_000_000L + now, snapshots,
            selector.select(now, engine.reading(now), engine.enabled))
    }

    @Test fun movingGpsThenTunnelKeepsDualSimWritesAt30_60_120_300_600Seconds() = runBlocking {
        val rig = Rig()
        rig.warmup()
        val start = rig.now
        val databaseRows = mutableListOf<DrivePointEntity>()
        val writeScope = CoroutineScope(SupervisorJob())
        val writtenAt = mutableMapOf<Long, List<DrivePoint>>()
        val queue = DrivePointWriteQueue<DrivePoint>(writeScope) { points, sid ->
            databaseRows.addAll(points.map { it.toDriveEntity(sid) })
        }
        try {
            runDriveSamplingLoop({ rig.now - start <= 603_000 }, { 3000 }, {
                val points = rig.sample()
                writtenAt[rig.now - start] = points
                queue.enqueue(points, "tunnel-session")
            }, { throw it }, { rig.advance(it) })
            assertNull(queue.awaitIdle())
            assertEquals(404, databaseRows.size)
            for (seconds in listOf(30, 60, 120, 300, 600)) {
                val points = writtenAt.getValue(seconds * 1000L)
                assertEquals(listOf(0, 1), points.map { it.simSlot })
                assertEquals(1, points.map { it.ts }.distinct().size)
                assertEquals(1, points.map { it.latLng }.distinct().size)
                assertTrue("$seconds s", points.all { it.estimated && it.positionSource == "INERTIAL" && it.latLng != null })
                assertTrue(driveDistanceMeters(31.0, 121.0, points[0].rawLat!!, points[0].rawLng!!) > seconds * 10)
            }
            assertTrue(writtenAt.getValue(120_000).first().accuracyMeters!! > 200f)
            assertTrue(writtenAt.getValue(603_000).all { it.latLng == null && it.positionSource == "NONE" })
            assertTrue(databaseRows.all { it.sessionId == "tunnel-session" })

            // The real persistence adapter and history/filter/CSV paths retain radio-only samples.
            val history = DriveHistory(databaseRows.mapNotNull { it.toDrivePointOrNull() })
            assertEquals(202, history.pointsBySlot.getValue(1).size)
            val filtered = history.filteredBy(DriveBandFilter(nr = setOf(41)))
            assertEquals(setOf(1), filtered.pointsBySlot.keys)
            val nr = filtered.pointsBySlot.getValue(1)
            val fixes = nr.map { DriveTrackFix(it.ts, it.latLng?.latitude, it.latLng?.longitude, it.segmentStart) }
            assertTrue(driveTrackLength(fixes) > 6000)
            assertTrue(driveTrackSegments(fixes).flatten().all { nr[it].latLng != null })
            val dots = nr.mapIndexedNotNull { i, p -> p.latLng?.let { DriveDot(i, it.latitude, it.longitude, p.isHandover) } }
            assertTrue(selectDriveDots(dots, 17f, true, 1f).isNotEmpty())
            val imported = DriveCsv.decode(DriveCsv.encode(databaseRows, emptyList(), "")).single().points
            assertEquals(databaseRows.size, imported.size)
            assertEquals(databaseRows.map { it.positionSource }, imported.map { it.positionSource })
            assertEquals(databaseRows.map { it.lat }, imported.map { it.lat })
        } finally { writeScope.cancel() }
    }

    @Test fun sensorLossStillRecordsRadioAndGpsRecoveryReanchorsAndRecalibrates() {
        val rig = Rig(); rig.warmup()
        rig.advance(12_000)
        assertTrue(rig.sample().all { it.estimated })
        rig.advance(3000, sensors = false)
        assertTrue(rig.sample().all { it.latLng == null && it.snapshot.pci.isNotEmpty() })
        rig.advance(6000)
        assertTrue(rig.sample().all { it.latLng == null }) // Fresh sensors alone cannot invent a new anchor.
        rig.gps()
        val recovered = rig.sample()
        assertTrue(recovered.all { !it.estimated && it.positionSource == "GPS" && it.segmentStart })
        assertEquals(rig.lng, recovered.first().rawLng!!, .0000001)
        rig.advance(8000, gps = true)
        assertTrue(rig.engine.isReady(rig.now))
        rig.advance(6000)
        assertTrue(rig.sample().all { it.estimated })
    }

    @Test fun preciseNetworkAndCachedGpsCannotReplaceOrRepeatTunnelEstimates() {
        val rig = Rig(); rig.warmup(); rig.advance(30_000)
        val estimate = rig.engine.reading(rig.now)
        assertEquals("prefer_gps_or_inertial", rig.selector.offerSdk(
            DriveSamplePosition(35.0, 115.0, "SDK", rig.now, 1f), rig.now, estimate))
        val points = rig.sample()
        assertTrue(points.all { it.positionSource == "INERTIAL" })
        assertTrue(points.first().rawLng!! > 121.003)
        assertTrue(rig.sample().all { it.latLng == null }) // Same measurement is never stamped twice.
    }

    @Test fun gpsSamplingRequiresNewMeasurementAndDisabledSwitchKeepsSdkFallback() {
        val rig = Rig(); rig.warmup()
        assertTrue(rig.sample().all { it.positionSource == "GPS" })
        rig.advance(1000, sensors = false)
        assertTrue(rig.sample().all { it.latLng == null })
        rig.engine.enabled = false
        rig.advance(6000)
        assertTrue(rig.sample().all { it.latLng == null })
        assertNull(rig.selector.offerSdk(DriveSamplePosition(31.0, 121.0, "SDK", rig.now, 80f), rig.now, rig.engine.reading(rig.now)))
        assertTrue(rig.sample().all { it.positionSource == "SDK" && !it.estimated })
        rig.advance(1000)
        assertTrue(rig.sample().all { it.latLng == null })
    }

    @Test fun noPositionSamplesRoundTripWithoutCoordinatesAndBreakTrackGaps() {
        val rig = Rig()
        val points = rig.batch.sample(1000, rig.snapshots, DriveSamplePosition(reason = "no_sensors"))
        val rows = points.map { it.toDriveEntity("radio-only") }
        assertTrue(rows.all { it.lat == null && it.lng == null && it.rawLat == null && it.rawCoordType == null })
        val csv = DriveCsv.encode(rows, emptyList(), "")
        assertTrue(CsvTable.decode(csv).all { it["gcj02_latitude"] == "" && it["wgs84_latitude"] == "" && it["position_source"] == "NONE" })
        assertEquals(rows, DriveCsv.decode(csv).single().points.map { it.copy(sessionId = "radio-only") })
        val fixes = listOf(DriveTrackFix(1000, 31.0, 121.0), DriveTrackFix(4000, null, null), DriveTrackFix(7000, 31.0001, 121.0))
        assertEquals(0.0, driveTrackLength(fixes), 0.0)
        assertTrue(driveTrackSegments(fixes).isEmpty())
    }

    @Test fun samplingTimerContinuesThroughFailuresAndDoesNotSwallowCancellation() = runBlocking {
        var ticks = 0; var errors = 0; var waits = 0
        runDriveSamplingLoop({ ticks < 5 }, { 3000 }, { if (++ticks == 2) error("temporary failure") },
            { errors++ }, { assertEquals(3000L, it); waits++ })
        assertEquals(5, ticks); assertEquals(5, waits); assertEquals(1, errors)
        val result = runCatching { runDriveSamplingLoop({ true }, { 3000 }, { throw CancellationException() },
            { fail("cancellation must propagate") }, {}) }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun sensorStreamOrderingKeepsExistingTimeBoundAndRejectsMissingReferences() {
        assertTrue(driveSensorReferenceFresh(1000, 1010))
        assertTrue(driveSensorReferenceFresh(1000, 800))
        assertFalse(driveSensorReferenceFresh(1000, 1201))
        assertFalse(driveSensorReferenceFresh(1000, 799))
        assertFalse(driveSensorReferenceFresh(0, -1))
    }

    @Test fun sdkStalenessBadAccuracyAndDuplicateGpsHaveExplicitRefusals() {
        val selector = DrivePositionSelector()
        val none = DeadReckoningEngine.Reading(DeadReckoningEngine.Mode.NO_FIX, reason = "imu_not_calibrated")
        val gps = DriveSamplePosition(31.0, 121.0, "GPS", 1000, 5f)
        assertNull(selector.offerSdk(gps, 1000, none))
        assertEquals("duplicate_sdk_measurement", selector.offerSdk(gps, 3000, none))
        val expired = selector.select(5000, none, true)
        assertFalse(expired.located)
        assertTrue(expired.reason.startsWith("sdk_measurement_expired"))
        assertNull(selector.offerSdk(DriveSamplePosition(31.0, 121.0, "SDK", 6000, 150f), 6000, none))
        assertTrue(selector.select(6000, none, true).reason.startsWith("sdk_accuracy_exceeds_100m"))
        assertEquals("invalid_or_stale_sdk", selector.offerSdk(gps.copy(measuredAt = 6000, lat = Double.NaN), 6000, none))
        val rejectedGps = DrivePositionSelector()
        assertNull(rejectedGps.offerSdk(gps, 1000, none, gpsValidated = false))
        assertFalse(rejectedGps.select(1000, none, true).located)
        assertTrue(rejectedGps.select(1000, none, false).located) // Original SDK mode remains available.
    }
}
