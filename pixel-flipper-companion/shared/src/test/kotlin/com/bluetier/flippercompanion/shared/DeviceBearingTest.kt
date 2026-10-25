package com.bluetier.flippercompanion.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DeviceBearingTest {

    /** Identity: device axes already aligned with east, north and up. */
    private val identity = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0,
    )

    /** Rotation about the vertical axis, turning the device clockwise by [degrees]. */
    private fun yawed(degrees: Double): DoubleArray {
        val a = Math.toRadians(degrees)
        // Device +Y must land on the compass heading `degrees`, so east and
        // north components of that column follow sin/cos of the angle.
        return doubleArrayOf(
            cos(a), sin(a), 0.0,
            -sin(a), cos(a), 0.0,
            0.0, 0.0, 1.0,
        )
    }

    @Test
    fun `an unrotated device held like a remote points north`() {
        val bearing = DeviceBearing.fromRotationMatrix(identity, PointingAxis.TOP_EDGE)!!

        assertEquals(0.0, bearing.yawDegrees, 1e-9)
        assertEquals(0.0, bearing.pitchDegrees, 1e-9)
    }

    @Test
    fun `yaw follows the compass clockwise from north`() {
        listOf(0.0, 45.0, 90.0, 180.0, 270.0, 315.0).forEach { heading ->
            val bearing = DeviceBearing.fromRotationMatrix(yawed(heading), PointingAxis.TOP_EDGE)!!
            assertEquals("heading $heading", heading, bearing.yawDegrees, 1e-6)
        }
    }

    @Test
    fun `screen-back points opposite to top-edge when flat`() {
        // Held flat and level, the rear camera looks straight down.
        val bearing = DeviceBearing.fromRotationMatrix(identity, PointingAxis.SCREEN_BACK)!!

        assertEquals(-90.0, bearing.pitchDegrees, 1e-9)
    }

    @Test
    fun `pitch is elevation, positive upwards`() {
        val up = DeviceBearing.fromWorldVector(east = 0.0, north = 0.0, up = 1.0)!!
        val level = DeviceBearing.fromWorldVector(east = 0.0, north = 1.0, up = 0.0)!!
        val down = DeviceBearing.fromWorldVector(east = 0.0, north = 0.0, up = -1.0)!!

        assertEquals(90.0, up.pitchDegrees, 1e-9)
        assertEquals(0.0, level.pitchDegrees, 1e-9)
        assertEquals(-90.0, down.pitchDegrees, 1e-9)
    }

    @Test
    fun `a 45 degree upward aim to the east reads correctly`() {
        val bearing = DeviceBearing.fromWorldVector(
            east = cos(Math.toRadians(45.0)),
            north = 0.0,
            up = sin(Math.toRadians(45.0)),
        )!!

        assertEquals(90.0, bearing.yawDegrees, 1e-6)
        assertEquals(45.0, bearing.pitchDegrees, 1e-6)
    }

    @Test
    fun `every pointing axis resolves to a bearing`() {
        // Adding a PointingAxis value and forgetting a branch is a compile
        // error where the `when` is exhaustive and a silent wrong answer where
        // it is not. This pins the one that does the actual geometry.
        PointingAxis.entries.forEach { axis ->
            val bearing = DeviceBearing.fromRotationMatrix(identity, axis)
            assertTrue("$axis produced no bearing", bearing != null)
        }
    }

    @Test
    fun `opposite edges point opposite ways`() {
        val top = DeviceBearing.fromRotationMatrix(identity, PointingAxis.TOP_EDGE)!!
        val bottom = DeviceBearing.fromRotationMatrix(identity, PointingAxis.BOTTOM_EDGE)!!

        assertEquals(180.0, Bearing.angleBetween(top, bottom), 1e-6)
    }

    @Test
    fun `nonsense input yields no bearing`() {
        assertNull(DeviceBearing.fromRotationMatrix(doubleArrayOf(1.0, 2.0, 3.0)))
        assertNull(DeviceBearing.fromRotationMatrix(DoubleArray(9) { Double.NaN }))
        assertNull(DeviceBearing.fromWorldVector(0.0, 0.0, 0.0))
    }

    @Test
    fun `averaging works across the compass wraparound`() {
        // The reason directions are averaged as vectors: the arithmetic mean of
        // 359 and 1 is 180, which points exactly the wrong way.
        val mean = DeviceBearing.average(
            listOf(Bearing(359.0, 0.0), Bearing(1.0, 0.0)),
        )!!

        assertEquals(0.0, mean.yawDegrees, 1e-6)
    }

    @Test
    fun `averaging smooths a jittery hold`() {
        val samples = listOf(
            Bearing(88.0, 1.0), Bearing(91.0, -1.0), Bearing(89.5, 0.5),
            Bearing(90.5, -0.5), Bearing(90.0, 0.0),
        )

        val mean = DeviceBearing.average(samples)!!

        assertEquals(89.8, mean.yawDegrees, 0.5)
        assertEquals(0.0, mean.pitchDegrees, 0.5)
    }

    @Test
    fun `averaging nothing yields nothing`() {
        assertNull(DeviceBearing.average(emptyList()))
    }

    @Test
    fun `opposing directions have no meaningful mean`() {
        val mean = DeviceBearing.average(listOf(Bearing(0.0, 0.0), Bearing(180.0, 0.0)))

        assertTrue("expected null or a defined bearing, never NaN", mean == null || mean.yawDegrees.isFinite())
    }
}
