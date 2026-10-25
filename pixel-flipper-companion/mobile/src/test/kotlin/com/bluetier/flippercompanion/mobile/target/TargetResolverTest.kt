package com.bluetier.flippercompanion.mobile.target

import com.bluetier.flippercompanion.shared.Bearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetResolverTest {

    private val resolver = TargetResolver()

    private fun target(id: String, yaw: Double, pitch: Double = 0.0, ble: String? = null) =
        Target(id, id, Bearing(yaw, pitch), action = "switch.$id", bleAddress = ble)

    private fun registryOf(vararg targets: Target) = TargetRegistry(targets.toList())

    private fun pointingAt(yaw: Double, pitch: Double = 0.0, seen: Map<String, Double> = emptyMap()) =
        Observation(Bearing(yaw, pitch), seen)

    @Test
    fun `an empty registry resolves to nothing`() {
        assertEquals(Resolution.EmptyRegistry, resolver.resolve(TargetRegistry(), pointingAt(90.0)))
    }

    @Test
    fun `points straight at a lone target`() {
        val registry = registryOf(target("tv", 90.0))

        val resolution = resolver.resolve(registry, pointingAt(90.0))

        val resolved = resolution as Resolution.Resolved
        assertEquals("tv", resolved.candidate.target.id)
        assertEquals(0.0, resolved.candidate.angleDegrees, 1e-6)
        assertEquals(1.0, resolved.candidate.score, 1e-6)
        assertNull(resolved.runnerUp)
    }

    @Test
    fun `tolerates the compass being off by a realistic amount`() {
        // Indoor magnetic error runs to 10-20 degrees; the resolver has to
        // survive that or the whole approach fails.
        val registry = registryOf(target("tv", 90.0))

        val resolution = resolver.resolve(registry, pointingAt(105.0))

        assertTrue(resolution is Resolution.Resolved)
    }

    @Test
    fun `declines when nothing is in that direction`() {
        val registry = registryOf(target("tv", 90.0), target("lamp", 180.0))

        val resolution = resolver.resolve(registry, pointingAt(300.0))

        val noMatch = resolution as Resolution.NoMatch
        // Pointing at 300: the lamp at 180 is 120 away, the TV at 90 is 150.
        assertEquals(120.0, noMatch.nearestAngleDegrees!!, 1e-6)
        assertEquals("lamp", noMatch.nearest!!.target.id)
    }

    @Test
    fun `declines rather than guessing between two equally plausible targets`() {
        // Firing the wrong device is worse than doing nothing: the user has to
        // notice it and undo it.
        val registry = registryOf(target("lamp_a", 90.0), target("lamp_b", 100.0))

        val resolution = resolver.resolve(registry, pointingAt(95.0))

        assertTrue("expected ambiguous, got $resolution", resolution is Resolution.Ambiguous)
    }

    @Test
    fun `picks the clearly closer of two targets`() {
        val registry = registryOf(target("tv", 90.0), target("lamp", 180.0))

        val resolution = resolver.resolve(registry, pointingAt(93.0))

        assertEquals("tv", (resolution as Resolution.Resolved).candidate.target.id)
    }

    @Test
    fun `ble presence breaks a tie the compass cannot`() {
        // The heart of the design. Two identical lamps 10 degrees apart are
        // indistinguishable by heading, but only one is advertising in this
        // room -- so the census decides.
        val registry = registryOf(
            target("lamp_here", 90.0, ble = "AA:BB:CC:DD:EE:01"),
            target("lamp_elsewhere", 100.0, ble = "AA:BB:CC:DD:EE:02"),
        )

        val ambiguous = resolver.resolve(registry, pointingAt(95.0))
        assertTrue("without the census this is a coin flip", ambiguous is Resolution.Ambiguous)

        val resolved = resolver.resolve(
            registry,
            pointingAt(95.0, seen = mapOf("AA:BB:CC:DD:EE:01" to -62.0)),
        )
        assertEquals("lamp_here", (resolved as Resolution.Resolved).candidate.target.id)
    }

    @Test
    fun `presence never overrides direction`() {
        // A device being in the room must not select it when you were plainly
        // pointing elsewhere, or the gesture stops meaning anything.
        val registry = registryOf(
            target("tv", 90.0),
            target("speaker", 270.0, ble = "AA:BB:CC:DD:EE:03"),
        )

        val resolution = resolver.resolve(
            registry,
            pointingAt(90.0, seen = mapOf("AA:BB:CC:DD:EE:03" to -40.0)),
        )

        assertEquals("tv", (resolution as Resolution.Resolved).candidate.target.id)
    }

    @Test
    fun `a target with no ble address is not penalised for lacking a radio`() {
        // An IR-controlled TV advertises nothing. It must still win on heading.
        val registry = registryOf(
            target("ir_tv", 90.0),
            target("bulb", 100.0, ble = "AA:BB:CC:DD:EE:04"),
        )

        val resolution = resolver.resolve(
            registry,
            pointingAt(91.0, seen = mapOf("AA:BB:CC:DD:EE:04" to -55.0)),
        )

        assertEquals("ir_tv", (resolution as Resolution.Resolved).candidate.target.id)
    }

    @Test
    fun `a quiet device stays reachable`() {
        // A TV on standby stops advertising. Absence is evidence, not proof,
        // so it must still resolve when it is the only thing you could mean.
        val registry = registryOf(target("tv", 90.0, ble = "AA:BB:CC:DD:EE:05"))

        val resolution = resolver.resolve(registry, pointingAt(92.0))

        assertTrue("a quiet TV must stay usable", resolution is Resolution.Resolved)
    }

    @Test
    fun `pitch distinguishes targets sharing a heading`() {
        // Pitch tolerance is loose by default, so targets separated only by
        // elevation need a wide gap - or a config that tightens it.
        val registry = registryOf(
            target("floor_lamp", 90.0, pitch = -55.0),
            target("ceiling_fan", 90.0, pitch = 55.0),
        )

        val down = resolver.resolve(registry, pointingAt(90.0, pitch = -50.0))
        val up = resolver.resolve(registry, pointingAt(90.0, pitch = 50.0))

        assertEquals("floor_lamp", (down as Resolution.Resolved).candidate.target.id)
        assertEquals("ceiling_fan", (up as Resolution.Resolved).candidate.target.id)
    }

    @Test
    fun `a stricter heading tolerance refuses what a lenient one accepts`() {
        val registry = registryOf(target("tv", 90.0))
        val observation = pointingAt(105.0)

        assertTrue(TargetResolver().resolve(registry, observation) is Resolution.Resolved)
        assertTrue(
            TargetResolver(ResolverConfig(maxYawDegrees = 10.0))
                .resolve(registry, observation) is Resolution.NoMatch,
        )
    }

    @Test
    fun `elevation is forgiven where heading is not`() {
        // The measured failure this exists for: a target captured at 43 degrees
        // of elevation, aimed at again from 13, with heading only 4 degrees out.
        // One combined cone rejected that; separate tolerances accept it, since
        // elevation moves with where you stand and heading does not.
        val registry = registryOf(target("tv", 349.0, pitch = 43.0))

        val resolution = resolver.resolve(registry, pointingAt(345.0, pitch = 13.0))

        assertTrue("a 30 degree tilt difference should not lose the target", resolution is Resolution.Resolved)
    }

    @Test
    fun `a large gap in heading is still rejected`() {
        // This asserted 30 degrees was "pointing somewhere else entirely",
        // which measured snaps disproved: honest attempts at one television
        // from one seat landed 16, 31 and 32 degrees out, because an arm is
        // not reproducible the way a television is.
        //
        // The boundary moved to 30 and the test moved with it. 45 is past any
        // of those attempts and still well inside the 180 that would make
        // every direction match.
        val registry = registryOf(target("tv", 349.0, pitch = 43.0))

        val resolution = resolver.resolve(registry, pointingAt(304.0, pitch = 43.0))

        assertTrue(resolution is Resolution.NoMatch)
    }

    @Test
    fun `an extra aim rescues a pose the first one misses`() {
        // The point of recording several aims: the second attempt is 40
        // degrees from the first, outside tolerance of it, and inside
        // tolerance of itself.
        val base = target("tv", 349.0, pitch = 10.0)
        val taught = base.copy(extraAims = listOf(Bearing(309.0, 10.0)))

        assertTrue(
            "one aim should not cover it",
            resolver.resolve(registryOf(base), pointingAt(309.0, pitch = 10.0))
                is Resolution.NoMatch,
        )
        assertTrue(
            "the taught aim should",
            resolver.resolve(registryOf(taught), pointingAt(309.0, pitch = 10.0))
                is Resolution.Resolved,
        )
    }

    @Test
    fun `extra aims do not widen a target towards everything`() {
        // Teaching aims must not degrade into "matches anything": a direction
        // far from every recorded aim is still a miss.
        val taught = target("tv", 349.0, pitch = 10.0)
            .copy(extraAims = listOf(Bearing(309.0, 10.0), Bearing(329.0, 10.0)))

        val resolution = resolver.resolve(registryOf(taught), pointingAt(180.0, pitch = 10.0))

        assertTrue(resolution is Resolution.NoMatch)
    }

    @Test
    fun `a miss reports which axis was out`() {
        val registry = registryOf(target("tv", 90.0, pitch = 0.0))

        val resolution = resolver.resolve(registry, pointingAt(180.0, pitch = 0.0))

        val nearest = (resolution as Resolution.NoMatch).nearest!!
        // Heading is the problem here, and the separation says so.
        assertTrue(kotlin.math.abs(nearest.separation.yawDegrees) > 60.0)
        assertEquals(0.0, nearest.separation.pitchDegrees, 1e-6)
    }

    @Test
    fun `the runner-up is reported for inspection`() {
        val registry = registryOf(target("tv", 90.0), target("lamp", 108.0))

        val resolved = resolver.resolve(registry, pointingAt(90.0)) as Resolution.Resolved

        assertEquals("lamp", resolved.runnerUp!!.target.id)
    }

    @Test
    fun `config rejects nonsensical settings`() {
        assertTrue(
            runCatching { ResolverConfig(maxYawDegrees = 0.0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { ResolverConfig(maxPitchDegrees = 0.0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { ResolverConfig(absentPenalty = 1.5) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }
}
