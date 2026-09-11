package com.nvmex.networkhelper.util.signalradar


import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

//方向采集器
/**
 * 输出 0..360 的航向角（headingDeg）
 * 优先用 ROTATION_VECTOR（稳、抗抖）
 */
class HeadingTracker(context: Context) {

    private val sm = context.getSystemService(SensorManager::class.java)

    fun headingDegFlow(): Flow<Float> = callbackFlow {
        val rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rv == null) {
            trySend(0f)
            close()
            return@callbackFlow
        }

        val rot = FloatArray(9)
        val ori = FloatArray(3)

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // rotation vector -> rotation matrix -> orientation
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, ori)

                // ori[0] 是 azimuth（弧度）: -pi..pi
                val az = ori[0].toDouble()
                var deg = Math.toDegrees(az).toFloat()
                if (deg < 0) deg += 360f
                trySend(deg)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sm.registerListener(l, rv, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(l) }
    }
}
