package com.bluetier.flippercompanion.wear.sensors

/** Why a completed transient was not accepted as a snap candidate. */
enum class RejectReason {
    /** Lasted longer than a snap can: an arm swing, a step, a knock. */
    TOO_LONG,

    /** Crossed the enter threshold but never reached [TransientConfig.minPeak]. */
    WEAK_PEAK,

    /** Carried too much angular rate: a wrist flick or a hand wave, not a snap. */
    TOO_MUCH_ROTATION,
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
    /** A transient must peak at least this high to count. */
    val minPeak: Double = 8.0,
    /** Longer than this is not a snap. */
    val maxDurationMs: Double = 60.0,
    /** Peak angular rate above this means the wrist rotated, so not a snap. */
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
        if (peakGyro > config.maxGyro) add(RejectReason.TOO_MUCH_ROTATION)
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
