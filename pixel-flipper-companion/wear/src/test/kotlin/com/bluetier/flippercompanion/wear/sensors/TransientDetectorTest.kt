package com.bluetier.flippercompanion.wear.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds a synthetic IMU stream at a fixed rate and collects whatever the
 * detector emits, so each test reads as the signal it describes.
 */
private class Feeder(
    val detector: TransientDetector,
    hz: Double,
    val baseline: Double = GRAVITY,
) {
    private val intervalNanos = (1_000_000_000.0 / hz).toLong()
    private var t = 0L
    val events = mutableListOf<TransientEvent>()

    fun feed(accel: Double, gyro: Double = 0.0) {
        detector.onSample(t, accel, gyro)?.let(events::add)
        t += intervalNanos
    }

    fun quiet(samples: Int) = repeat(samples) { feed(baseline) }

    /**
     * A transient shaped like a snap measured on a wrist: a sharp onset that
     * rings down over roughly 45 ms, then falls below the exit threshold.
     *
     * [samples] is how many the stream captures. At 198 Hz a real snap covers
     * 9-12; the default matches that. Earlier versions of these tests used two
     * or three samples, from an assumption that a snap lasts 10-20 ms — the
     * recorded data in MeasuredWristTest shows it lasts 35-60 ms.
     */
    fun snapLike(peak: Double, gyro: Double = 2.0, samples: Int = 10) {
        require(samples >= 2) { "a transient needs at least two samples" }
        repeat(samples - 1) { i ->
            val decay = 1.0 - (i.toDouble() / (samples - 1)) * 0.95
            feed(baseline + peak * decay, gyro)
        }
        feed(baseline + 0.5, gyro) // below the exit threshold: closes the transient
    }

    companion object {
        /** A still wrist reads about 1 g on the accelerometer. */
        const val GRAVITY = 9.81
    }
}

class TransientDetectorTest {

    @Test
    fun `gravity alone never fires`() {
        // Regression guard: the baseline must be seeded from the first sample,
        // or sample one reads as a ~9.8 m per s2 deviation from zero and fires.
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(500)

        assertTrue("gravity should not look like a snap", feeder.events.isEmpty())
    }

    @Test
    fun `accepts a snap-shaped transient`() {
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(50)
        feeder.snapLike(peak = 35.0)

        val event = feeder.events.single()
        assertTrue("expected accepted, got ${event.rejections}", event.accepted)
        assertEquals(35.0, event.peakDeviation, 0.01)
        assertEquals(10, event.sampleCount)
    }

    @Test
    fun `rejects a transient that never gets sharp enough`() {
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(50)
        // Over the enter threshold (6.0), under minPeak (8.0).
        feeder.snapLike(peak = 7.0)

        assertEquals(setOf(RejectReason.WEAK_PEAK), feeder.events.single().rejections)
    }

    @Test
    fun `rejects a hard clap on peak`() {
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(50)
        // Claps measured on a wrist peak at 74-135. The ceiling sits at 100,
        // high enough for the 79-93 snaps the field produced, so only the
        // harder claps are caught here - see MeasuredWristTest for the one
        // that is knowingly let through.
        feeder.snapLike(peak = 120.0)

        assertEquals(setOf(RejectReason.TOO_STRONG), feeder.events.single().rejections)
    }

    @Test
    fun `rejects a wrist flick on angular rate`() {
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(50)
        // The same acceleration shape as a snap, but the wrist rotated.
        feeder.snapLike(peak = 35.0, gyro = 10.0)

        assertEquals(setOf(RejectReason.TOO_MUCH_ROTATION), feeder.events.single().rejections)
    }

    @Test
    fun `rejects a one-or-two sample blip as too brief`() {
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(50)
        // A knock: strong enough to open a transient, over almost immediately.
        feeder.feed(Feeder.GRAVITY + 20.0)
        feeder.feed(Feeder.GRAVITY + 0.5)

        val event = feeder.events.single()
        assertTrue(RejectReason.TOO_BRIEF in event.rejections)
        assertEquals(2, event.sampleCount)
    }

