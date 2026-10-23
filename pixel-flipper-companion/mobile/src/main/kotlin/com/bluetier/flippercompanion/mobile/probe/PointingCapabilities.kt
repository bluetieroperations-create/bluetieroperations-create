package com.bluetier.flippercompanion.mobile.probe

/**
 * The sensors that matter for working out what the user is pointing at.
 *
 * A deliberately small enum rather than `android.hardware.Sensor` constants, so
 * this model and the strategy decision below stay free of framework types and
 * can run as plain JVM unit tests.
 */
enum class PointingSensor {
    /** Fused orientation including the magnetometer: absolute heading, but drags magnetic error in with it. */
    ROTATION_VECTOR,

    /** Fused orientation *without* the magnetometer: immune to magnetic junk, but drifts and has no true north. */
    GAME_ROTATION_VECTOR,

    /** Low-power fused orientation. Coarser; listed for completeness. */
    GEOMAGNETIC_ROTATION_VECTOR,

    MAGNETOMETER,
    ACCELEROMETER,
    GYROSCOPE,
}

/** What one sensor on this device can actually do. */
data class SensorCapability(
    val sensor: PointingSensor,
    val vendor: String,
    /**
     * Maximum rate the hardware advertises, from its minimum delay. This is the
     * hardware ceiling; what a running app is *delivered* can be lower, and on
     * API 31+ is capped at 200 Hz without `HIGH_SAMPLING_RATE_SENSORS`.
     */
    val maxRateHz: Double,
    val resolution: Float,
)

/**
 * How this device should resolve a pointing gesture, best first.
 *
 * The gap between the first option and the rest is large: angle of arrival is a
 * real bearing measurement, whereas everything below it is a compass heading
 * matched against targets registered from a known spot.
 */
enum class PointingStrategy {
    /**
     * UWB ranging plus angle of arrival. Roughly +/-5 degrees and ~10 cm, and it
     * does not care about magnetic interference. Needs UWB anchors as well as a
     * UWB phone, and `androidx.core.uwb` to actually range.
     */
    UWB_ANGLE_OF_ARRIVAL,

    /**
     * Compass heading and pitch from the fused rotation vector, matched against
     * targets calibrated from a known standing position. Workable, but indoor
     * magnetometer error of +/-10-20 degrees sets the floor on how close
     * together two targets can be.
     */
    FUSED_ROTATION_VECTOR,

    /**
     * Relative orientation only. Usable, but with no absolute reference the
     * registry has to be re-zeroed against a known direction regularly, so it is
     * a fallback rather than a plan.
     */
    GAME_ROTATION_VECTOR_ONLY,

    /** Nothing here can establish a direction. */
    UNSUPPORTED,
}

/**
 * What this phone can offer the pointing resolver.
 *
 * [uwbPresent] comes from a system-feature query, which reports that the
 * hardware exists — not that it is switched on, and not that any anchor is in
 * range. Confirming those needs `androidx.core.uwb`, which is the follow-up
 * this probe is meant to justify or rule out.
 */
data class PointingCapabilities(
    val uwbPresent: Boolean,
    val sensors: List<SensorCapability>,
    val deviceModel: String = "",
    val androidApiLevel: Int = 0,
) {
    fun has(sensor: PointingSensor): Boolean = sensors.any { it.sensor == sensor }

    fun capability(sensor: PointingSensor): SensorCapability? =
        sensors.firstOrNull { it.sensor == sensor }

    /**
     * The decision this whole probe exists to make, encoded rather than left to
     * a human reading a spec sheet.
     */
    val strategy: PointingStrategy
        get() = when {
            uwbPresent -> PointingStrategy.UWB_ANGLE_OF_ARRIVAL
            has(PointingSensor.ROTATION_VECTOR) -> PointingStrategy.FUSED_ROTATION_VECTOR
            has(PointingSensor.GAME_ROTATION_VECTOR) -> PointingStrategy.GAME_ROTATION_VECTOR_ONLY
            else -> PointingStrategy.UNSUPPORTED
        }

    /**
     * True when the fused rotation vector is available but the magnetometer is
     * not, which would make its heading unreliable in a way the sensor type
     * alone does not advertise.
     */
    val rotationVectorLacksMagnetometer: Boolean
        get() = has(PointingSensor.ROTATION_VECTOR) && !has(PointingSensor.MAGNETOMETER)

    /** One-line summary for the probe screen. */
    fun summary(): String = buildString {
        append(if (uwbPresent) "UWB present" else "no UWB")
        append(" | ")
        append(
            when (strategy) {
                PointingStrategy.UWB_ANGLE_OF_ARRIVAL -> "angle of arrival"
                PointingStrategy.FUSED_ROTATION_VECTOR -> "compass + pitch"
                PointingStrategy.GAME_ROTATION_VECTOR_ONLY -> "relative only"
                PointingStrategy.UNSUPPORTED -> "no pointing"
            },
        )
        append(" | ")
        append(sensors.size)
        append(" sensors")
    }
}
