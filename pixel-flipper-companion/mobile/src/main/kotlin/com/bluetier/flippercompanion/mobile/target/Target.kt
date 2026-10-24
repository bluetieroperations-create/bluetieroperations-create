package com.bluetier.flippercompanion.mobile.target

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
 * One thing that can be pointed at.
 *
 * [bearing] is where it sits, recorded during calibration from a known standing
 * spot. [bleAddress] is optional and does double duty: it narrows the candidate
 * set at resolve time (a device whose advertiser is nowhere in range is
 * probably not the one being pointed at) and it is how a BLE device is
 * actuated directly. [action] is opaque here — a Home Assistant entity id, a
 * GATT write spec, whatever the dispatcher understands — because what a target
 * *is* and how it is *driven* are separate concerns.
 */
data class Target(
    val id: String,
    val label: String,
    val bearing: Bearing,
    val action: String,
    val bleAddress: String? = null,
    /** UID of the NFC tag that calibrated this target, if one did. */
    val nfcTagUid: String? = null,
) {
    init {
        require(id.isNotBlank()) { "a target needs an id" }
        require(label.isNotBlank()) { "a target needs a label" }
    }
}

/** The registry's view of the world: the targets that have been calibrated. */
data class TargetRegistry(val targets: List<Target> = emptyList()) {

    init {
        val duplicates = targets.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate target ids: $duplicates" }
    }

    val size: Int get() = targets.size

    fun byId(id: String): Target? = targets.firstOrNull { it.id == id }

    fun byNfcTag(uid: String): Target? = targets.firstOrNull { it.nfcTagUid == uid }

    /** Adds a target, replacing any existing one with the same id. */
    fun with(target: Target): TargetRegistry =
        TargetRegistry(targets.filterNot { it.id == target.id } + target)

    fun without(id: String): TargetRegistry =
        TargetRegistry(targets.filterNot { it.id == id })

    /**
     * Closest angular separation between any two targets.
     *
     * The practical limit on this design: indoor magnetometer error runs to
     * 10-20 degrees, so two targets closer together than that cannot be told
     * apart by heading alone. Surfaced so calibration can warn at the point of
     * registering rather than leaving it to be discovered in use.
     */
    fun tightestSeparationDegrees(): Double? {
        if (targets.size < 2) return null
        return targets.indices.flatMap { i ->
            ((i + 1) until targets.size).map { j ->
                Bearing.angleBetween(targets[i].bearing, targets[j].bearing)
            }
        }.minOrNull()
    }

    /** Targets within [degrees] of [bearing], nearest first. */
    fun near(bearing: Bearing, degrees: Double): List<Target> =
        targets
            .map { it to Bearing.angleBetween(bearing, it.bearing) }
            .filter { (_, angle) -> angle <= degrees }
            .sortedBy { (_, angle) -> angle }
            .map { (target, _) -> target }
}

/** True when two bearings are within [degrees] of each other. */
fun Bearing.isWithin(degrees: Double, other: Bearing): Boolean =
    abs(Bearing.angleBetween(this, other)) <= degrees