    @Test
    fun `rejects sustained motion as too long`() {
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(50)
        // 40 samples is about 200 ms: an arm swing, a reach, a door being
        // pushed. The fixture used to feed 20, which is 101 ms - shorter than
        // some of the snaps measured in the field, so it was never really
        // describing sustained motion.
        repeat(40) { feeder.feed(Feeder.GRAVITY + 25.0) }

        val event = feeder.events.single()
        assertEquals(setOf(RejectReason.TOO_LONG), event.rejections)
        assertTrue(
            "expected longer than the 110 ms bound, was ${event.durationMs}",
            event.durationMs > 110.0,
        )
    }

    @Test
    fun `debounce suppresses a second snap inside the dead time`() {
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(50)
        feeder.snapLike(peak = 35.0)
        feeder.quiet(20) // ~100 ms, inside the 250 ms debounce
        feeder.snapLike(peak = 35.0)

        assertEquals("one snap, not two", 1, feeder.events.size)
    }

    @Test
    fun `a second snap after the dead time is detected`() {
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(50)
        feeder.snapLike(peak = 35.0)
        feeder.quiet(80) // ~400 ms, clear of the debounce
        feeder.snapLike(peak = 35.0)

        assertEquals(2, feeder.events.size)
        assertTrue(feeder.events.all { it.accepted })
    }

    @Test
    fun `slow arm motion is absorbed by the baseline`() {
        val feeder = Feeder(TransientDetector(), hz = 198.0)
        feeder.quiet(50)
        // A gentle ramp: the EMA baseline lags by roughly step/alpha, which stays
        // well under the enter threshold.
        var accel = Feeder.GRAVITY
        repeat(200) {
            accel += 0.03
            feeder.feed(accel)
        }

        assertTrue("a slow lift should not look like a snap", feeder.events.isEmpty())
    }

    @Test
    fun `at 100 Hz a snap only just clears the sample-count gate`() {
        // Why the achieved rate matters. A ~40 ms snap covers about 10 samples
        // at 198 Hz but only 5 at 100 Hz — exactly minSampleCount, with nothing
        // to spare. A slower stream than this cannot see a snap's shape at all.
        val feeder = Feeder(TransientDetector(), hz = 100.0)
        feeder.quiet(50)
        feeder.snapLike(peak = 35.0, samples = 5)

        val event = feeder.events.single()
        assertTrue("expected accepted, got ${event.rejections}", event.accepted)
        assertEquals(5, event.sampleCount)
        assertEquals(TransientConfig().minSampleCount, event.sampleCount)
    }

    @Test
    fun `below the sample-count gate the same snap is rejected`() {
        val feeder = Feeder(TransientDetector(), hz = 60.0)
        feeder.quiet(50)
        feeder.snapLike(peak = 35.0, samples = 3)

        assertTrue(RejectReason.TOO_BRIEF in feeder.events.single().rejections)
    }

    @Test
    fun `reset clears an open transient`() {
        val detector = TransientDetector()
        val feeder = Feeder(detector, hz = 198.0)
        feeder.quiet(50)
        feeder.feed(Feeder.GRAVITY + 25.0) // leaves a transient open
        detector.reset()
        feeder.quiet(50)

        assertTrue(feeder.events.isEmpty())
    }

    @Test
    fun `config rejects inverted thresholds`() {
        assertTrue(
            runCatching { TransientConfig(enterThreshold = 2.0, exitThreshold = 6.0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { TransientConfig(minPeak = 80.0, maxPeak = 10.0) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `a tuned config changes the verdict on the same signal`() {
        // The spike's whole purpose: the same wrist data, re-judged.
        val strict = Feeder(TransientDetector(TransientConfig(minPeak = 50.0)), hz = 198.0)
        strict.quiet(50)
        strict.snapLike(peak = 35.0)
        assertEquals(setOf(RejectReason.WEAK_PEAK), strict.events.single().rejections)

        val lenient = Feeder(TransientDetector(TransientConfig(minPeak = 5.0)), hz = 198.0)
        lenient.quiet(50)
        lenient.snapLike(peak = 35.0)
        assertTrue(lenient.events.single().accepted)
    }
}
