package com.nvmex.networkhelper.util.map

import org.junit.Assert.*
import org.junit.Test

class DriveCourseTest {
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
        val estimate = DeadReckoningEngine.Reading(DeadReckoningEngine.Mode.ESTIMATED, uncertaintyMeters = 25.0)
        assertTrue(drivePreferInertial(false, 150f, estimate))
        assertFalse(drivePreferInertial(false, 10f, estimate))
        assertFalse(drivePreferInertial(true, 150f, estimate))
        assertFalse(drivePreferInertial(false, 150f, DeadReckoningEngine.Reading(DeadReckoningEngine.Mode.NO_FIX)))
    }

    @Test fun liveBandLabelsStayRatSpecific() {
        assertEquals("B41", driveBandLabel("LTE", "41"))
        assertEquals("n41", driveBandLabel("NR", "N41"))
        assertEquals("—", driveBandLabel("NR", "-"))
    }
}
