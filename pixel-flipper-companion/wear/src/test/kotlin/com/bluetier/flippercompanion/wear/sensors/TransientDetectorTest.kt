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
        val feeder = Feeder(TransientDetector(), hz = 200.0)
        feeder.quiet(500)

        assertTrue("gravity should not look like a snap", feeder.events.isEmpty())
    }

    @Test
    fun `accepts a short sharp low-rotation spike`() {
        val feeder = Feeder(TransientDetector(), hz = 200.0)
        feeder.quiet(50)
        feeder.feed(25.0) // opens: deviation ~15.2
        feeder.feed(18.0) // still above the exit threshold
        feeder.feed(11.0) // closes: deviation ~1.2

        assertEquals(1, feeder.events.size)
        val event = feeder.events.single()
        assertTrue("expected accepted, got ${event.rejections}", event.accepted)
        assertEquals(15.19, event.peakDeviation, 0.01)
        assertEquals(10.0, event.durationMs, 0.01)
        assertEquals(3, event.sampleCount)
    }

    @Test
    fun `rejects a spike that never gets sharp enough`() {
        val feeder = Feeder(TransientDetector(), hz = 200.0)
        feeder.quiet(50)
        feeder.feed(16.5) // deviation ~6.7: over enter (6.0), under minPeak (8.0)
        feeder.feed(10.5)

        val event = feeder.events.single()
        assertEquals(setOf(RejectReason.WEAK_PEAK), event.rejections)
    }

    @Test
    fun `rejects a wrist flick on angular rate`() {
        val feeder = Feeder(TransientDetector(), hz = 200.0)
        feeder.quiet(50)
        // Same acceleration shape as an accepted snap, but the wrist rotated.
        feeder.feed(25.0, gyro = 10.0)
        feeder.feed(11.0)

        val event = feeder.events.single()
        assertEquals(setOf(RejectReason.TOO_MUCH_ROTATION), event.rejections)
        assertTrue(event.peakDeviation > 8.0)
    }

    @Test
    fun `rejects sustained motion as too long`() {
        val feeder = Feeder(TransientDetector(), hz = 200.0)
        feeder.quiet(50)
        repeat(20) { feeder.feed(25.0) } // an arm swing, not a snap

        val event = feeder.events.single()
        assertEquals(setOf(RejectReason.TOO_LONG), event.rejections)
        assertTrue("expected > 60 ms, was ${event.durationMs}", event.durationMs > 60.0)
    }

    @Test
    fun `debounce suppresses a second spike inside the dead time`() {
        val feeder = Feeder(TransientDetector(), hz = 200.0)
        feeder.quiet(50)
        feeder.feed(25.0)
        feeder.feed(11.0)
        feeder.quiet(20) // 100 ms, inside the 250 ms debounce
        feeder.feed(25.0)
        feeder.feed(11.0)

        assertEquals("one snap, not two", 1, feeder.events.size)
    }

    @Test
    fun `a second spike after the dead time is detected`() {
        val feeder = Feeder(TransientDetector(), hz = 200.0)
        feeder.quiet(50)
        feeder.feed(25.0)
        feeder.feed(11.0)
        feeder.quiet(80) // 400 ms, clear of the debounce
        feeder.feed(25.0)
        feeder.feed(11.0)

        assertEquals(2, feeder.events.size)
        assertTrue(feeder.events.all { it.accepted })
    }

    @Test
    fun `slow arm motion is absorbed by the baseline`() {
        val feeder = Feeder(TransientDetector(), hz = 200.0)
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
    fun `at 100 Hz a snap spans only two samples`() {
        // The headline number for the sample-rate spike. If this is what the
        // Pixel Watch 3 actually delivers, the IMU cannot characterise a snap's
        // shape on its own and the microphone has to carry the decision.
        val feeder = Feeder(TransientDetector(), hz = 100.0)
        feeder.quiet(50)
        feeder.feed(25.0)
        feeder.feed(11.0)

        val event = feeder.events.single()
        assertTrue(event.accepted)
        assertEquals(2, event.sampleCount)
        assertEquals(10.0, event.durationMs, 0.01)
    }

    @Test
    fun `reset clears an open transient`() {
        val detector = TransientDetector()
        val feeder = Feeder(detector, hz = 200.0)
        feeder.quiet(50)
        feeder.feed(25.0) // leaves a transient open
        detector.reset()
        feeder.quiet(50)

        assertTrue(feeder.events.isEmpty())
    }

    @Test
    fun `config rejects inverted thresholds`() {
        val error = runCatching {
            TransientConfig(enterThreshold = 2.0, exitThreshold = 6.0)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a tuned config changes the verdict on the same signal`() {
        // The spike's whole purpose: the same wrist data, re-judged.
        val strict = Feeder(TransientDetector(TransientConfig(minPeak = 20.0)), hz = 200.0)
        strict.quiet(50)
        strict.feed(25.0)
        strict.feed(11.0)
        assertEquals(setOf(RejectReason.WEAK_PEAK), strict.events.single().rejections)

        val lenient = Feeder(TransientDetector(TransientConfig(minPeak = 5.0)), hz = 200.0)
        lenient.quiet(50)
        lenient.feed(25.0)
        lenient.feed(11.0)
        assertTrue(lenient.events.single().accepted)
    }
}
