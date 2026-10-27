package com.bluetier.flippercompanion.wear.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the detector's gates to real measurements rather than to guesses.
 *
 * Every figure here comes from a capture session on a Pixel Watch 3 at 198 Hz:
 * 55 transients across snaps (relaxed and while pointing), claps, knocks and
 * walking. The thresholds in [TransientConfig] were chosen from this data, so
 * these tests fail if someone later moves a threshold past what the wrist
 * actually produced.
 *
 * The summary of each transient is what is checked — peak, rotation, span —
 * because that is what the gates act on. [TransientDetectorTest] covers the
 * state machine that produces those values.
 */
class MeasuredWristTest {

    /** Peak deviation (m/s^2), peak angular rate (rad/s), sample count. */
    private data class Measured(val peak: Double, val gyro: Double, val samples: Int)

    /** Accepted snaps with the arm relaxed. */
    private val relaxedSnaps = listOf(
        Measured(23.2, 1.18, 12), Measured(38.2, 4.28, 10), Measured(36.6, 2.13, 9),
        Measured(28.8, 3.32, 11), Measured(26.6, 3.85, 10), Measured(37.4, 2.44, 9),
        Measured(28.3, 3.18, 11), Measured(31.3, 1.87, 9), Measured(48.3, 2.03, 10),
        Measured(44.9, 2.75, 9), Measured(44.9, 3.74, 10), Measured(44.0, 1.29, 9),
        Measured(31.9, 1.79, 11), Measured(35.2, 1.99, 9), Measured(36.3, 4.24, 9),
        Measured(44.9, 4.01, 9), Measured(24.9, 3.59, 10), Measured(32.0, 3.08, 9),
        Measured(39.2, 1.85, 10), Measured(26.0, 1.61, 10), Measured(32.8, 1.94, 10),
    )

    /** Snaps made while pointing at something across the room - the real gesture. */
    private val pointingSnaps = listOf(
        Measured(50.1, 1.78, 10), Measured(57.4, 2.76, 8), Measured(59.1, 3.00, 12),
        Measured(60.6, 5.94, 8), Measured(62.8, 3.82, 8),
    )

    /** Claps: the false positive the original thresholds let through. */
    private val claps = listOf(
        Measured(87.8, 4.79, 12), Measured(131.5, 5.03, 13), Measured(129.8, 5.12, 6),
        Measured(135.2, 3.79, 13), Measured(74.1, 7.10, 6),
    )

    /** A knock: a one- or two-sample blip, also previously accepted. */
    private val knock = Measured(11.1, 0.41, 2)

    private val snaps get() = relaxedSnaps + pointingSnaps

    /** Applies the gates [TransientDetector.verdict] applies, to a measured summary. */
    private fun rejections(m: Measured, config: TransientConfig = TransientConfig()) = buildSet {
        if (m.peak < config.minPeak) add(RejectReason.WEAK_PEAK)
        if (m.peak > config.maxPeak) add(RejectReason.TOO_STRONG)
        if (m.gyro > config.maxGyro) add(RejectReason.TOO_MUCH_ROTATION)
        if (m.samples < config.minSampleCount) add(RejectReason.TOO_BRIEF)
    }

    @Test
    fun `every snap made while pointing passes the gates`() {
        // Pointing is the pose this gesture uses, so these are the ones that
        // must never be lost. They run 50.1 to 62.8, comfortably clear of the
        // floor.
        pointingSnaps.forEach { snap ->
            assertEquals("snap $snap should be accepted", emptySet<RejectReason>(), rejections(snap))
        }
    }

    @Test
    fun `the weakest relaxed snaps fall below the floor, and that is intended`() {
        // Snaps made with the arm hanging at the side reach only 24.9 to 28.8,
        // inside the 16-to-27 band that hands rubbing on fabric produce. A
        // labelled session had five such transients accepted as snaps while
        // their owner was drying their hands.
        //
        // Nothing separates a weak relaxed snap from a wipe, so the floor
        // cannot admit one without the other. It admits neither, and the
        // gesture asks for a snap made while actually pointing - which is the
        // only kind it was ever going to act on anyway.
        val tooWeak = relaxedSnaps.filter { it.peak < TransientConfig().minPeak }

        assertTrue("some relaxed snaps are expected below the floor", tooWeak.isNotEmpty())
        tooWeak.forEach { snap ->
            assertTrue(
                "relaxed snap $snap is below the floor and should be rejected",
                RejectReason.WEAK_PEAK in rejections(snap),
            )
        }
    }

