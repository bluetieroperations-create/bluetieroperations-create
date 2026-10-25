package com.bluetier.flippercompanion.mobile.target

import com.bluetier.flippercompanion.shared.Bearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BearingStabilityTest {

    private fun at(yaw: Double, pitch: Double = 0.0) = Bearing(yaw, pitch)

    @Test
    fun `a single sample is not yet a hold`() {
        val stability = BearingStability()

        assertEquals(HoldState.Moving, stability.update(0, at(90.0)))
    }

    @Test
    fun `holding steady captures after the hold time`() {
        val stability = BearingStability(holdMillis = 1_000)
        stability.update(0, at(90.0))

        assertTrue(stability.update(500, at(90.5)) is HoldState.Holding)
        assertTrue(stability.update(1_000, at(90.2)) is HoldState.Captured)
    }

    @Test
    fun `progress climbs towards the capture`() {
        val stability = BearingStability(holdMillis = 1_000)
        stability.update(0, at(90.0))

        val quarter = stability.update(250, at(90.0)) as HoldState.Holding
        val half = stability.update(500, at(90.0)) as HoldState.Holding

        assertEquals(0.25, quarter.progress, 1e-9)
        assertEquals(0.50, half.progress, 1e-9)
    }

    @Test
    fun `wandering off restarts the hold`() {
        val stability = BearingStability(holdMillis = 1_000, toleranceDegrees = 4.0)
        stability.update(0, at(90.0))
        stability.update(900, at(90.0))

        // Swings away just before capture: the timer restarts from here.
        assertEquals(HoldState.Moving, stability.update(950, at(130.0)))
        assertTrue(stability.update(1_200, at(130.0)) is HoldState.Holding)
        assertTrue(stability.update(1_950, at(130.0)) is HoldState.Captured)
    }

    @Test
    fun `small tremor within tolerance does not break the hold`() {
        val stability = BearingStability(holdMillis = 1_000, toleranceDegrees = 4.0)
        stability.update(0, at(90.0))

        listOf(91.0, 89.0, 92.0, 88.5, 90.5).forEachIndexed { i, yaw ->
            assertTrue(
                "tremor to $yaw should not break the hold",
                stability.update(100L * (i + 1), at(yaw)) is HoldState.Holding,
            )
        }
    }

    @Test
    fun `the captured bearing is the average of the hold, not the last sample`() {
        val stability = BearingStability(holdMillis = 1_000)
        stability.update(0, at(89.0))
        stability.update(250, at(91.0))
        stability.update(500, at(89.0))
        stability.update(750, at(91.0))

        val captured = stability.update(1_000, at(92.0)) as HoldState.Captured

        // A mean over the hold, pulled only slightly by the final sample.
        assertEquals(90.4, captured.bearing.yawDegrees, 0.5)
    }

    @Test
    fun `capture is sticky until reset`() {
        // A caller polling the state must not be able to miss the capture
        // between frames, nor fire the same one twice.
        val stability = BearingStability(holdMillis = 1_000)
        stability.update(0, at(90.0))
        stability.update(1_000, at(90.0))

        assertTrue(stability.update(1_100, at(90.0)) is HoldState.Captured)
        assertTrue(stability.update(5_000, at(270.0)) is HoldState.Captured)

        stability.reset()
        assertEquals(HoldState.Moving, stability.update(6_000, at(270.0)))
    }

    @Test
    fun `holding across the compass wraparound works`() {
        val stability = BearingStability(holdMillis = 1_000, toleranceDegrees = 4.0)
        stability.update(0, at(359.0))

        assertTrue(stability.update(500, at(1.0)) is HoldState.Holding)
        val captured = stability.update(1_000, at(0.0)) as HoldState.Captured
        assertTrue(
            "mean should sit near north, was ${captured.bearing.yawDegrees}",
            captured.bearing.yawDegrees < 5.0 || captured.bearing.yawDegrees > 355.0,
        )
    }

    @Test
    fun `pitch changes break the hold too`() {
        val stability = BearingStability(holdMillis = 1_000, toleranceDegrees = 4.0)
        stability.update(0, at(90.0, pitch = 0.0))

        assertEquals(HoldState.Moving, stability.update(500, at(90.0, pitch = 30.0)))
    }

    @Test
    fun `nonsensical settings are rejected`() {
        assertTrue(
            runCatching { BearingStability(toleranceDegrees = 0.0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { BearingStability(holdMillis = 0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }
}
