package com.bluetier.flippercompanion.mobile.probe

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build

/**
 * Reads this phone's pointing-relevant hardware and reports what it can do.
 *
 * Deliberately dependency-free: UWB presence comes from
 * [PackageManager.FEATURE_UWB], a plain system-feature query, rather than from
 * `androidx.core.uwb`. The question at this stage is only whether the radio is
 * there — if it is, pulling in the UWB library for real ranging is the next
 * step, and if it is not, there is nothing to pull it in for.
 *
 * The result is a pure [PointingCapabilities], so the decision it drives is
 * unit-tested even though this query is not.
 */
object CapabilityProbe {

    /** Maps the sensor types this cares about onto framework constants. */
    private val sensorTypes: List<Pair<PointingSensor, Int>> = listOf(
        PointingSensor.ROTATION_VECTOR to Sensor.TYPE_ROTATION_VECTOR,
        PointingSensor.GAME_ROTATION_VECTOR to Sensor.TYPE_GAME_ROTATION_VECTOR,
        PointingSensor.GEOMAGNETIC_ROTATION_VECTOR to Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        PointingSensor.MAGNETOMETER to Sensor.TYPE_MAGNETIC_FIELD,
        PointingSensor.ACCELEROMETER to Sensor.TYPE_ACCELEROMETER,
        PointingSensor.GYROSCOPE to Sensor.TYPE_GYROSCOPE,
    )

    fun probe(context: Context): PointingCapabilities {
        val sensorManager =
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val sensors = sensorTypes.mapNotNull { (pointingSensor, type) ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                SensorCapability(
                    sensor = pointingSensor,
                    vendor = sensor.vendor ?: "",
                    maxRateHz = maxRateHz(sensor),
                    resolution = sensor.resolution,
                )
            }
        }

        return PointingCapabilities(
            uwbPresent = hasUwb(context),
            sensors = sensors,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidApiLevel = Build.VERSION.SDK_INT,
        )
    }

    /**
     * FEATURE_UWB landed in API 31. On anything older the answer is no, which
     * is also the right answer for a phone that predates the feature string.
     */
    private fun hasUwb(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)

    /**
     * Converts a sensor's minimum delay (microseconds between samples) into a
     * rate. A reported delay of 0 means "on change only" — no continuous rate —
     * so it maps to 0.0 rather than to infinity.
     */
    private fun maxRateHz(sensor: Sensor): Double {
        val minDelayMicros = sensor.minDelay
        return if (minDelayMicros > 0) 1_000_000.0 / minDelayMicros else 0.0
    }
}