    @Test
    fun `all but one measured clap is still rejected`() {
        // Four of the five: three on peak, and the 74.1 one on rotation, which
        // carried 7.10 rad/s. The exception is deliberate and is the next test.
        val rejected = claps.count { rejections(it).isNotEmpty() }

        assertEquals(claps.size - 1, rejected)
    }

    @Test
    fun `one clap now passes the gates, and that is the price of the ceiling`() {
        // The 87.8 clap clears a maxPeak of 100 with 12 samples and 4.79 rad/s,
        // and nothing else in the measured set separates it from a snap.
        //
        // This is not an oversight to be fixed later. The ceiling moved because
        // deliberate snaps on a real wrist were being refused at 79.0 and 93.3,
        // and no bound can admit those while excluding a clap at 87.8. A clap
        // aimed at a television is rare and undoes itself; a snap that works
        // half the time makes the gesture not worth using. Recorded here so the
        // trade is visible rather than discovered.
        val borderline = claps.single { it.peak == 87.8 }

        assertTrue(
            "the known-accepted clap should still be accepted: ${rejections(borderline)}",
            rejections(borderline).isEmpty(),
        )
    }

    @Test
    fun `a knock is rejected for spanning too few samples`() {
        assertTrue(RejectReason.TOO_BRIEF in rejections(knock))
    }

    @Test
    fun `the peak ceiling clears the bench snaps and most of the claps`() {
        // There was a gap here once: bench snaps topped out at 62.8 and claps
        // started at 74.1, so a bound between them separated the two cleanly.
        //
        // The field closed that gap. Deliberate snaps were refused at 79.0 and
        // 93.3 on a wrist this data never covered, which puts real snaps inside
        // the clap range and makes peak alone an imperfect discriminator. The
        // ceiling is now a bound on absurdity rather than a dividing line.
        val strongestSnap = snaps.maxOf { it.peak }
        val ceiling = TransientConfig().maxPeak

        assertTrue("every measured snap must pass: $strongestSnap", strongestSnap < ceiling)
        assertTrue(
            "the ceiling must still reject the hardest claps",
            claps.count { it.peak > ceiling } >= 3,
        )
    }

    @Test
    fun `pointing does not weaken the signal`() {
        // The gesture is point-then-snap, so detection had to hold up with the
        // arm raised. It does: pointing snaps hit harder than relaxed ones.
        val relaxedMax = relaxedSnaps.maxOf { it.peak }
        val pointingMin = pointingSnaps.minOf { it.peak }

        assertTrue(
            "pointing snaps ($pointingMin+) should not be weaker than relaxed ones (max $relaxedMax)",
            pointingMin > relaxedMax * 0.8,
        )
        assertTrue(pointingSnaps.all { rejections(it).isEmpty() })
    }

    @Test
    fun `the rotation gate separates snaps from high-peak walking`() {
        // Walking transients that reached snap-like peaks all carried more
        // rotation than any snap did. The margin is real but narrow.
        val snapGyroMax = snaps.maxOf { it.gyro }
        val walkingGyroMin = 6.72 // lowest among walking transients peaking above 50

        assertTrue("snaps reached $snapGyroMax rad/s", snapGyroMax < walkingGyroMin)
        assertTrue(TransientConfig().maxGyro in snapGyroMax..walkingGyroMin)
    }

    @Test
    fun `the tuned gates keep every pointing snap, and admit one clap knowingly`() {
        val strongRelaxed = relaxedSnaps.count { rejections(it).isEmpty() }
        val accepted = (snaps + claps + listOf(knock)).count { rejections(it).isEmpty() }

        // Every pointing snap, the stronger relaxed ones, the one documented
        // clap, and nothing else: the knock, the four remaining claps and the
        // relaxed snaps that sit in the hand-rubbing band all stay out.
        assertEquals(pointingSnaps.size + strongRelaxed + 1, accepted)
        assertTrue(
            "no snap made while pointing may be lost",
            pointingSnaps.all { rejections(it).isEmpty() },
        )
    }

    @Test
    fun `the previous thresholds accepted claps - this is the regression`() {
        // Without an upper bound on peak, two of these claps were accepted as
        // snaps on a real wrist. Guards the reason maxPeak exists.
        //
        // minPeak is pinned to the 8.0 of that era rather than inherited: this
        // test reconstructs a past configuration, so letting it track today's
        // defaults would quietly change what it claims to prove. Raising
        // minPeak to 15.0 is exactly such a change - it now catches the knock
        // on peak height alone, where before only the sample-count gate did.
        val old = TransientConfig(
            minPeak = 8.0,
            maxPeak = Double.MAX_VALUE,
            minSampleCount = 1,
        )

        assertTrue(claps.count { rejections(it, old).isEmpty() } > 0)
        assertTrue(rejections(knock, old).isEmpty())
    }
}
