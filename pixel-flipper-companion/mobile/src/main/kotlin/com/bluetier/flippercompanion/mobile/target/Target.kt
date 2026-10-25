package com.bluetier.flippercompanion.mobile.target

import com.bluetier.flippercompanion.shared.Bearing

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
