package com.ncthanhngo.pollmaster.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Reads the device heading (0-359 degrees, 0 = north) from the rotation-vector sensor. */
class CompassReader(private val context: Context) {
    private val sensors get() = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val isAvailable: Boolean get() = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    suspend fun read(): Int? {
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                var count = 0
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        // Let the filter settle for a few samples before reporting.
                        if (++count < SETTLE_SAMPLES || !cont.isActive) return
                        sensors.unregisterListener(this)
                        cont.resume(heading(event.values))
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                cont.invokeOnCancellation { sensors.unregisterListener(listener) }
                sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun heading(rotationVector: FloatArray): Int {
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, rotationVector)
        SensorManager.getOrientation(matrix, orientation)
        val degrees = Math.toDegrees(orientation[0].toDouble())
        return ((degrees + 360.0) % 360.0).roundToInt() % 360
    }

    private companion object {
        const val SETTLE_SAMPLES = 5
    }
}
