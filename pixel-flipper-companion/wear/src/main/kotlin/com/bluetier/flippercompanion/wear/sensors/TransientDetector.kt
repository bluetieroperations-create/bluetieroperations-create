package com.bluetier.flippercompanion.wear.sensors

/** Why a completed transient was not accepted as a snap candidate. */
enum class RejectReason {
    /** Lasted longer than a snap can: an arm swing, a step, a knock. */
    TOO_LONG,

    /** Crossed the enter threshold but never reached [TransientConfig.minPeak]. */
    WEAK_PEAK,

    /** Carried too much angular rate: a wrist flick or a hand wave, not a snap. */
    TOO_MUCH_ROTATION,

    /**
     * Hit harder than a snap can. Measured on a wrist, a clap peaks at 74-135
     * m/s^2 where a snap tops out near 63: two hands colliding carry far more
     * energy than a finger slipping off a thumb.
     */
    TOO_STRONG,

    /**
     * Over too few samples to be a snap's shape. Real snaps span 8-12 samples
     * at ~198 Hz; a one- or two-sample blip is a knock or a bump.
     */
    TOO_BRIEF,
}

/**
 * Tuning for [TransientDetector]. Every value is a parameter rather than a
 * constant because finding the right ones on a real wrist *is* the experiment;
 * the defaults are a starting point, not a result.
 *
 * Units: acceleration in m/s^2 (deviation from a slow baseline, so gravity is
 * already removed), angular rate in rad/s, times in milliseconds.
 */
data class TransientConfig(
    /** Deviation that opens a transient. */
    val enterThreshold: Double = 6.0,
    /** Deviation that closes it. Below [enterThreshold] to give hysteresis. */
    val exitThreshold: Double = 2.0,
    /**
     * A transient must peak at least this high to count.
     *
     * 8.0 came from a bench and was far too low in a room, accepting arm
     * movements at 8.2, 9.2 and 10.7. 15.0 was better and still too low: a
     * labelled session caught five transients at 16.5 to 26.8 accepted as
     * snaps while their owner was wiping their hands on their shorts.
     *
     * Rubbing fabric peaks at 16 to 27. Snaps made while pointing at something
     * peak at 50 to 100 in the field, and 50.1 to 62.8 on the bench. 30 sits
     * in that gap.
     *
     * It does reject the weakest bench snaps, around 25 - but those were made
     * with the arm relaxed at the side, which is not how anyone points at a
     * television. For the pose this gesture actually uses, nothing measured
     * comes close to the floor.
     *
     * A gesture that fires when its owner dries their hands is not a sensitive
     * gesture, it is a broken one.
     */
    val minPeak: Double = 30.0,
    /**
     * And no higher than this.
     *
     * 68 sat in the gap between bench snaps, which topped out at 62.8, and
     * claps, which started at 74.1. That gap does not exist on this wrist:
     * field logs show deliberate snaps at 79.0 and 93.3 being refused as too
     * hard, in the same minute as accepted ones at 37.4 and 57.2. How hard a
     * snap reads depends on the hand, the band and where the fingers are
     * relative to the watch, and a bound drawn from one session of one wrist
     * was never going to hold.
     *
     * 100 overlaps the measured clap range, which is a real cost: a clap while
     * pointing at something can now register. That is the lesser failure. A
     * clap aimed at a television is rare and recoverable; a snap that works
     * half the time is the thing that makes the whole gesture not worth using.
     */
    val maxPeak: Double = 100.0,
    /**
     * Fewest samples a transient may span. Measured snaps cover 8-12 samples;
     * this rejects the one- and two-sample blips a knock produces without
     * coming near a real snap.
     */
    val minSampleCount: Int = 5,
    /**
     * Longer than this is not a snap.
     *
     * 60 cut off at 12 samples on a 198 Hz stream, and the field logs are full
     * of 13-sample transients peaking at 52 to 86 - snap-shaped, snap-sized,
     * and a single sample over the line. 80 allows about 16.
     *
     * Safer to widen now than when it was written: the peak floor at 15 does
     * the work this bound used to share, since the arm swings and footsteps
     * that run long are also weak.
     *
     * 80 was still a sample short. The next field session rejected eight
     * seventeen-sample transients peaking at 81, 83, 97, 100 and 125 - all
     * snaps, all 86 ms against an 80 ms bound. 110 leaves room rather than
     * landing on the next edge, and costs nothing in the measured set, where
     * no clap or knock runs past thirteen samples.
     */
    val maxDurationMs: Double = 110.0,
    /**
     * Peak angular rate above this means the wrist rotated, so not a snap.
     *
     * Stays at 6.0 despite field logs showing two rejections at 7.3 and 8.4
     * that peaked near 50 and looked like snaps thrown away. The measured set
     * says otherwise: every recorded snap stayed under 6.72 rad/s, and that
     * figure is the *lowest* rotation among walking transients that reached
     * snap-like peaks. A high peak with 7-8 rad/s of rotation is the signature
     * of a footstep traveling up the body, not of a snap.
     *
     * Two unlabelled field samples do not outweigh fifty-five labelled ones.
     */
    val maxGyro: Double = 6.0,
    /** Dead time after any transient, to avoid double-counting one snap. */
    val debounceMs: Double = 250.0,
    /** EMA weight for the slow baseline. Small = slow to follow arm motion. */
    val baselineAlpha: Double = 0.02,
) {
    init {
        require(exitThreshold < enterThreshold) {
            "exitThreshold ($exitThreshold) must be below enterThreshold ($enterThreshold)"
        }
        require(minPeak < maxPeak) {
            "minPeak ($minPeak) must be below maxPeak ($maxPeak)"
        }
        require(baselineAlpha > 0.0 && baselineAlpha <= 1.0) {
            "baselineAlpha must be in (0, 1], was $baselineAlpha"
        }
    }
}

