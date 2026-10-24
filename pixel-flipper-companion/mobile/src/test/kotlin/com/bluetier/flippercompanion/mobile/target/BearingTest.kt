package com.bluetier.flippercompanion.mobile.target

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

    @Test
    fun `registry reports the tightest separation`() {
        val registry = TargetRegistry(
            listOf(
                target("tv", at(90.0)),
                target("lamp", at(100.0)),
                target("fan", at(270.0)),
            ),
        )

        // The practical constraint: anything under ~15 degrees cannot be told
        // apart by an indoor compass.
        assertEquals(10.0, registry.tightestSeparationDegrees()!!, 1e-6)
    }

    @Test
    fun `tightest separation is undefined below two targets`() {
        assertNull(TargetRegistry().tightestSeparationDegrees())
        assertNull(TargetRegistry(listOf(target("tv", at(90.0)))).tightestSeparationDegrees())
    }

    @Test
    fun `near returns matches closest first`() {
        val registry = TargetRegistry(
            listOf(
                target("far", at(120.0)),
                target("close", at(92.0)),
                target("miles", at(270.0)),
            ),
        )

        assertEquals(listOf("close", "far"), registry.near(at(95.0), degrees = 30.0).map { it.id })
    }

    @Test
    fun `with replaces by id and without removes`() {
        val registry = TargetRegistry().with(target("tv", at(90.0)))
            .with(target("tv", at(100.0)))

        assertEquals(1, registry.size)
        assertEquals(100.0, registry.byId("tv")!!.bearing.yawDegrees, 1e-9)
        assertEquals(0, registry.without("tv").size)
    }

    @Test
    fun `duplicate ids are rejected`() {
        assertTrue(
            runCatching {
                TargetRegistry(listOf(target("tv", at(90.0)), target("tv", at(180.0))))
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `an nfc tag finds its target`() {
        val registry = TargetRegistry(
            listOf(target("tv", at(90.0)).copy(nfcTagUid = "04A2B3")),
        )

        assertEquals("tv", registry.byNfcTag("04A2B3")!!.id)
        assertNull(registry.byNfcTag("DEADBEEF"))
    }

    private fun target(id: String, bearing: Bearing) =
        Target(id = id, label = id, bearing = bearing, action = "switch.$id")
}
