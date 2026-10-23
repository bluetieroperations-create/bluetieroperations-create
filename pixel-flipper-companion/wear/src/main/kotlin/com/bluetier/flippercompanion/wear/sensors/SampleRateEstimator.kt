package com.bluetier.flippercompanion.wear.sensors

import kotlin.math.sqrt

/**
 * Achieved sample-rate statistics for one sensor stream.
 *
 * [hz] is derived from [meanIntervalMs] rather than from a sample count over
 * wall-clock time, so a stream that stalls and resumes reports the rate it
 * actually delivers while running instead of an average washed out by the gap.
 */
data class RateStats(
    val hz: Double,
    val meanIntervalMs: Double,
    val minIntervalMs: Double,
    val maxIntervalMs: Double,
    /** Standard deviation of the interval, i.e. how evenly samples arrive. */
    val jitterMs: Double,
    val intervalCount: Int,
)

/**
 * Measures the sample rate a sensor stream *actually* delivers, as opposed to
 * the rate requested from `SensorManager`.
 *
 * This exists because `SENSOR_DELAY_FASTEST` is a hint, not a contract: Wear OS
 * routinely delivers well under the hardware maximum, and a finger snap at the
 * wrist is a ~10-20 ms transient that needs roughly 200 Hz to catch reliably.
 * Knowing the real rate decides whether IMU-only snap detection is viable at
 * all, so it is measured rather than assumed.
 *
 * Keeps a sliding window of the most recent [windowSize] inter-sample intervals.
 * Pure and deterministic: timestamps are supplied by the caller, so this is
 * unit-testable without a device.
 */
class SampleRateEstimator(private val windowSize: Int = 256) {

    init {
        require(windowSize >= 2) { "windowSize must be >= 2, was $windowSize" }
    }

    private val intervalsMs = ArrayDeque<Double>(windowSize)
    private var lastTimestampNanos: Long? = null

    /**
     * Records one sample arrival. [timestampNanos] should be the sensor's own
     * event timestamp (monotonic), not wall-clock time.
     *
     * Non-monotonic or duplicate timestamps are ignored: some Wear OS sensor
     * HALs replay a timestamp across a batch delivery, and counting those as
     * zero-length intervals would report an absurdly high rate.
     */
    fun record(timestampNanos: Long) {
        val previous = lastTimestampNanos
        lastTimestampNanos = timestampNanos
        if (previous == null || timestampNanos <= previous) return

        intervalsMs.addLast((timestampNanos - previous) / 1_000_000.0)
        while (intervalsMs.size > windowSize) {
            intervalsMs.removeFirst()
        }
    }

    /** Null until at least one interval has been observed. */
    fun stats(): RateStats? {
        if (intervalsMs.isEmpty()) return null

        val count = intervalsMs.size
        val mean = intervalsMs.sum() / count
        val variance = intervalsMs.sumOf { val d = it - mean; d * d } / count

        return RateStats(
            hz = if (mean > 0) 1_000.0 / mean else 0.0,
            meanIntervalMs = mean,
            minIntervalMs = intervalsMs.min(),
            maxIntervalMs = intervalsMs.max(),
            jitterMs = sqrt(variance),
            intervalCount = count,
        )
    }

    fun reset() {
        intervalsMs.clear()
        lastTimestampNanos = null
    }
}
