package com.bluetier.flippercompanion.mobile.target

/** Where a point-and-hold has got to. */
sealed interface HoldState {
    /** Not steady enough; the hold has not started or has been broken. */
    data object Moving : HoldState

    /** Holding steady. [progress] runs 0 until 1 and suits a filling indicator. */
    data class Holding(val progress: Double, val bearing: Bearing) : HoldState

    /** Held long enough. [bearing] is the average over the hold, not the last sample. */
    data class Captured(val bearing: Bearing) : HoldState
}

/**
 * Detects "pointing at something and keeping still", the calibration gesture.
 *
 * Holding is what makes calibration trustworthy: a single instant carries
 * whatever hand tremor and magnetic noise happened to coincide with it, while a
 * second of steadiness averages both away and is a clear statement of intent.
 *
 * Direction is averaged as vectors rather than by taking a mean of the yaw
 * figures, which would be badly wrong across the compass wraparound - the mean
 * of 359 and 1 is 180, pointing exactly backwards.
 *
 * Pure and deterministic: timestamps come from the caller.
 */
class BearingStability(
    /** How far the aim may wander and still count as held. */
    private val toleranceDegrees: Double = 4.0,
    /** How long it must stay within that to capture. */
    private val holdMillis: Long = 1_000,
) {
    init {
        require(toleranceDegrees > 0) { "tolerance must be positive, was $toleranceDegrees" }
        require(holdMillis > 0) { "hold must be positive, was $holdMillis" }
    }

    private var anchor: Bearing? = null
    private var anchorTimeMs = 0L
    private var captured = false
    private val samples = mutableListOf<Bearing>()

    /**
     * Feeds one orientation sample.
     *
     * Once [HoldState.Captured] is returned it keeps being returned until
     * [reset], so a caller polling the state cannot capture the same hold
     * twice or miss it between frames.
     */
    fun update(timeMs: Long, bearing: Bearing): HoldState {
        val current = anchor
        if (captured) return HoldState.Captured(averageOrLast(bearing))

        if (current == null || !current.isWithin(toleranceDegrees, bearing)) {
            // Wandered off: start a fresh hold from here rather than punishing
            // the user with a cooldown.
            anchor = bearing
            anchorTimeMs = timeMs
            samples.clear()
            samples.add(bearing)
            return HoldState.Moving
        }

        samples.add(bearing)
        val heldFor = timeMs - anchorTimeMs

        return if (heldFor >= holdMillis) {
            captured = true
            HoldState.Captured(averageOrLast(bearing))
        } else {
            HoldState.Holding(
                progress = (heldFor.toDouble() / holdMillis).coerceIn(0.0, 1.0),
                bearing = bearing,
            )
        }
    }

    fun reset() {
        anchor = null
        anchorTimeMs = 0L
        captured = false
        samples.clear()
    }

    private fun averageOrLast(fallback: Bearing): Bearing =
        DeviceBearing.average(samples) ?: fallback
}
