package com.bluetier.flippercompanion.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapTracePayloadTest {

    private fun trace(
        accepted: Boolean = true,
        rejections: List<String> = emptyList(),
        samples: List<TraceSample> = listOf(
            TraceSample(0L, 9.81, 0.02),
            TraceSample(5_000L, 25.4, 0.31),
            TraceSample(10_000L, 11.2, 0.18),
        ),
    ) = SnapTrace(
        startNanos = 1_234_567_890_123L,
        peakOffsetMicros = 5_000L,
        peakDeviation = 15.59,
        baseline = 9.81,
        peakGyro = 0.31,
        riseTimeMs = 5.0,
        durationMs = 10.0,
        sampleCount = 3,
        accepted = accepted,
        rejections = rejections,
        accelHz = 198.4,
        samples = samples,
    )

    @Test
    fun `round trips an accepted trace`() {
        val original = trace()
        val decoded = SnapTracePayload.decode(SnapTracePayload.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `round trips rejection reasons`() {
        val original = trace(
            accepted = false,
            rejections = listOf("WEAK_PEAK", "TOO_MUCH_ROTATION"),
        )
        val decoded = SnapTracePayload.decode(SnapTracePayload.encode(original))!!

        assertEquals(listOf("WEAK_PEAK", "TOO_MUCH_ROTATION"), decoded.rejections)
        assertTrue(!decoded.accepted)
    }

    @Test
    fun `round trips a trace with no waveform`() {
        val original = trace(samples = emptyList())
        val decoded = SnapTracePayload.decode(SnapTracePayload.encode(original))!!

        assertTrue(decoded.samples.isEmpty())
        assertEquals(original, decoded)
    }

    @Test
    fun `survives a locale that would use comma decimal separators`() {
        // Double.toString is locale-independent; String.format would not be, and
        // a comma decimal separator would collide with the sample separator.
        val original = trace()
        val encoded = String(SnapTracePayload.encode(original), Charsets.UTF_8)

        assertTrue("expected a dot decimal separator in: $encoded", encoded.contains("9.81"))
        assertEquals(original, SnapTracePayload.decode(encoded.toByteArray()))
    }

    @Test
    fun `rejects an unknown version`() {
        val encoded = String(SnapTracePayload.encode(trace()), Charsets.UTF_8)
        val bumped = encoded.replaceFirst("v=${SnapTracePayload.VERSION}", "v=99")

        assertNull(SnapTracePayload.decode(bumped.toByteArray()))
    }

    @Test
    fun `returns null rather than throwing on malformed input`() {
        assertNull(SnapTracePayload.decode(ByteArray(0)))
        assertNull(SnapTracePayload.decode("garbage".toByteArray()))
        assertNull(SnapTracePayload.decode("v=1;start=notanumber".toByteArray()))
        // Header present and well-formed, waveform truncated mid-sample.
        assertNull(SnapTracePayload.decode("v=1;start=1\n0:9.81".toByteArray()))
    }

    @Test
    fun `csv row column count matches the header`() {
        val row = SnapTracePayload.toCsvRow(trace(), capturedAtMillis = 1_700_000_000_000L)

        assertEquals(
            SnapTracePayload.CSV_HEADER.split(",").size,
            row.split(",").size,
        )
    }

    @Test
    fun `csv waveform does not break the column split`() {
        // Rejections are pipe-joined and the waveform is space-joined precisely
        // so neither can introduce a stray comma.
        val row = SnapTracePayload.toCsvRow(
            trace(accepted = false, rejections = listOf("WEAK_PEAK", "TOO_LONG")),
            capturedAtMillis = 1_700_000_000_000L,
        )

        assertEquals(SnapTracePayload.CSV_HEADER.split(",").size, row.split(",").size)
        assertTrue(row.contains("WEAK_PEAK|TOO_LONG"))
        assertTrue(row.contains("0:9.81:0.02 5000:25.4:0.31"))
    }
}
