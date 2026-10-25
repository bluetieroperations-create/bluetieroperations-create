package com.bluetier.flippercompanion.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapPointPayloadTest {

    private fun point(
        yaw: Double = 137.4,
        pitch: Double = -2.1,
        spread: Double = 1.3,
    ) = SnapPoint(
        bearing = Bearing(yaw, pitch),
        snapNanos = 23_366_914_587_680L,
        peakDeviation = 38.2,
        peakGyro = 4.28,
        sampleCount = 10,
        accelHz = 198.1,
        aimSpreadDegrees = spread,
    )

    @Test
    fun `round trips a snap point`() {
        val decoded = SnapPointPayload.decode(SnapPointPayload.encode(point()))

        assertEquals(point(), decoded)
    }

    @Test
    fun `round trips a bearing across the compass wraparound`() {
        val original = point(yaw = 359.8)

        assertEquals(original, SnapPointPayload.decode(SnapPointPayload.encode(original)))
    }

    @Test
    fun `round trips a steep downward aim`() {
        val original = point(pitch = -62.5)

        assertEquals(original, SnapPointPayload.decode(SnapPointPayload.encode(original)))
    }

    @Test
    fun `decimals survive, locale-independently`() {
        val encoded = String(SnapPointPayload.encode(point()), Charsets.UTF_8)

        assertTrue("expected a dot decimal separator in: $encoded", encoded.contains("yaw=137.4"))
    }

    @Test
    fun `an unknown version is refused`() {
        val encoded = String(SnapPointPayload.encode(point()), Charsets.UTF_8)
            .replaceFirst("v=${SnapPointPayload.VERSION}", "v=99")

        assertNull(SnapPointPayload.decode(encoded.toByteArray()))
    }

    @Test
    fun `malformed input is refused rather than throwing`() {
        listOf(
            ByteArray(0),
            "garbage".toByteArray(),
            "v=1;yaw=notanumber".toByteArray(),
            "v=1;yaw=10.0".toByteArray(), // every other field missing
        ).forEach { bytes ->
            assertNull(SnapPointPayload.decode(bytes))
        }
    }

    @Test
    fun `an impossible pitch is refused, not clamped`() {
        // Bearing rejects it on construction; decode must report that as a
        // failure rather than letting a bad direction through.
        val encoded = String(SnapPointPayload.encode(point()), Charsets.UTF_8)
            .replaceFirst("pitch=-2.1", "pitch=120.0")

        assertNull(SnapPointPayload.decode(encoded.toByteArray()))
    }

    @Test
    fun `the spread a wild snap carries survives the trip`() {
        // The phone declines on this, so it has to arrive intact.
        val wild = point(spread = 42.7)

        assertEquals(42.7, SnapPointPayload.decode(SnapPointPayload.encode(wild))!!.aimSpreadDegrees, 1e-9)
    }
}
