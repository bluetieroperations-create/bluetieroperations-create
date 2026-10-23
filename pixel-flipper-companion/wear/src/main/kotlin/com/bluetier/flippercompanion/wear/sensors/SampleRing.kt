package com.bluetier.flippercompanion.wear.sensors

/** One fused IMU sample: accelerometer and gyroscope magnitudes at an instant. */
data class ImuSample(
    val timestampNanos: Long,
    val accelMagnitude: Double,
    val gyroMagnitude: Double,
)

/**
 * Fixed-capacity ring of recent IMU samples, so that when a transient is
 * detected the samples *leading up to it* are still available.
 *
 * This is the reason the detector can be causal while the exported trace is
 * centred: detection necessarily happens at the end of a transient, but what
 * you want to look at afterwards starts before the beginning of it.
 *
 * Pure and allocation-free on the hot path apart from the backing array.
 */
class SampleRing(val capacity: Int = 512) {

    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
    }

    private val buffer = arrayOfNulls<ImuSample>(capacity)
    private var writeIndex = 0
    private var filled = 0

    val size: Int get() = filled

    fun add(sample: ImuSample) {
        buffer[writeIndex] = sample
        writeIndex = (writeIndex + 1) % capacity
        if (filled < capacity) filled++
    }

    /** Every buffered sample, oldest first. */
    fun snapshot(): List<ImuSample> {
        if (filled == 0) return emptyList()
        val start = (writeIndex - filled + capacity) % capacity
        return List(filled) { buffer[(start + it) % capacity]!! }
    }

    /**
     * Samples within [preMs] before [centerNanos] and [postMs] after it, oldest
     * first. Returns whatever is in range; the window is not padded, so a trace
     * taken right after startup is simply shorter.
     */
    fun window(centerNanos: Long, preMs: Double, postMs: Double): List<ImuSample> {
        val from = centerNanos - (preMs * 1_000_000.0).toLong()
        val to = centerNanos + (postMs * 1_000_000.0).toLong()
        return snapshot().filter { it.timestampNanos in from..to }
    }

    fun clear() {
        writeIndex = 0
        filled = 0
        buffer.fill(null)
    }
}
