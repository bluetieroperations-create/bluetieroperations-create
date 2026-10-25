package com.bluetier.flippercompanion.wear.sensors

import com.bluetier.flippercompanion.shared.Bearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BearingRingTest {

    private fun ms(value: Long) = value * 1_000_000L

    /** Fills the ring with a steady aim, one sample every 10 ms. */
    private fun steady(ring: BearingRing, yaw: Double, fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t <= toMs) {
            ring.add(ms(t), Bearing(yaw, 0.0))
            t += 10
        }
    }

    @Test
    fun `starts empty`() {
        val ring = BearingRing()
        assertEquals(0, ring.size)
        assertNull(ring.aimBefore(ms(100)))
    }

    @Test
    fun `reads the aim from before the snap, not at it`() {
        // The arm is steady at 90 until 500 ms, then the snap throws it to 150.
        val ring = BearingRing()
        steady(ring, yaw = 90.0, fromMs = 0, toMs = 500)
        steady(ring, yaw = 150.0, fromMs = 510, toMs = 600)

        val aim = ring.aimBefore(atNanos = ms(600), lagMs = 120.0, windowMs = 200.0)!!

        // Window is 280..480 ms: entirely before the disturbance.
        assertEquals(90.0, aim.bearing.yawDegrees, 0.5)
    }

    @Test
    fun `a steady arm reports almost no spread`() {
        val ring = BearingRing()
        steady(ring, yaw = 137.0, fromMs = 0, toMs = 600)

        val aim = ring.aimBefore(ms(600))!!

        assertTrue("spread was ${aim.spreadDegrees}", aim.spreadDegrees < 0.5)
        assertEquals(137.0, aim.bearing.yawDegrees, 0.5)
    }

    @Test
    fun `an arm in motion reports a wide spread`() {
        // Sweeping through 60 degrees across the window: a direction that was
        // never really meant, and the phone should be able to see that.
        val ring = BearingRing()
        var yaw = 60.0
        var t = 0L
        while (t <= 600) {
            ring.add(ms(t), Bearing(yaw, 0.0))
            yaw += 1.0
            t += 10
        }

        val aim = ring.aimBefore(ms(600))!!

        assertTrue("spread was ${aim.spreadDegrees}", aim.spreadDegrees > 5.0)
    }

    @Test
    fun `spread is measured across the compass wraparound`() {
        val ring = BearingRing()
        listOf(358.0, 359.0, 0.0, 1.0, 2.0).forEachIndexed { i, yaw ->
            // All inside the default window ending 120 ms before 600 ms.
            ring.add(ms(300 + i * 10L), Bearing(yaw, 0.0))
        }

        val aim = ring.aimBefore(ms(600))!!

        assertTrue("mean should sit near north, was ${aim.bearing.yawDegrees}",
            aim.bearing.yawDegrees < 5.0 || aim.bearing.yawDegrees > 355.0)
        assertTrue("spread was ${aim.spreadDegrees}", aim.spreadDegrees < 3.0)
    }

    @Test
    fun `no samples in the window yields no aim`() {
        val ring = BearingRing()
        steady(ring, yaw = 90.0, fromMs = 0, toMs = 100)

        // Window would be 680..880 ms; nothing was recorded there.
        assertNull(ring.aimBefore(ms(1_000)))
    }

    @Test
    fun `older samples fall out of the ring`() {
        val ring = BearingRing(capacity = 8)
        steady(ring, yaw = 90.0, fromMs = 0, toMs = 500)

        assertEquals(8, ring.size)
        assertEquals(ms(430), ring.snapshot().first().timestampNanos)
    }

    @Test
    fun `clear empties it`() {
        val ring = BearingRing(capacity = 8)
        steady(ring, yaw = 90.0, fromMs = 0, toMs = 100)
        ring.clear()
        assertEquals(0, ring.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a zero capacity`() {
        BearingRing(capacity = 0)
    }
}
