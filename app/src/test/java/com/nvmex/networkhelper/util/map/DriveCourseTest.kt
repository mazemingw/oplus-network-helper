package com.nvmex.networkhelper.util.map

import org.junit.Assert.*
import org.junit.Test

class DriveCourseTest {
    @Test fun stationaryCompassAndMovingCourseSwitchWithoutOverwritingTravelBearing() {
        val course = DriveCourse()
        course.update(1000, 31.0, 121.0, 3.0, 0.0, 230.0, true)
        assertFalse(course.isMoving)
        course.update(2000, 31.0, 121.0002, 3.0, 15.0, 90.0, true)
        assertTrue(course.isMoving)
        course.update(3000, 31.0, 121.0002, 3.0, 0.0, 250.0, true)
        assertFalse(course.isMoving)
        assertEquals(90.0, course.bearing!!, .01)
        course.update(4000, 31.0, 121.0004, 3.0, null, null, true)
        assertTrue(course.isMoving)
    }
    @Test fun travelCourseIgnoresStationaryAndUntrustedCompassBearings() {
        val course = DriveCourse()
        assertEquals(90.0, course.update(1000, 31.0, 121.0, 3.0, 15.0, 90.0, true)!!, .01)
        assertEquals(90.0, course.update(2000, 31.0, 121.0, 3.0, .1, 250.0, true)!!, .01)
        assertEquals(90.0, course.update(3000, 31.0, 121.0001, 100.0, 20.0, 180.0, false)!!, .01)
    }

    @Test fun displacementSuppliesHeadingOnlyWhenItExceedsFixUncertainty() {
        val course = DriveCourse()
        assertNull(course.update(1000, 31.0, 121.0, 3.0, null, null, true))
        assertNull(course.update(2000, 31.00001, 121.00001, 3.0, null, null, true))
        assertEquals(90.0, course.update(3000, 31.0, 121.0002, 3.0, null, null, true)!!, .01)
        assertEquals(2.0, driveAngleDelta(359.0, 1.0), .01)
        assertEquals(-2.0, driveAngleDelta(1.0, 359.0), .01)
    }

    @Test fun coarseNetworkLocationCannotSuppressCalibratedTunnelEstimates() {
        val estimate = DeadReckoningEngine.Reading(DeadReckoningEngine.Mode.ESTIMATED, 31.0, 121.0,
            uncertaintyMeters = 25.0, measuredAtMillis = 10_000)
        val selector = DrivePositionSelector()
        assertEquals("prefer_gps_or_inertial", selector.offerSdk(DriveSamplePosition(32.0, 121.0, "SDK", 10_000, 150f), 10_000, estimate))
        assertEquals("prefer_gps_or_inertial", selector.offerSdk(DriveSamplePosition(32.0, 121.0, "SDK", 10_001, 10f), 10_001, estimate))
        assertEquals("prefer_gps_or_inertial", selector.offerSdk(DriveSamplePosition(32.0, 121.0, "GPS", 10_002, 150f), 10_002, estimate))
        // A GPS fix rejected by the existing engine jump/time checks also cannot evict IMU.
        assertEquals("prefer_gps_or_inertial", selector.offerSdk(DriveSamplePosition(32.0, 121.0, "GPS", 10_003, 5f), 10_003, estimate, gpsValidated = false))
        assertNull(selector.offerSdk(DriveSamplePosition(31.0, 121.0, "GPS", 10_004, 5f), 10_004, estimate))
    }

    @Test fun liveBandLabelsStayRatSpecific() {
        assertEquals("B41", driveBandLabel("LTE", "41"))
        assertEquals("n41", driveBandLabel("NR", "N41"))
        assertEquals("—", driveBandLabel("NR", "-"))
    }
}