/**
 * One completed acceleration transient, accepted or not.
 *
 * Rejected events are reported too, deliberately: tuning a snap detector is
 * mostly a matter of looking at the near-misses, so the caller gets everything
 * and decides what to keep.
 */
data class TransientEvent(
    /** Sensor timestamp of the peak sample. */
    val peakNanos: Long,
    /** Sensor timestamp of the sample that opened the transient. */
    val startNanos: Long,
    /** Peak deviation from baseline, in m/s^2. */
    val peakDeviation: Double,
    /** The frozen baseline this deviation was measured against. */
    val baseline: Double,
    /** Highest angular rate seen during the transient, in rad/s. */
    val peakGyro: Double,
    /** Start to peak. */
    val riseTimeMs: Double,
    /**
     * Start to close. For a [RejectReason.TOO_LONG] event this is the time at
     * which it was abandoned, not a true duration.
     */
    val durationMs: Double,
    /**
     * How many samples fell inside the transient. The headline number for the
     * sample-rate spike: if snaps routinely span one or two samples, the stream
     * is too slow to characterise them and the mic has to carry the decision.
     */
    val sampleCount: Int,
    val rejections: Set<RejectReason>,
) {
    val accepted: Boolean get() = rejections.isEmpty()
}

/**
 * Detects short, sharp acceleration transients in a single IMU stream — the
 * shape a finger snap makes at the wrist.
 *
 * A snap is distinguished from the things that otherwise look like it by three
 * gates: it is *brief* (an arm swing is not), it is *sharp* (a footstep
 * travelling up the body is not), and it involves almost no *rotation* (a wrist
 * flick does). The rotation gate is the one that matters most in practice.
 *
 * Works on the deviation of acceleration magnitude from a slow EMA baseline, so
 * gravity and gradual arm motion fall out without a separate high-pass filter.
 * The baseline is frozen while a transient is open so the spike cannot drag its
 * own reference up behind it.
 *
 * Pure and deterministic — timestamps come from the caller — so the whole state
 * machine is unit-testable without a watch.
 */
class TransientDetector(private val config: TransientConfig = TransientConfig()) {

