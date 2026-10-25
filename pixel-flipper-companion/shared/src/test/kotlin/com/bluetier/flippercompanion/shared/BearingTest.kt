package com.bluetier.flippercompanion.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BearingTest {

    private fun at(yaw: Double, pitch: Double = 0.0) = Bearing(yaw, pitch)

    @Test
    fun `a bearing is zero degrees from itself`() {
        assertEquals(0.0, Bearing.angleBetween(at(137.0, -4.0), at(137.0, -4.0)), 1e-9)
    }

    @Test
    fun `angle does not jump at the compass wraparound`() {
        // The reason bearings are compared as vectors: 359 and 1 are 2 degrees
        // apart, not 358.
        assertEquals(2.0, Bearing.angleBetween(at(359.0), at(1.0)), 1e-6)
        assertEquals(10.0, Bearing.angleBetween(at(355.0), at(5.0)), 1e-6)
    }

    @Test
    fun `yaw outside 0 until 360 normalizes`() {
        assertEquals(0.0, Bearing.angleBetween(at(370.0), at(10.0)), 1e-6)
        assertEquals(0.0, Bearing.angleBetween(at(-10.0), at(350.0)), 1e-6)
        assertEquals(350.0, at(-10.0).normalizedYaw, 1e-9)
    }

    @Test
    fun `pitch separates two targets on the same heading`() {
        // Point down at a floor lamp, up at a ceiling fan: same compass
        // direction, 60 degrees apart.
        assertEquals(60.0, Bearing.angleBetween(at(90.0, -30.0), at(90.0, 30.0)), 1e-6)
    }

    @Test
    fun `near the vertical a large yaw difference is a small real one`() {
        // Pointing almost straight up, heading barely matters - which comparing
        // yaw and pitch separately would get badly wrong.
        val angle = Bearing.angleBetween(at(0.0, 89.0), at(180.0, 89.0))
        assertTrue("expected a small angle, got $angle", angle < 3.0)
    }

    @Test
    fun `opposite directions are 180 degrees apart`() {
        assertEquals(180.0, Bearing.angleBetween(at(0.0), at(180.0)), 1e-6)
    }

    @Test
    fun `isWithin reflects the tolerance`() {
        assertTrue(at(100.0).isWithin(15.0, at(110.0)))
        assertFalse(at(100.0).isWithin(5.0, at(110.0)))
    }

    @Test
    fun `pitch beyond vertical is rejected`() {
        assertTrue(
            runCatching { Bearing(0.0, 91.0) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

}

/** The split into heading and elevation, which drives the resolver's tolerance. */
class SeparationTest {

    @Test
    fun `pure heading difference shows only in yaw`() {
        val separation = separationBetween(Bearing(90.0, 0.0), Bearing(110.0, 0.0))

        assertEquals(20.0, separation.yawDegrees, 1e-6)
        assertEquals(0.0, separation.pitchDegrees, 1e-6)
    }

    @Test
    fun `pure elevation difference shows only in pitch`() {
        val separation = separationBetween(Bearing(90.0, 10.0), Bearing(90.0, 40.0))

        assertEquals(0.0, separation.yawDegrees, 1e-6)
        assertEquals(30.0, separation.pitchDegrees, 1e-6)
    }

    @Test
    fun `heading difference takes the short way round`() {
        val separation = separationBetween(Bearing(355.0, 0.0), Bearing(5.0, 0.0))

        assertEquals(10.0, separation.yawDegrees, 1e-6)
    }

    @Test
    fun `heading counts for less the steeper you point`() {
        // Thirty degrees of heading spans much less real angle when aimed
        // steeply upward, and nothing at all straight up.
        val level = separationBetween(Bearing(0.0, 0.0), Bearing(30.0, 0.0))
        val steep = separationBetween(Bearing(0.0, 75.0), Bearing(30.0, 75.0))

        assertTrue(
            "steep=${steep.yawDegrees} should be well under level=${level.yawDegrees}",
            kotlin.math.abs(steep.yawDegrees) < kotlin.math.abs(level.yawDegrees) * 0.5,
        )
    }

    @Test
    fun `the measured TV case is mostly elevation`() {
        // Target captured at 349 / 43, aimed at again from 345 / 13.
        val separation = separationBetween(Bearing(345.0, 13.0), Bearing(349.0, 43.0))

        assertTrue("heading was nearly right", kotlin.math.abs(separation.yawDegrees) < 5.0)
        assertEquals(30.0, separation.pitchDegrees, 1e-6)
    }

    @Test
    fun `signed deltas point the right way`() {
        assertEquals(-20.0, separationBetween(Bearing(110.0, 0.0), Bearing(90.0, 0.0)).yawDegrees, 1e-6)
        assertEquals(-30.0, separationBetween(Bearing(90.0, 40.0), Bearing(90.0, 10.0)).pitchDegrees, 1e-6)
    }
}
