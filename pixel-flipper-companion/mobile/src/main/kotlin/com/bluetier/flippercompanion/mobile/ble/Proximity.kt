package com.bluetier.flippercompanion.mobile.ble

import kotlin.math.pow

/** Coarse distance band from signal strength, for the hot/cold finder. */
enum class ProximityBand { IMMEDIATE, NEAR, FAR, DISTANT }

/** Whether the signal is strengthening (getting closer) since a moment ago. */
enum class Trend { WARMER, COLDER, STEADY }

/**
 * RSSI → distance helpers. RSSI is noisy and reflects off everything, so these
 * are deliberately coarse and meant for *relative* "am I getting closer" use, not
 * absolute positioning. Pure and unit-testable.
 */
object Proximity {

    /** Default calibrated power at 1 m when a device doesn't advertise its own. */
    const val DEFAULT_TX_POWER = -59

    fun band(smoothedRssi: Double): ProximityBand = when {
        smoothedRssi >= -55 -> ProximityBand.IMMEDIATE
        smoothedRssi >= -70 -> ProximityBand.NEAR
        smoothedRssi >= -85 -> ProximityBand.FAR
        else -> ProximityBand.DISTANT
    }

    /**
     * Log-distance path-loss estimate in metres (exponent n = 2.0, free space-ish).
     * Clamped to [0.1, 100] m because the tail is meaningless.
     */
    fun estimateMeters(smoothedRssi: Double, txPowerAt1m: Int = DEFAULT_TX_POWER): Double {
        val meters = 10.0.pow((txPowerAt1m - smoothedRssi) / 20.0)
        return meters.coerceIn(0.1, 100.0)
    }
}

/**
 * Tracks whether a device's signal is trending warmer or colder by comparing the
 * current value to one from ~[windowMs] ago. [hysteresisDb] keeps it from
 * flickering on noise. Time is passed in so it can be unit-tested deterministically.
 */
class TrendTracker(
    private val windowMs: Long = 1_500,
    private val hysteresisDb: Double = 2.0,
) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun update(timeMs: Long, value: Double): Trend {
        samples.addLast(timeMs to value)
        while (samples.size > 1 && timeMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
        val baseline = samples.first().second
        return when {
            value - baseline > hysteresisDb -> Trend.WARMER
            baseline - value > hysteresisDb -> Trend.COLDER
            else -> Trend.STEADY
        }
    }

    fun reset() = samples.clear()
}
