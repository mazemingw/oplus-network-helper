package com.nvmex.networkhelper.util.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DeadReckoningEngineTest {
    @Test fun readinessTracksCalibrationDisableAndSensorAvailabilityWithoutOwningGps() {
        val drive = Drive()
        assertFalse(drive.engine.isReady(drive.at))
        drive.warmup()
        assertTrue(drive.engine.isReady(drive.at))
        assertFalse(drive.engine.isReady(drive.at + 1500))
        drive.engine.enabled = false
        assertFalse(drive.engine.isReady(drive.at))
        drive.step()
        assertTrue(drive.gps())
        assertEquals(DeadReckoningEngine.Mode.GPS, drive.engine.reading(drive.at).mode)
    }
    private class Drive(val engine: DeadReckoningEngine = DeadReckoningEngine().apply { enabled = true }) {
        var at = 0L
        var speed = 10.0
        var course = 0.0
        var east = 0.0
        var north = 0.0
        val latitude get() = 31.0 + Math.toDegrees(north / 6371008.8)
        val longitude get() = 121.0 + Math.toDegrees(east / (6371008.8 * cos(Math.toRadians(31.0))))
        init { gps() }
        fun gps() = engine.onGps(at, latitude, longitude, speed, (course + 360) % 360, 5.0)
        fun step(acceleration: Double = 0.0, turn: Double = 0.0, gps: Boolean = true,
                 eastBias: Double = 0.04, northBias: Double = -0.03, gyroBias: Double = 0.1) {
            val dt = 0.05
            val mid = Math.toRadians(course + turn * dt / 2)
            val nextSpeed = (speed + acceleration * dt).coerceAtLeast(0.0)
            val averageSpeed = (speed + nextSpeed) / 2
            east += averageSpeed * dt * sin(mid)
            north += averageSpeed * dt * cos(mid)
            val eastAcceleration = acceleration * sin(mid) + averageSpeed * Math.toRadians(turn) * cos(mid)
            val northAcceleration = acceleration * cos(mid) - averageSpeed * Math.toRadians(turn) * sin(mid)
            course = (course + turn * dt + 360) % 360
            speed = nextSpeed
            at += 50
            engine.onMotion(at, eastAcceleration + eastBias, northAcceleration + northBias, turn + gyroBias)
            if (gps && at % 1000 == 0L) gps()
        }
        fun warmup(acceleration: Double = 0.0, turn: Double = 0.0) {
            repeat(120) { step(acceleration, turn) }
            assertEquals(DeadReckoningEngine.Calibration.READY, engine.calibration)
        }
        fun positionError(reading: DeadReckoningEngine.Reading) =
            driveDistanceMeters(latitude, longitude, reading.lat!!, reading.lng!!)
    }

    @Test fun automaticallyCalibratesWhileDrivingWithoutAnyManualOrStationaryStep() {
        val drive = Drive()
        drive.warmup()
        assertTrue(drive.speed > 2)
        repeat(100) { drive.step(gps = false) }
        val reading = drive.engine.reading(drive.at)
        assertEquals(DeadReckoningEngine.Mode.ESTIMATED, reading.mode)
        assertEquals(10.0, reading.speedMps!!, 0.3)
        assertTrue(drive.positionError(reading) < 2.0)
    }

    @Test fun gpsVelocityChangeSeparatesRealAccelerationAndCorneringFromBias() {
        val drive = Drive()
        drive.warmup(acceleration = 0.5, turn = 3.0)
        repeat(100) { drive.step(acceleration = 0.5, turn = 3.0, gps = false) }
        val reading = drive.engine.reading(drive.at)
        assertEquals(DeadReckoningEngine.Mode.ESTIMATED, reading.mode)
        assertEquals(drive.speed, reading.speedMps!!, 0.5)
        assertEquals(drive.course, reading.bearing!!, 1.0)
        assertTrue(drive.positionError(reading) < 3.0)
    }

    @Test fun followsRightHandTunnelBendImmediatelyAfterGpsLoss() {
        val drive = Drive()
        drive.warmup()
        repeat(80) { drive.step(turn = 11.25, gps = false) }
        val half = drive.engine.reading(drive.at)
        assertEquals(DeadReckoningEngine.Mode.ESTIMATED, half.mode)
        assertEquals(45.0, half.bearing!!, 1.0)
        repeat(80) { drive.step(turn = 11.25, gps = false) }
        val reading = drive.engine.reading(drive.at)
        assertEquals(90.0, reading.bearing!!, 1.5)
        assertTrue(reading.lng!! > 121.0004)
        assertTrue(drive.positionError(reading) < 3.0)
    }

    @Test fun followsLeftHandTunnelBendAcrossNorthAndChangesSpeed() {
        val drive = Drive()
        drive.warmup()
        repeat(160) { drive.step(acceleration = 0.4, turn = -11.25, gps = false) }
        val reading = drive.engine.reading(drive.at)
        assertEquals(270.0, reading.bearing!!, 1.5)
        assertEquals(drive.speed, reading.speedMps!!, 0.6)
        assertTrue(reading.lng!! < 121.0)
        assertTrue(drive.positionError(reading) < 4.0)
    }

    @Test fun brakingCanReachZeroAndNeverCreatesReverseSpeed() {
        val drive = Drive()
        drive.warmup()
        repeat(240) { drive.step(acceleration = if (drive.speed > 0.1) -1.5 else 0.0, gps = false) }
        val reading = drive.engine.reading(drive.at)
        assertEquals(DeadReckoningEngine.Mode.ESTIMATED, reading.mode)
        assertTrue(reading.speedMps!! in 0.0..0.5)
    }

    @Test fun delayedGpsReplaysOnlyTheUnconsumedImuTail() {
        val drive = Drive()
        drive.warmup()
        repeat(20) { drive.step(gps = false) }
        val gpsAt = drive.at
        val gpsLat = drive.latitude
        val gpsLng = drive.longitude
        repeat(20) { drive.step(gps = false) }
        assertTrue(drive.engine.onGps(gpsAt, gpsLat, gpsLng, 10.0, 0.0, 5.0))
        repeat(60) { drive.step(gps = false) }
        val reading = drive.engine.reading(drive.at)
        assertEquals(DeadReckoningEngine.Mode.ESTIMATED, reading.mode)
        assertTrue(drive.positionError(reading) < 2.0)
        assertTrue(drive.gps())
        val reset = drive.engine.reading(drive.at)
        assertEquals(DeadReckoningEngine.Mode.GPS, reset.mode)
        assertEquals(drive.latitude, reset.lat!!, 0.0000001)
    }

    @Test fun honorsExplicitOneMinuteSettingAndExpiresAfterSensorInterruption() {
        val drive = Drive()
        drive.engine.maxOutageMs = 60_000
        drive.warmup()
        repeat(1200) { drive.step(gps = false) }
        assertEquals(DeadReckoningEngine.Mode.ESTIMATED, drive.engine.reading(drive.at).mode)
        drive.step(gps = false)
        assertEquals(DeadReckoningEngine.Mode.NO_FIX, drive.engine.reading(drive.at).mode)
        val second = Drive()
        second.warmup()
        repeat(80) { second.step(gps = false) }
        assertEquals(DeadReckoningEngine.Mode.NO_FIX, second.engine.reading(second.at + 1500).mode)
        assertEquals(DeadReckoningEngine.Calibration.FIX_REQUIRED, second.engine.calibration)
    }

    @Test fun longTunnelEstimatesContinueBeyondOldDriftAndMinuteLimitsThenHonorConfiguredLimit() {
        val drive = Drive()
        drive.warmup()
        repeat(2400) { drive.step(gps = false) }
        val twoMinutes = drive.engine.reading(drive.at)
        assertEquals(DeadReckoningEngine.Mode.ESTIMATED, twoMinutes.mode)
        assertEquals(120_000L, twoMinutes.outageMillis)
        assertTrue(twoMinutes.uncertaintyMeters!! > 200)
        repeat(9600) { drive.step(gps = false) }
        assertEquals(DeadReckoningEngine.Mode.ESTIMATED, drive.engine.reading(drive.at).mode)
        drive.step(gps = false)
        assertEquals(DeadReckoningEngine.Mode.NO_FIX, drive.engine.reading(drive.at).mode)
        assertTrue(drive.gps())
        assertEquals(DeadReckoningEngine.Mode.GPS, drive.engine.reading(drive.at).mode)
    }

    @Test fun unreliableSensorsRequireFreshAutomaticAlignmentAndRecoverWithoutManualAction() {
        val drive = Drive()
        drive.warmup()
        drive.engine.sensorReliability(false)
        repeat(80) { drive.step(gps = false) }
        assertEquals(DeadReckoningEngine.Mode.NO_FIX, drive.engine.reading(drive.at).mode)
        drive.engine.sensorReliability(true)
        repeat(120) { drive.step() }
        assertEquals(DeadReckoningEngine.Calibration.READY, drive.engine.calibration)
    }

    @Test fun stationaryStartWaitsForObservableGpsHeadingButDoesNotRequireParking() {
        val drive = Drive().apply { speed = 0.0 }
        repeat(120) { drive.step() }
        assertNotEquals(DeadReckoningEngine.Calibration.READY, drive.engine.calibration)
        drive.speed = 10.0
        repeat(120) { drive.step() }
        assertEquals(DeadReckoningEngine.Calibration.READY, drive.engine.calibration)
    }

    @Test fun disabledMissingSensorsOrInvalidGpsNeverInventMotion() {
        val engine = DeadReckoningEngine()
        assertNull(engine.reading(0).speedMps)
        assertFalse(engine.onGps(1000, Double.NaN, 0.0, 1.0, 0.0, 5.0))
        assertFalse(engine.onGps(1000, 31.0, 121.0, 1.0, 0.0, 500.0))
        assertFalse(engine.onGps(1000, 31.0, 121.0, -1.0, 0.0, 5.0))
        assertTrue(engine.onGps(1000, 31.0, 121.0, 10.0, 0.0, 5.0))
        assertFalse(engine.onGps(1000, 31.0, 121.0, 10.0, 0.0, 5.0))
        assertEquals(DeadReckoningEngine.Mode.GPS, engine.reading(1000).mode)
        assertEquals(DeadReckoningEngine.Mode.NO_FIX, engine.reading(5000).mode)
        engine.enabled = true
        engine.onGps(6000, 31.0004, 121.0, 10.0, 0.0, 5.0)
        assertEquals(DeadReckoningEngine.Mode.NO_FIX, engine.reading(10000).mode)
    }
}
