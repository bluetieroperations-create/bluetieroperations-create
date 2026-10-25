package com.bluetier.flippercompanion.mobile.target

import com.bluetier.flippercompanion.shared.Bearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry itself. The geometry it leans on moved to :shared when the
 * watch needed it too, and is covered by BearingTest there.
 */
class TargetRegistryTest {

    private fun at(yaw: Double, pitch: Double = 0.0) = Bearing(yaw, pitch)

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
