package com.bluetier.flippercompanion.mobile.target

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much the compass can be trusted right now.
 *
 * This matters more here than in most apps. The whole pointing design rests on
 * absolute heading, and a magnetometer that has not been calibrated — or is
 * sitting next to a speaker magnet — will report a confident bearing that is
 * simply wrong. Surfacing it lets the UI ask for a figure-of-eight wave rather
 * than silently recording a bad target.
 */
enum class CompassTrust {
    /** Readings are meaningless; the magnetometer needs calibrating. */
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH;

    val usableForCalibration: Boolean get() = this == MEDIUM || this == HIGH

    companion object {
        fun fromSensorAccuracy(accuracy: Int): CompassTrust = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HIGH
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> LOW
            else -> UNRELIABLE
        }
    }
}

/**
 * Streams the phone's pointing direction from the fused rotation vector.
 *
 * Runs at `SENSOR_DELAY_GAME` rather than flat out: aiming at a television is
 * not a 400 Hz problem, and the sampler may be left running while someone
 * decides what to call a target.
 *
 * The conversion itself lives in [DeviceBearing], which is pure and tested;
 * this class only wires a sensor to it.
 */
class BearingSampler(
    context: Context,
    private val axis: PointingAxis = PointingAxis.TOP_EDGE,
) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationVector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = rotationVector != null

    private val _bearing = MutableStateFlow<Bearing?>(null)
    val bearing: StateFlow<Bearing?> = _bearing.asStateFlow()

    private val _trust = MutableStateFlow(CompassTrust.UNRELIABLE)
    val trust: StateFlow<CompassTrust> = _trust.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // Reused across events: this fires ~50 times a second and allocating two
    // arrays each time would be pure garbage.
    private val matrix = FloatArray(9)
    private val asDoubles = DoubleArray(9)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Some devices report a rotation vector of an unexpected length;
            // the framework throws rather than coping, so guard it.
            val converted = runCatching {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                for (i in matrix.indices) asDoubles[i] = matrix[i].toDouble()
                DeviceBearing.fromRotationMatrix(asDoubles, axis)
            }.getOrNull()

            if (converted != null) _bearing.value = converted
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            _trust.value = CompassTrust.fromSensorAccuracy(accuracy)
        }
    }

    fun start() {
        val sensor = rotationVector ?: return
        if (_running.value) return
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        _running.value = true
    }

    fun stop() {
        if (!_running.value) return
        sensorManager.unregisterListener(listener)
        _running.value = false
    }
}
