package com.bluetier.flippercompanion.wear.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRateEstimatorTest {

    private val msToNanos = 1_000_000L

    @Test
    fun `no stats before any interval is observed`() {
        val estimator = SampleRateEstimator()
        assertNull(estimator.stats())

        // One sample is still zero intervals.
        estimator.record(0)
        assertNull(estimator.stats())
    }

    @Test
    fun `reports the rate of an evenly spaced stream`() {
        val estimator = SampleRateEstimator()
        // 5 ms spacing is 200 Hz.
        repeat(100) { estimator.record(it * 5L * msToNanos) }

        val stats = estimator.stats()!!
        assertEquals(200.0, stats.hz, 0.001)
        assertEquals(5.0, stats.meanIntervalMs, 0.001)
        assertEquals(0.0, stats.jitterMs, 0.001)
        assertEquals(99, stats.intervalCount)
    }

    @Test
    fun `reports 100 Hz for 10 ms spacing`() {
        val estimator = SampleRateEstimator()
        repeat(50) { estimator.record(it * 10L * msToNanos) }

        assertEquals(100.0, estimator.stats()!!.hz, 0.001)
    }

    @Test
    fun `ignores duplicate and non-monotonic timestamps`() {
        val estimator = SampleRateEstimator()
        // Some Wear OS HALs replay a timestamp across a batch delivery. Counting
        // those as zero-length intervals would report an absurd rate.
        estimator.record(0)
        estimator.record(10 * msToNanos)
        estimator.record(10 * msToNanos) // duplicate
        estimator.record(5 * msToNanos)  // goes backwards
        estimator.record(15 * msToNanos)

        val stats = estimator.stats()!!
        // Only 0->10 counted; the backwards jump resets the reference to 5 ms,
        // so 5->15 contributes a second 10 ms interval.
        assertEquals(10.0, stats.meanIntervalMs, 0.001)
        assertTrue(stats.hz.isFinite())
    }

    @Test
    fun `window evicts the oldest intervals`() {
        val estimator = SampleRateEstimator(windowSize = 10)
        // 20 slow samples, then 20 fast ones: the window should reflect only the
        // fast tail once it has been pushed through.
        repeat(20) { estimator.record(it * 20L * msToNanos) }
        val slowBase = 20 * 20L * msToNanos
        repeat(20) { estimator.record(slowBase + it * 4L * msToNanos) }

        val stats = estimator.stats()!!
        assertEquals(10, stats.intervalCount)
        assertEquals(4.0, stats.meanIntervalMs, 0.001)
        assertEquals(250.0, stats.hz, 0.001)
    }

    @Test
    fun `captures jitter and the interval extremes`() {
        val estimator = SampleRateEstimator()
        // Intervals of 5, 15, 5, 15 ms: mean 10, spread 5. The first record only
        // establishes a reference point, so it has to precede the gaps.
        var t = 0L
        estimator.record(t)
        listOf(5L, 15L, 5L, 15L).forEach { gap ->
            t += gap * msToNanos
            estimator.record(t)
        }

        val stats = estimator.stats()!!
        assertEquals(10.0, stats.meanIntervalMs, 0.001)
        assertEquals(5.0, stats.minIntervalMs, 0.001)
        assertEquals(15.0, stats.maxIntervalMs, 0.001)
        assertEquals(5.0, stats.jitterMs, 0.001)
    }

    @Test
    fun `reset clears accumulated state`() {
        val estimator = SampleRateEstimator()
        repeat(10) { estimator.record(it * 5L * msToNanos) }
        estimator.reset()
        assertNull(estimator.stats())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a degenerate window`() {
        SampleRateEstimator(windowSize = 1)
    }
}
