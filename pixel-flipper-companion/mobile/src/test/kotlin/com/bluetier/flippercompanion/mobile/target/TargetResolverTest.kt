package com.bluetier.flippercompanion.mobile.target

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
        val registry = registryOf(
            target("floor_lamp", 90.0, pitch = -35.0),
            target("ceiling_fan", 90.0, pitch = 35.0),
        )

        val down = resolver.resolve(registry, pointingAt(90.0, pitch = -30.0))
        val up = resolver.resolve(registry, pointingAt(90.0, pitch = 30.0))

        assertEquals("floor_lamp", (down as Resolution.Resolved).candidate.target.id)
        assertEquals("ceiling_fan", (up as Resolution.Resolved).candidate.target.id)
    }

    @Test
    fun `a stricter cone refuses what a lenient one accepts`() {
        val registry = registryOf(target("tv", 90.0))
        val observation = pointingAt(110.0)

        assertTrue(TargetResolver().resolve(registry, observation) is Resolution.Resolved)
        assertTrue(
            TargetResolver(ResolverConfig(maxAngleDegrees = 10.0))
                .resolve(registry, observation) is Resolution.NoMatch,
        )
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
            runCatching { ResolverConfig(maxAngleDegrees = 0.0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { ResolverConfig(absentPenalty = 1.5) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }
}
