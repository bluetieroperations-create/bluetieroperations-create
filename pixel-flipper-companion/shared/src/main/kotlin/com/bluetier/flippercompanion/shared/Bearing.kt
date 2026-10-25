package com.bluetier.flippercompanion.shared

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// Geometry lives in :shared because both halves need it: the phone to
// resolve a bearing against its registry, and the watch to produce one at
// the moment of a snap. Duplicating it would let the two drift apart, and a
// pointing system whose ends disagree about what a bearing means is worse
// than one that does not point at all.

/**
 * A direction, as the watch reports it.
 *
 * [yawDegrees] is compass azimuth: 0 is north, increasing clockwise, so east is
 * 90. [pitchDegrees] is elevation, positive upwards. Together they are where
 * the forearm was aimed at the instant of a snap.
 */
data class Bearing(
    val yawDegrees: Double,
    val pitchDegrees: Double,
) {
    init {
        require(pitchDegrees in -90.0..90.0) {
            "pitch must be within +/-90 degrees, was $pitchDegrees"
        }
    }

    /** Yaw folded into 0 until 360, so 370 and 10 compare equal. */
    val normalizedYaw: Double
        get() = ((yawDegrees % 360.0) + 360.0) % 360.0

    /**
     * Unit vector in a local east/north/up frame.
     *
     * Working in vectors rather than comparing yaw and pitch separately is what
     * makes the wraparound at 360 degrees disappear, and it keeps the angle
     * honest near the poles, where a large yaw difference is a small real one.
     */
    internal fun toVector(): DoubleArray {
        val yaw = Math.toRadians(normalizedYaw)
        val pitch = Math.toRadians(pitchDegrees)
        val horizontal = cos(pitch)
        return doubleArrayOf(
            horizontal * sin(yaw), // east
            horizontal * cos(yaw), // north
            sin(pitch),            // up
        )
    }

    companion object {
        /**
         * Angle between two bearings, in degrees. Always 0 until 180.
         *
         * Uses `atan2(|a x b|, a . b)` rather than `acos(a . b)`. They agree
         * mathematically, but acos is ill-conditioned where this is used most:
         * its slope is infinite at a dot product of 1, so the rounding error in
         * two nearly identical bearings is magnified into a visible angle.
         * atan2 of the cross and dot products stays accurate across the whole
         * range, and returns exactly zero for a bearing compared with itself.
         */
        fun angleBetween(a: Bearing, b: Bearing): Double {
            val u = a.toVector()
            val v = b.toVector()

            val dot = u[0] * v[0] + u[1] * v[1] + u[2] * v[2]
            val crossX = u[1] * v[2] - u[2] * v[1]
            val crossY = u[2] * v[0] - u[0] * v[2]
            val crossZ = u[0] * v[1] - u[1] * v[0]
            val cross = sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)

            return Math.toDegrees(atan2(cross, dot))
        }
    }
}

/**
 * How far apart two bearings are, split into heading and elevation.
 *
 * Kept separate because the two are not equally trustworthy. Heading is highly
 * reproducible: a television is north-north-west of the sofa whether you are
 * sitting or standing. Elevation is not — it shifts with where you stand, how
 * tall you are and how much you happen to tilt the phone. Collapsing both into
 * one number treats a 30 degree elevation error as being as damning as a 30
 * degree heading error, which it is not.
 */
data class Separation(
    /** Shortest signed heading difference, foreshortened for elevation. */
    val yawDegrees: Double,
    /** Signed elevation difference. */
    val pitchDegrees: Double,
) {
    /** Straight-line combination, for when a single figure is wanted. */
    val magnitudeDegrees: Double get() = hypot(yawDegrees, pitchDegrees)
}

/** Shortest signed turn from [from] to [to], within -180 until 180. */
internal fun shortestYawDelta(from: Double, to: Double): Double {
    var delta = (to - from) % 360.0
    if (delta > 180.0) delta -= 360.0
    if (delta < -180.0) delta += 360.0
    return delta
}

/**
 * Splits the gap between two bearings into heading and elevation parts.
 *
 * The heading difference is multiplied by the cosine of the mean elevation:
 * the steeper you point, the less real angle a given heading difference spans,
 * and straight up it spans none at all.
 */
fun separationBetween(a: Bearing, b: Bearing): Separation {
    val meanPitch = Math.toRadians((a.pitchDegrees + b.pitchDegrees) / 2.0)
    return Separation(
        yawDegrees = shortestYawDelta(a.normalizedYaw, b.normalizedYaw) * cos(meanPitch),
        pitchDegrees = b.pitchDegrees - a.pitchDegrees,
    )
}

/** True when two bearings are within [degrees] of each other. */
fun Bearing.isWithin(degrees: Double, other: Bearing): Boolean =
    abs(Bearing.angleBetween(this, other)) <= degrees
