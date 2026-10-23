package com.bluetier.flippercompanion.mobile.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointingCapabilitiesTest {

    private fun cap(sensor: PointingSensor, maxRateHz: Double = 200.0) =
        SensorCapability(sensor, vendor = "test", maxRateHz = maxRateHz, resolution = 0.01f)

    private fun capabilities(
        uwb: Boolean = false,
        sensors: List<PointingSensor> = emptyList(),
    ) = PointingCapabilities(uwbPresent = uwb, sensors = sensors.map { cap(it) })

    @Test
    fun `uwb wins when present`() {
        val caps = capabilities(
            uwb = true,
            sensors = listOf(PointingSensor.ROTATION_VECTOR, PointingSensor.MAGNETOMETER),
        )

        assertEquals(PointingStrategy.UWB_ANGLE_OF_ARRIVAL, caps.strategy)
    }

    @Test
    fun `falls back to the fused rotation vector without uwb`() {
        val caps = capabilities(
            sensors = listOf(
                PointingSensor.ROTATION_VECTOR,
                PointingSensor.MAGNETOMETER,
                PointingSensor.GYROSCOPE,
            ),
        )

        assertEquals(PointingStrategy.FUSED_ROTATION_VECTOR, caps.strategy)
    }

    @Test
    fun `falls back to relative orientation when there is no absolute reference`() {
        val caps = capabilities(
            sensors = listOf(PointingSensor.GAME_ROTATION_VECTOR, PointingSensor.GYROSCOPE),
        )

        assertEquals(PointingStrategy.GAME_ROTATION_VECTOR_ONLY, caps.strategy)
    }

    @Test
    fun `reports unsupported when nothing can give a direction`() {
        val caps = capabilities(sensors = listOf(PointingSensor.ACCELEROMETER))

        assertEquals(PointingStrategy.UNSUPPORTED, caps.strategy)
    }

    @Test
    fun `an accelerometer alone is not a pointing device`() {
        assertEquals(PointingStrategy.UNSUPPORTED, capabilities().strategy)
    }

    @Test
    fun `flags a rotation vector with no magnetometer behind it`() {
        // The sensor type does not advertise this, but a fused rotation vector
        // without a magnetometer has no true north to offer.
        val suspect = capabilities(sensors = listOf(PointingSensor.ROTATION_VECTOR))
        assertTrue(suspect.rotationVectorLacksMagnetometer)

        val sound = capabilities(
            sensors = listOf(PointingSensor.ROTATION_VECTOR, PointingSensor.MAGNETOMETER),
        )
        assertFalse(sound.rotationVectorLacksMagnetometer)
    }

    @Test
    fun `lookup finds a present sensor and misses an absent one`() {
        val caps = PointingCapabilities(
            uwbPresent = false,
            sensors = listOf(cap(PointingSensor.GYROSCOPE, maxRateHz = 400.0)),
        )

        assertTrue(caps.has(PointingSensor.GYROSCOPE))
        assertEquals(400.0, caps.capability(PointingSensor.GYROSCOPE)!!.maxRateHz, 0.001)
        assertFalse(caps.has(PointingSensor.MAGNETOMETER))
        assertNull(caps.capability(PointingSensor.MAGNETOMETER))
    }

    @Test
    fun `summary names the radio and the chosen strategy`() {
        val withUwb = capabilities(uwb = true, sensors = listOf(PointingSensor.ROTATION_VECTOR))
        assertTrue(withUwb.summary().contains("UWB present"))
        assertTrue(withUwb.summary().contains("angle of arrival"))

        val withoutUwb = capabilities(sensors = listOf(PointingSensor.ROTATION_VECTOR))
        assertTrue(withoutUwb.summary().contains("no UWB"))
        assertTrue(withoutUwb.summary().contains("compass + pitch"))
    }
}
