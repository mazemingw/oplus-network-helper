package com.nvmex.networkhelper.util.map

import android.content.Context
import android.hardware.*
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Secondary IMU aid. Observes GPS fixes from the map SDK and never owns the location subscription. */
internal class DriveMotionTracker(context: Context,
    private val onReading: (DeadReckoningEngine.Reading) -> Unit
) : SensorEventListener {
    val engine = DeadReckoningEngine()
    private val appContext = context.applicationContext
    private val sensors = appContext.getSystemService(SensorManager::class.java)
    private val absoluteRotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotation = gameRotation ?: absoluteRotation
    private val acceleration = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val hasInertialSensors get() = absoluteRotation != null && acceleration != null && gyroscope != null
    private val matrix = FloatArray(9)
    private val absoluteMatrix = FloatArray(9)
    private var rotationAt = -1L
    private var absoluteAt = -1L
    private var gyroAt = -1L
    private var yawRate = 0.0
    private var frameRotation: Double? = null
    private var declination = 0.0
    private var sensorsRegistered = false
    private var running = false
    private var gyroReliable = true
    private var absoluteReliable = true
    private var gameReliable = true
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            onReading(engine.reading(SystemClock.elapsedRealtime()))
            handler.postDelayed(this, 500)
        }
    }
    fun start() {
        if (running) return
        running = true
        runCatching { updateSensors() }.onFailure { engine.sensorReliability(false) }
        handler.post(ticker)
    }

    fun setInertialEnabled(enabled: Boolean) {
        engine.enabled = enabled && hasInertialSensors
        if (running) runCatching { updateSensors() }.onFailure { engine.sensorReliability(false) }
    }

    private fun updateSensors() {
        val wanted = engine.enabled && hasInertialSensors
        if (wanted == sensorsRegistered) return
        sensors.unregisterListener(this)
        sensorsRegistered = false
        resetSensorFrame()
        if (!wanted) return
        val attitudeOk = sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME)
        val absoluteOk = gameRotation == null || sensors.registerListener(this, absoluteRotation, SensorManager.SENSOR_DELAY_GAME)
        val accelerationOk = sensors.registerListener(this, acceleration, SensorManager.SENSOR_DELAY_GAME)
        val gyroOk = sensors.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        sensorsRegistered = attitudeOk && absoluteOk && accelerationOk && gyroOk
        if (!sensorsRegistered) {
            sensors.unregisterListener(this)
            engine.sensorReliability(false)
        }
    }

    private fun resetSensorFrame() {
        rotationAt = -1
        absoluteAt = -1
        gyroAt = -1
        frameRotation = null
        gyroReliable = true
        absoluteReliable = true
        gameReliable = true
        engine.resetSensors()
        engine.sensorReliability(true)
    }

    fun acceptGps(fix: Location) {
        val now = SystemClock.elapsedRealtime()
        val at = fix.elapsedRealtimeNanos / 1_000_000
        if (at <= 0 || now - at !in 0..3000 || !fix.hasAccuracy()) return
        if (engine.onGps(at, fix.latitude, fix.longitude,
                if (fix.hasSpeed() && (!fix.hasSpeedAccuracy() || fix.speedAccuracyMetersPerSecond <= 1.5f)) fix.speed.toDouble() else null,
                if (fix.hasBearing() && (!fix.hasBearingAccuracy() || fix.bearingAccuracyDegrees <= 15)) fix.bearing.toDouble() else null,
                fix.accuracy.toDouble())) {
            declination = GeomagneticField(fix.latitude.toFloat(), fix.longitude.toFloat(), fix.altitude.toFloat(), fix.time).declination.toDouble()
            onReading(engine.reading(now))
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val at = event.timestamp / 1_000_000
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(absoluteMatrix, event.values)
            absoluteAt = at
            if (gameRotation == null) {
                absoluteMatrix.copyInto(matrix)
                rotationAt = at
                frameRotation = 0.0
            }
            alignFrames()
            return
        }
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            rotationAt = at
            alignFrames()
            return
        }
        if (rotationAt < 0 || at - rotationAt !in 0..200 || frameRotation == null) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            // Android gyro is counterclockwise about device axes. Navigation bearing is clockwise about Earth up.
            yawRate = driveGyroYawRate(matrix, x, y, z)
            gyroAt = at
            return
        }
        if (gyroAt < 0 || at - gyroAt !in 0..200) return
        // Raw accelerometer gravity maps to Earth up, leaving the horizontal components gravity-free.
        val angle = frameRotation!! - Math.toRadians(declination)
        val (trueEast, trueNorth) = driveHorizontalAcceleration(matrix, x, y, z, angle)
        engine.onMotion(at, trueEast, trueNorth, yawRate)
    }

    private fun alignFrames() {
        if (gameRotation == null || frameRotation != null || absoluteAt < 0 || rotationAt < 0 ||
            kotlin.math.abs(rotationAt - absoluteAt) > 100 || !absoluteReliable || !gameReliable) return
        // Anchor the gyro/gravity frame once, then ignore magnetic yaw changes in tunnels.
        frameRotation = driveFrameRotation(absoluteMatrix, matrix)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val reliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
        when (sensor?.type) {
            Sensor.TYPE_GYROSCOPE -> gyroReliable = reliable
            Sensor.TYPE_GAME_ROTATION_VECTOR -> gameReliable = reliable
            Sensor.TYPE_ROTATION_VECTOR -> absoluteReliable = reliable
        }
        engine.sensorReliability(gyroReliable && if (gameRotation == null) absoluteReliable
            else gameReliable && (frameRotation != null || absoluteReliable))
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        sensors.unregisterListener(this)
        sensorsRegistered = false
        resetSensorFrame()
    }
}

internal fun driveGyroYawRate(matrix: FloatArray, x: Float, y: Float, z: Float): Double =
    Math.toDegrees(-(matrix[6] * x + matrix[7] * y + matrix[8] * z).toDouble())

internal fun driveHorizontalAcceleration(matrix: FloatArray, x: Float, y: Float, z: Float,
    frameRotation: Double): Pair<Double, Double> {
    val east = matrix[0] * x + matrix[1] * y + matrix[2] * z
    val north = matrix[3] * x + matrix[4] * y + matrix[5] * z
    return east * cos(frameRotation) - north * sin(frameRotation) to
        east * sin(frameRotation) + north * cos(frameRotation)
}

internal fun driveFrameRotation(absolute: FloatArray, relative: FloatArray): Double {
    val c = absolute[0] * relative[0] + absolute[1] * relative[1] + absolute[2] * relative[2]
    val s = absolute[3] * relative[0] + absolute[4] * relative[1] + absolute[5] * relative[2]
    return atan2(s.toDouble(), c.toDouble())
}
