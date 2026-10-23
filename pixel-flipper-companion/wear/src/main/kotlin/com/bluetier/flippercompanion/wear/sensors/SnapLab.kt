package com.bluetier.flippercompanion.wear.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.bluetier.flippercompanion.shared.SnapTrace
import com.bluetier.flippercompanion.shared.TraceSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * The watch-side snap capture session: the instrument for the sample-rate spike.
 *
 * Runs the accelerometer and gyroscope as fast as the platform will allow,
 * reports the rate actually achieved, and captures the waveform of every
 * acceleration transient it sees — accepted *and* rejected — for export to the
 * phone.
 *
 * Two things worth knowing about the rate it reports:
 *
 * 1. `SENSOR_DELAY_FASTEST` is a hint. What arrives is whatever the HAL feels
 *    like delivering, which is why [SampleRateEstimator] measures it.
 * 2. Android 12 (API 31) caps third-party sensor rates at 200 Hz unless the app
 *    holds `HIGH_SAMPLING_RATE_SENSORS`. That permission is declared in the
 *    manifest; without it a reading of "200 Hz" would be the permission
 *    talking, not the hardware.
 *
 * Tied to the UI lifecycle rather than a foreground service, because capture is
 * something you sit and do deliberately while watching the numbers.
 */
class SnapLab(
    context: Context,
    private val config: TransientConfig = TransientConfig(),
    /** Waveform captured before the transient's peak. */
    private val preRollMs: Double = 60.0,
    /** Waveform captured after it. */
    private val postRollMs: Double = 100.0,
) {

    /** Live view of the capture session, for the watch UI. */
    data class State(
        val running: Boolean = false,
        val accelRate: RateStats? = null,
        val gyroRate: RateStats? = null,
        val accelAvailable: Boolean = true,
        val gyroAvailable: Boolean = true,
        val acceptedCount: Int = 0,
        val rejectedCount: Int = 0,
        val lastEvent: TransientEvent? = null,
    )

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val accelRate = SampleRateEstimator()
    private val gyroRate = SampleRateEstimator()
    private val detector = TransientDetector(config)
    private val ring = SampleRing(capacity = 512)

    private val _state = MutableStateFlow(
        State(
            accelAvailable = accelerometer != null,
            gyroAvailable = gyroscope != null,
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Completed traces, for the caller to ship to the phone. Buffered rather
     * than conflated so a burst of snaps is not silently thinned out.
     */
    private val _traces = MutableSharedFlow<SnapTrace>(replay = 0, extraBufferCapacity = 32)
    val traces: SharedFlow<SnapTrace> = _traces.asSharedFlow()

    /**
     * Most recent gyroscope magnitude, paired with each accelerometer sample.
     *
     * The two sensors deliver on independent callbacks and need not be in step,
     * so each accelerometer sample is fused with the latest gyroscope reading
     * available. At these rates the pairing error is well under a millisecond,
     * which is plenty for a rotation gate that only has to tell a snap from a
     * wrist flick.
     */
    @Volatile
    private var lastGyroMagnitude = 0.0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    gyroRate.record(event.timestamp)
                    lastGyroMagnitude = magnitude(event.values)
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    accelRate.record(event.timestamp)
                    onAccelSample(event.timestamp, magnitude(event.values), lastGyroMagnitude)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun onAccelSample(timestampNanos: Long, accel: Double, gyro: Double) {
        ring.add(ImuSample(timestampNanos, accel, gyro))

        val event = detector.onSample(timestampNanos, accel, gyro)
        val current = _state.value

        _state.value = current.copy(
            accelRate = accelRate.stats(),
            gyroRate = gyroRate.stats(),
            acceptedCount = current.acceptedCount + if (event?.accepted == true) 1 else 0,
            rejectedCount = current.rejectedCount + if (event != null && !event.accepted) 1 else 0,
            lastEvent = event ?: current.lastEvent,
        )

        if (event != null) _traces.tryEmit(buildTrace(event))
    }

    /**
     * Packages a detected transient with the waveform around its peak.
     *
     * Sample offsets are relative to the transient's *start*, so the pre-roll
     * carries negative offsets. That keeps the zero point at the thing the
     * detector actually triggered on.
     */
    private fun buildTrace(event: TransientEvent): SnapTrace {
        val window = ring.window(event.peakNanos, preRollMs, postRollMs)
        return SnapTrace(
            startNanos = event.startNanos,
            peakOffsetMicros = (event.peakNanos - event.startNanos) / 1_000L,
            peakDeviation = event.peakDeviation,
            baseline = event.baseline,
            peakGyro = event.peakGyro,
            riseTimeMs = event.riseTimeMs,
            durationMs = event.durationMs,
            sampleCount = event.sampleCount,
            accepted = event.accepted,
            rejections = event.rejections.map { it.name },
            accelHz = accelRate.stats()?.hz ?: 0.0,
            samples = window.map {
                TraceSample(
                    offsetMicros = (it.timestampNanos - event.startNanos) / 1_000L,
                    accelMagnitude = it.accelMagnitude,
                    gyroMagnitude = it.gyroMagnitude,
                )
            },
        )
    }

    /** No-op when already running, or when the watch has no accelerometer. */
    fun start() {
        if (_state.value.running || accelerometer == null) return

        accelRate.reset()
        gyroRate.reset()
        detector.reset()
        ring.clear()

        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        gyroscope?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        _state.value = _state.value.copy(
            running = true,
            acceptedCount = 0,
            rejectedCount = 0,
            lastEvent = null,
            accelRate = null,
            gyroRate = null,
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
}
