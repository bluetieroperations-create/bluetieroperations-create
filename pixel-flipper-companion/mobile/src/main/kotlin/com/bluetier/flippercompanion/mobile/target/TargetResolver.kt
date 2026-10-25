package com.bluetier.flippercompanion.mobile.target

import com.bluetier.flippercompanion.shared.Bearing
import com.bluetier.flippercompanion.shared.Separation
import com.bluetier.flippercompanion.shared.separationBetween
import kotlin.math.sqrt

/**
 * What the phone knows at the instant of a snap.
 *
 * [bearing] is where the watch was aimed, taken from the orientation buffer
 * shortly *before* the snap: the snap itself jolts the forearm.
 *
 * [visibleRssi] is the live BLE census keyed by address, from `BleScanner`.
 * It is what makes a compass good enough for this: a heading accurate to
 * roughly 15 degrees only has to tell apart the devices actually in the room,
 * not every device ever registered.
 */
data class Observation(
    val bearing: Bearing,
    val visibleRssi: Map<String, Double> = emptyMap(),
)

/**
 * How willing the resolver is to commit.
 *
 * The defaults assume the fused rotation vector on a wrist, where indoor
 * magnetic error runs to 10-20 degrees.
 */
data class ResolverConfig(
    /**
     * Heading tolerance. Tight, because heading is the reproducible half: a
     * television sits on the same bearing from a room whether you are sitting
     * or standing.
     */
    val maxYawDegrees: Double = 20.0,
    /**
     * Elevation tolerance. Deliberately much looser. Where you stand, how tall
     * you are and how far you tilt the phone all move elevation while heading
     * stays put, so holding pitch to the same standard rejects targets that
     * were aimed at perfectly well.
     *
     * Tighten it when pitch is doing real work — a floor lamp below and a
     * ceiling fan above on the same bearing.
     */
    val maxPitchDegrees: Double = 40.0,
    /**
     * Score lead the winner needs over the runner-up. Below it the resolver
     * declines rather than guessing: firing the wrong device is worse than
     * doing nothing, because the user has to notice and undo it.
     */
    val ambiguityMargin: Double = 0.15,
    /**
     * Multiplier for a target that advertises over BLE but is nowhere in the
     * census. Not zero: a TV on standby may stop advertising, so absence is
     * evidence rather than proof.
     */
    val absentPenalty: Double = 0.5,
) {
    init {
        require(maxYawDegrees > 0.0 && maxYawDegrees <= 180.0) {
            "maxYawDegrees must be within 0 until 180, was $maxYawDegrees"
        }
        require(maxPitchDegrees > 0.0 && maxPitchDegrees <= 180.0) {
            "maxPitchDegrees must be within 0 until 180, was $maxPitchDegrees"
        }
        require(absentPenalty in 0.0..1.0) {
            "absentPenalty must be within 0 until 1, was $absentPenalty"
        }
    }
}

/** One target weighed against an observation, kept for display and debugging. */
data class Candidate(
    val target: Target,
    /** True angle between the aim and the target, for showing a human. */
    val angleDegrees: Double,
    /**
     * The same gap split into heading and elevation. Reported separately
     * because it is usually the answer to "why did that not match": a 4 degree
     * heading error beside a 30 degree elevation error says plainly that the
     * phone was held at the wrong tilt, which one combined figure hides.
     */
    val separation: Separation,
    /** 1.0 pointing straight at it, falling to 0 at the edge of tolerance. */
    val angularScore: Double,
    /** 1.0 when present or unknown, [ResolverConfig.absentPenalty] when expected but unseen. */
    val presenceScore: Double,
    /** Elliptical distance; at or below 1.0 the target is within tolerance. */
    val normalizedDistance: Double,
) {
    val score: Double get() = angularScore * presenceScore

    val withinTolerance: Boolean get() = normalizedDistance <= 1.0
}

/** The resolver's verdict. */
sealed interface Resolution {
    /** Confident enough to act. */
    data class Resolved(val candidate: Candidate, val runnerUp: Candidate?) : Resolution

    /** Two targets fit about equally well; the resolver declines to pick. */
    data class Ambiguous(val first: Candidate, val second: Candidate) : Resolution

    /** Nothing registered lies within tolerance. [nearest] is the closest miss. */
    data class NoMatch(
        val nearestAngleDegrees: Double?,
        val nearest: Candidate? = null,
    ) : Resolution

    data object EmptyRegistry : Resolution
}

/**
 * Picks which registered target a snap was aimed at.
 *
 * Scoring is `angularScore * presenceScore`, so being in the right direction is
 * necessary and BLE presence breaks ties. That ordering matters: presence alone
 * must never select a target you were not pointing at, and a device that has
 * gone quiet must not become unreachable.
 *
 * Declining is a first-class outcome. [Resolution.Ambiguous] and
 * [Resolution.NoMatch] both mean "do nothing", and that is the right answer
 * whenever the geometry does not clearly single one target out.
 *
 * Pure and deterministic, so the whole decision is unit-testable without a
 * device, a room, or a compass.
 */
class TargetResolver(private val config: ResolverConfig = ResolverConfig()) {

    fun resolve(registry: TargetRegistry, observation: Observation): Resolution {
        if (registry.targets.isEmpty()) return Resolution.EmptyRegistry

        val scored = registry.targets
            .map { target -> score(target, observation) }
            .sortedByDescending { it.score }

        val candidates = scored.filter { it.withinTolerance }
        if (candidates.isEmpty()) {
            val nearest = scored.minByOrNull { it.normalizedDistance }
            return Resolution.NoMatch(nearest?.angleDegrees, nearest)
        }

        val best = candidates.first()
        val runnerUp = candidates.getOrNull(1)

        return when {
            runnerUp != null && best.score - runnerUp.score < config.ambiguityMargin ->
                Resolution.Ambiguous(best, runnerUp)

            else -> Resolution.Resolved(best, runnerUp)
        }
    }

    private fun score(target: Target, observation: Observation): Candidate {
        val angle = Bearing.angleBetween(observation.bearing, target.bearing)
        val separation = separationBetween(observation.bearing, target.bearing)

        // An ellipse rather than a cone: wide in elevation, narrow in heading.
        // At 1.0 the aim is exactly on the boundary, whichever axis took it
        // there, so one normalised figure orders candidates across both.
        val yawTerm = separation.yawDegrees / config.maxYawDegrees
        val pitchTerm = separation.pitchDegrees / config.maxPitchDegrees
        val normalized = sqrt(yawTerm * yawTerm + pitchTerm * pitchTerm)

        // Linear falloff rather than anything fancier: with magnetic error of
        // this size, a more elaborate curve would be false precision.
        val angular = (1.0 - normalized).coerceIn(0.0, 1.0)

        val presence = when {
            // No BLE address registered: nothing is known, so stay neutral
            // rather than penalising a target for lacking a radio.
            target.bleAddress == null -> 1.0
            observation.visibleRssi.containsKey(target.bleAddress) -> 1.0
            else -> config.absentPenalty
        }

        return Candidate(
            target = target,
            angleDegrees = angle,
            separation = separation,
            angularScore = angular,
            presenceScore = presence,
            normalizedDistance = normalized,
        )
    }
}
