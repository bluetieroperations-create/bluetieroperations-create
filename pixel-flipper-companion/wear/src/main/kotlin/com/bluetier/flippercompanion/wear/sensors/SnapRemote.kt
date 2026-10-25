package com.bluetier.flippercompanion.wear.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.bluetier.flippercompanion.shared.Bearing
import com.bluetier.flippercompanion.shared.DeviceBearing
import com.bluetier.flippercompanion.shared.PointingAxis
import com.bluetier.flippercompanion.shared.SnapPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * The gesture itself: snap with the hand you are pointing with, and the watch
 * reports where that hand was aimed.
 *
 * Three streams at once. Accelerometer and gyroscope feed [TransientDetector],
 * tuned on measured snaps. The rotation vector feeds [BearingRing], so when a
 * snap is accepted the aim can be read from *before* it — detection happens at
 * the end of a transient, by which point the snap has already jolted the arm.
 *
 * Nothing here knows what a target is. The watch reports a direction and the
 * phone decides what it meant, which keeps the registry in one place and the
 * watch ignorant of the house.
 */
class SnapRemote(
    context: Context,
    private val config: TransientConfig = TransientConfig(),
    initialAxis: PointingAxis = PointingAxis.TOP_EDGE,
) {

    /** Live view for the watch UI. */
    data class State(
        val running: Boolean = false,
        val bearing: Bearing? = null,
        val accelHz: Double? = null,
        val axis: PointingAxis = PointingAxis.TOP_EDGE,
        val snapsDetected: Int = 0,
        val snapsRejected: Int = 0,
        /** Why the last snap was not sent, when it was not. */
        val lastSkipped: String? = null,
        val sensorsAvailable: Boolean = true,
    )

    /**
     * Which way the arm points, changeable while running.
     *
     * A watch's forward direction along the forearm depends on the wrist and
     * the band, so this is chosen by looking at the live readout rather than
     * assumed.
     */
    @Volatile
    var axis: PointingAxis = initialAxis
        set(value) {
            field = value
            _state.value = _state.value.copy(axis = value)
            bearingRing.clear()
        }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val detector = TransientDetector(config)
    private val accelRate = SampleRateEstimator()
    private val bearingRing = BearingRing()

    private val _state = MutableStateFlow(
        State(
            axis = initialAxis,
            sensorsAvailable = accelerometer != null && rotationVector != null,
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** Accepted snaps with an aim attached, for the caller to send to the phone. */
    private val _snaps = MutableSharedFlow<SnapPoint>(replay = 0, extraBufferCapacity = 8)
    val snaps: SharedFlow<SnapPoint> = _snaps.asSharedFlow()

    @Volatile
    private var lastGyroMagnitude = 0.0

    private val matrix = FloatArray(9)
    private val asDoubles = DoubleArray(9)

    /** Display updates are rate-limited; the ring still gets every sample. */
    private var lastUiEmitMillis = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> lastGyroMagnitude = magnitude(event.values)

                Sensor.TYPE_ROTATION_VECTOR -> onRotation(event)

                Sensor.TYPE_ACCELEROMETER -> {
                    accelRate.record(event.timestamp)
                    onAccel(event.timestamp, magnitude(event.values), lastGyroMagnitude)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun onRotation(event: SensorEvent) {
        val bearing = runCatching {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            for (i in matrix.indices) asDoubles[i] = matrix[i].toDouble()
            DeviceBearing.fromRotationMatrix(asDoubles, axis)
        }.getOrNull() ?: return

        // Every sample goes into the ring: the aim before a snap is only as
        // good as what was recorded, and nobody is watching that part.
        bearingRing.add(event.timestamp, bearing)

        val now = System.currentTimeMillis()
        if (now - lastUiEmitMillis >= UI_INTERVAL_MS) {
            lastUiEmitMillis = now
            _state.value = _state.value.copy(bearing = bearing, accelHz = accelRate.stats()?.hz)
        }
    }

    private fun onAccel(timestampNanos: Long, accel: Double, gyro: Double) {
        val event = detector.onSample(timestampNanos, accel, gyro) ?: return

        if (!event.accepted) {
            _state.value = _state.value.copy(snapsRejected = _state.value.snapsRejected + 1)
            return
        }

        // A snap is only as useful as the aim behind it. With no orientation
        // recorded for that moment there is nothing to send, and firing on a
        // guess is worse than not firing.
        val aim = bearingRing.aimBefore(event.peakNanos)
        if (aim == null) {
            _state.value = _state.value.copy(
                snapsDetected = _state.value.snapsDetected + 1,
                lastSkipped = "no aim recorded",
            )
            return
        }

        _state.value = _state.value.copy(
            snapsDetected = _state.value.snapsDetected + 1,
            lastSkipped = null,
        )

        _snaps.tryEmit(
            SnapPoint(
                bearing = aim.bearing,
                snapNanos = event.peakNanos,
                peakDeviation = event.peakDeviation,
                peakGyro = event.peakGyro,
                sampleCount = event.sampleCount,
                accelHz = accelRate.stats()?.hz ?: 0.0,
                aimSpreadDegrees = aim.spreadDegrees,
            ),
        )
    }

    fun start() {
        val accel = accelerometer ?: return
        val rotation = rotationVector ?: return
        if (_state.value.running) return

        detector.reset()
        accelRate.reset()
        bearingRing.clear()

        // The IMU runs flat out because a snap is a ~45 ms event; orientation
        // does not need to, and the two have very different jobs.
        sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_FASTEST)
        gyroscope?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        sensorManager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME)

        _state.value = _state.value.copy(
            running = true,
            snapsDetected = 0,
            snapsRejected = 0,
            lastSkipped = null,
        )
    }

    fun stop() {
        if (!_state.value.running) return
        sensorManager.unregisterListener(listener)
        _state.value = _state.value.copy(running = false)
    }

    private fun magnitude(values: FloatArray): Double {
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        return sqrt(x * x + y * y + z * z)
    }

    private companion object {
        const val UI_INTERVAL_MS = 100L
    }
}
