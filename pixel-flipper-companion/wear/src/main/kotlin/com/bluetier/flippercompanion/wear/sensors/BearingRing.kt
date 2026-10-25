package com.bluetier.flippercompanion.wear.sensors

import com.bluetier.flippercompanion.shared.Bearing
import com.bluetier.flippercompanion.shared.DeviceBearing

/** Where the arm was aimed at one instant. */
data class TimedBearing(val timestampNanos: Long, val bearing: Bearing)

/**
 * An aim worked out from several samples, with a measure of how steady it was.
 *
 * [spreadDegrees] is the widest departure from the mean across the window. A
 * snap thrown mid-swing carries a direction worth much less than one made with
 * the arm held still, and the phone can decline on that rather than acting on
 * a bearing that was never really meant.
 */
data class Aim(
    val bearing: Bearing,
    val spreadDegrees: Double,
    val sampleCount: Int,
)

/**
 * Recent orientation, kept so the aim can be read from *before* a snap.
 *
 * Detection necessarily happens at the end of a transient, and by then the
 * forearm has been jolted by the snap itself. The direction that was meant is
 * the one held in the moments before it, which is only available if those
 * moments were being recorded all along.
 *
 * Pure and deterministic: timestamps come from the caller.
 */
class BearingRing(val capacity: Int = 256) {

    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
    }

    private val buffer = arrayOfNulls<TimedBearing>(capacity)
    private var writeIndex = 0
    private var filled = 0

    val size: Int get() = filled

    fun add(timestampNanos: Long, bearing: Bearing) {
        buffer[writeIndex] = TimedBearing(timestampNanos, bearing)
        writeIndex = (writeIndex + 1) % capacity
        if (filled < capacity) filled++
    }

    /** Everything buffered, oldest first. */
    fun snapshot(): List<TimedBearing> {
        if (filled == 0) return emptyList()
        val start = (writeIndex - filled + capacity) % capacity
        return List(filled) { buffer[(start + it) % capacity]!! }
    }

    /**
     * The aim over a window of [windowMs] ending [lagMs] before [atNanos].
     *
     * The lag is the point of the whole class: it steps back past the snap to
     * the steady aim that preceded it. Returns null when nothing was recorded
     * in that window, which happens if a snap arrives before the orientation
     * stream has warmed up.
     */
    fun aimBefore(atNanos: Long, lagMs: Double = 120.0, windowMs: Double = 200.0): Aim? {
        val end = atNanos - (lagMs * 1_000_000.0).toLong()
        val start = end - (windowMs * 1_000_000.0).toLong()

        val window = snapshot().filter { it.timestampNanos in start..end }
        if (window.isEmpty()) return null

        val mean = DeviceBearing.average(window.map { it.bearing }) ?: return null
        val spread = window.maxOf { Bearing.angleBetween(mean, it.bearing) }

        return Aim(bearing = mean, spreadDegrees = spread, sampleCount = window.size)
    }

    fun clear() {
        writeIndex = 0
        filled = 0
        buffer.fill(null)
    }
}