    private enum class State { IDLE, IN_TRANSIENT, REFRACTORY }

    private var state = State.IDLE
    private var baseline: Double? = null

    private var startNanos = 0L
    private var peakNanos = 0L
    private var peakDeviation = 0.0
    private var peakGyro = 0.0
    private var frozenBaseline = 0.0
    private var sampleCount = 0
    private var refractoryStartNanos = 0L

    /**
     * Feeds one fused IMU sample.
     *
     * [accelMagnitude] is the magnitude of the raw accelerometer vector
     * (gravity included — the baseline removes it) and [gyroMagnitude] the
     * magnitude of the gyroscope vector at the same moment, or 0.0 if no
     * gyroscope sample is available.
     *
     * Returns a [TransientEvent] on the sample that *closes* a transient, and
     * null otherwise. Check [TransientEvent.accepted] before treating one as a
     * snap.
     */
    fun onSample(
        timestampNanos: Long,
        accelMagnitude: Double,
        gyroMagnitude: Double,
    ): TransientEvent? {
        // Seed the baseline from the first sample. Without this, the first
        // sample reads as a ~9.8 m/s^2 deviation from zero and fires instantly.
        val currentBaseline = baseline
        if (currentBaseline == null) {
            baseline = accelMagnitude
            return null
        }

        val deviation = kotlin.math.abs(accelMagnitude - currentBaseline)

        return when (state) {
            State.REFRACTORY -> {
                baseline = ema(currentBaseline, accelMagnitude)
                val elapsedMs = (timestampNanos - refractoryStartNanos) / 1_000_000.0
                if (elapsedMs >= config.debounceMs) state = State.IDLE
                null
            }

            State.IDLE -> {
                if (deviation >= config.enterThreshold) {
                    state = State.IN_TRANSIENT
                    startNanos = timestampNanos
                    peakNanos = timestampNanos
                    peakDeviation = deviation
                    peakGyro = gyroMagnitude
                    frozenBaseline = currentBaseline
                    sampleCount = 1
                } else {
                    baseline = ema(currentBaseline, accelMagnitude)
                }
                null
            }

            State.IN_TRANSIENT -> {
                sampleCount++
                if (deviation > peakDeviation) {
                    peakDeviation = deviation
                    peakNanos = timestampNanos
                }
                if (gyroMagnitude > peakGyro) peakGyro = gyroMagnitude

                val durationMs = (timestampNanos - startNanos) / 1_000_000.0

                when {
                    durationMs > config.maxDurationMs ->
                        close(timestampNanos, durationMs, setOf(RejectReason.TOO_LONG))

                    deviation <= config.exitThreshold ->
                        close(timestampNanos, durationMs, verdict())

                    else -> null
                }
            }
        }
    }

    private fun verdict(): Set<RejectReason> = buildSet {
        if (peakDeviation < config.minPeak) add(RejectReason.WEAK_PEAK)
        if (peakDeviation > config.maxPeak) add(RejectReason.TOO_STRONG)
        if (peakGyro > config.maxGyro) add(RejectReason.TOO_MUCH_ROTATION)
        if (sampleCount < config.minSampleCount) add(RejectReason.TOO_BRIEF)
    }

    private fun close(
        timestampNanos: Long,
        durationMs: Double,
        rejections: Set<RejectReason>,
    ): TransientEvent {
        state = State.REFRACTORY
        refractoryStartNanos = timestampNanos
        return TransientEvent(
            peakNanos = peakNanos,
            startNanos = startNanos,
            peakDeviation = peakDeviation,
            baseline = frozenBaseline,
            peakGyro = peakGyro,
            riseTimeMs = (peakNanos - startNanos) / 1_000_000.0,
            durationMs = durationMs,
            sampleCount = sampleCount,
            rejections = rejections,
        )
    }

    private fun ema(current: Double, sample: Double): Double =
        current + config.baselineAlpha * (sample - current)

    fun reset() {
        state = State.IDLE
        baseline = null
        sampleCount = 0
    }
}
