package com.nvmex.networkhelper.util.map

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.*

class DriveMotionTrackerMathTest {
    private fun rotation(yaw: Double, tilt: Double): FloatArray {
        val a = Math.toRadians(yaw)
        val b = Math.toRadians(tilt)
        return floatArrayOf(cos(a).toFloat(), (-sin(a) * cos(b)).toFloat(), (sin(a) * sin(b)).toFloat(),
            sin(a).toFloat(), (cos(a) * cos(b)).toFloat(), (-cos(a) * sin(b)).toFloat(),
            0f, sin(b).toFloat(), cos(b).toFloat())
    }
    private fun deviceVector(matrix: FloatArray, east: Double, north: Double, up: Double) =
        (0..2).map { (matrix[it] * east + matrix[it + 3] * north + matrix[it + 6] * up).toFloat() }

    @Test fun landscapePortraitAndTiltedMountsPreserveHorizontalAccelerationAndTurnSign() {
        for (yaw in listOf(0.0, 90.0, 180.0, 270.0)) for (tilt in listOf(0.0, 45.0, 90.0)) {
            val matrix = rotation(yaw, tilt)
            // Raw accelerometer fallback includes gravity, which must not leak into east/north.
            val a = deviceVector(matrix, 1.2, -0.7, 9.81)
            val horizontal = driveHorizontalAcceleration(matrix, a[0], a[1], a[2], 0.0)
            assertEquals(1.2, horizontal.first, 0.00001)
            assertEquals(-0.7, horizontal.second, 0.00001)
            val rightTurn = deviceVector(matrix, 0.0, 0.0, -Math.toRadians(30.0))
            assertEquals(30.0, driveGyroYawRate(matrix, rightTurn[0], rightTurn[1], rightTurn[2]), 0.00001)
            val leftTurn = deviceVector(matrix, 0.0, 0.0, Math.toRadians(30.0))
            assertEquals(-30.0, driveGyroYawRate(matrix, leftTurn[0], leftTurn[1], leftTurn[2]), 0.00001)
        }
    }

    @Test fun gyroAttitudeWithArbitraryNorthIsAnchoredBeforeAccelerationProjection() {
        val absolute = rotation(25.0, 70.0)
        val relative = rotation(145.0, 70.0)
        val correction = driveFrameRotation(absolute, relative)
        assertEquals(-120.0, Math.toDegrees(correction), 0.00001)
        val a = deviceVector(absolute, 0.8, 1.5, 9.81)
        val horizontal = driveHorizontalAcceleration(relative, a[0], a[1], a[2], correction)
        assertEquals(0.8, horizontal.first, 0.00001)
        assertEquals(1.5, horizontal.second, 0.00001)
    }
}
