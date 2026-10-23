package com.bluetier.flippercompanion.wear.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRingTest {

    private fun sample(ms: Long, accel: Double = 9.81, gyro: Double = 0.0) =
        ImuSample(ms * 1_000_000L, accel, gyro)

    @Test
    fun `starts empty`() {
        val ring = SampleRing(capacity = 4)
        assertEquals(0, ring.size)
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test
    fun `preserves order while filling`() {
        val ring = SampleRing(capacity = 4)
        repeat(3) { ring.add(sample(it.toLong())) }

        assertEquals(3, ring.size)
        assertEquals(listOf(0L, 1L, 2L), ring.snapshot().map { it.timestampNanos / 1_000_000L })
    }

    @Test
    fun `drops the oldest samples on wraparound`() {
        val ring = SampleRing(capacity = 3)
        repeat(5) { ring.add(sample(it.toLong())) }

        assertEquals(3, ring.size)
        assertEquals(listOf(2L, 3L, 4L), ring.snapshot().map { it.timestampNanos / 1_000_000L })
    }

    @Test
    fun `window returns samples around a centre`() {
        val ring = SampleRing(capacity = 64)
        repeat(50) { ring.add(sample(it.toLong())) }

        // Centre on 25 ms, 10 ms either side.
        val window = ring.window(centerNanos = 25 * 1_000_000L, preMs = 10.0, postMs = 10.0)

        assertEquals(21, window.size) // 15..35 inclusive
        assertEquals(15L, window.first().timestampNanos / 1_000_000L)
        assertEquals(35L, window.last().timestampNanos / 1_000_000L)
    }

    @Test
    fun `window is not padded when history is short`() {
        val ring = SampleRing(capacity = 64)
        repeat(5) { ring.add(sample(it.toLong())) }

        // Asking for 100 ms of lead-in when only 5 ms exists yields what there is.
        val window = ring.window(centerNanos = 4 * 1_000_000L, preMs = 100.0, postMs = 100.0)

        assertEquals(5, window.size)
    }

    @Test
    fun `window carries the sample values through`() {
        val ring = SampleRing(capacity = 8)
        ring.add(sample(0, accel = 9.81, gyro = 0.1))
        ring.add(sample(1, accel = 25.0, gyro = 0.4))

        val peak = ring.window(1 * 1_000_000L, preMs = 0.0, postMs = 0.0).single()
        assertEquals(25.0, peak.accelMagnitude, 1e-9)
        assertEquals(0.4, peak.gyroMagnitude, 1e-9)
    }

    @Test
    fun `clear empties the ring`() {
        val ring = SampleRing(capacity = 4)
        repeat(4) { ring.add(sample(it.toLong())) }
        ring.clear()

        assertEquals(0, ring.size)
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a zero capacity`() {
        SampleRing(capacity = 0)
    }
}
