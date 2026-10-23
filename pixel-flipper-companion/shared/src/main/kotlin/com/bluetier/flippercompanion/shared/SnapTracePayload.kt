package com.bluetier.flippercompanion.shared

/**
 * A snap-candidate trace travelling from the watch to the phone.
 *
 * Carries the measured shape of one acceleration transient plus the achieved
 * sensor rate at the time, which together answer the question the snap spike
 * exists to answer: is the wrist IMU fast enough to characterise a snap?
 *
 * [samples] are `(microseconds relative to [startNanos], accel magnitude,
 * gyro magnitude)`. Relative microseconds keep the encoded form compact and
 * avoid shipping 19-digit absolute timestamps for every sample. Offsets in the
 * pre-roll are negative, since the captured window opens before the transient
 * the detector triggered on.
 */
data class SnapTrace(
    val startNanos: Long,
    val peakOffsetMicros: Long,
    val peakDeviation: Double,
    val baseline: Double,
    val peakGyro: Double,
    val riseTimeMs: Double,
    val durationMs: Double,
    val sampleCount: Int,
    val accepted: Boolean,
    /** Reject-reason names, empty when [accepted]. Kept as strings so the */
    /** wire format does not depend on the watch-side enum. */
    val rejections: List<String>,
    /** Achieved accelerometer rate when this was captured, in Hz. */
    val accelHz: Double,
    val samples: List<TraceSample>,
)

/** One point of a [SnapTrace] waveform. */
data class TraceSample(
    val offsetMicros: Long,
    val accelMagnitude: Double,
    val gyroMagnitude: Double,
)

/**
 * Encodes and decodes [SnapTrace] for the Wearable Data Layer.
 *
 * Deliberately a hand-rolled delimited text format rather than JSON or
 * protobuf, to hold the line [WearContract] already draws: the phone/watch
 * contract stays a trivial, versionable thing with no shared serialization
 * dependency on either side.
 *
 * Wire form is two lines:
 * ```
 * v=1;start=<nanos>;peak=<micros>;dev=<f>;base=<f>;gyro=<f>;rise=<f>;dur=<f>;n=<i>;ok=<0|1>;rej=<A,B>;hz=<f>
 * <offsetMicros>:<accel>:<gyro>,<offsetMicros>:<accel>:<gyro>,...
 * ```
 * Doubles go through [Double.toString]/[String.toDouble], which are
 * locale-independent — a `String.format` round trip would not be.
 */
object SnapTracePayload {

    /** Bumped only on a breaking change to the line format. */
    const val VERSION = 1

    private const val FIELD_SEPARATOR = ';'
    private const val SAMPLE_SEPARATOR = ','
    private const val PART_SEPARATOR = ':'
    private const val REJECT_SEPARATOR = ','

    fun encode(trace: SnapTrace): ByteArray = buildString {
        append("v=").append(VERSION)
        append(FIELD_SEPARATOR).append("start=").append(trace.startNanos)
        append(FIELD_SEPARATOR).append("peak=").append(trace.peakOffsetMicros)
        append(FIELD_SEPARATOR).append("dev=").append(trace.peakDeviation)
        append(FIELD_SEPARATOR).append("base=").append(trace.baseline)
        append(FIELD_SEPARATOR).append("gyro=").append(trace.peakGyro)
        append(FIELD_SEPARATOR).append("rise=").append(trace.riseTimeMs)
        append(FIELD_SEPARATOR).append("dur=").append(trace.durationMs)
        append(FIELD_SEPARATOR).append("n=").append(trace.sampleCount)
        append(FIELD_SEPARATOR).append("ok=").append(if (trace.accepted) 1 else 0)
        append(FIELD_SEPARATOR).append("rej=").append(trace.rejections.joinToString(REJECT_SEPARATOR.toString()))
        append(FIELD_SEPARATOR).append("hz=").append(trace.accelHz)
        append('\n')
        trace.samples.forEachIndexed { index, sample ->
            if (index > 0) append(SAMPLE_SEPARATOR)
            append(sample.offsetMicros)
            append(PART_SEPARATOR).append(sample.accelMagnitude)
            append(PART_SEPARATOR).append(sample.gyroMagnitude)
        }
    }.toByteArray(Charsets.UTF_8)

    /** Returns null for anything unparseable or of an unknown version. */
    fun decode(bytes: ByteArray): SnapTrace? = runCatching {
        val text = String(bytes, Charsets.UTF_8)
        val lines = text.split('\n')
        val header = lines.getOrNull(0) ?: return null

        val fields = header.split(FIELD_SEPARATOR)
            .mapNotNull { field ->
                val at = field.indexOf('=')
                if (at <= 0) null else field.substring(0, at) to field.substring(at + 1)
            }
            .toMap()

        if (fields["v"]?.toIntOrNull() != VERSION) return null

        val samples = (lines.getOrNull(1) ?: "")
            .split(SAMPLE_SEPARATOR)
            .filter { it.isNotBlank() }
            .map { encoded ->
                val parts = encoded.split(PART_SEPARATOR)
                TraceSample(
                    offsetMicros = parts[0].toLong(),
                    accelMagnitude = parts[1].toDouble(),
                    gyroMagnitude = parts[2].toDouble(),
                )
            }

        SnapTrace(
            startNanos = fields.getValue("start").toLong(),
            peakOffsetMicros = fields.getValue("peak").toLong(),
            peakDeviation = fields.getValue("dev").toDouble(),
            baseline = fields.getValue("base").toDouble(),
            peakGyro = fields.getValue("gyro").toDouble(),
            riseTimeMs = fields.getValue("rise").toDouble(),
            durationMs = fields.getValue("dur").toDouble(),
            sampleCount = fields.getValue("n").toInt(),
            accepted = fields.getValue("ok") == "1",
            rejections = fields["rej"].orEmpty()
                .split(REJECT_SEPARATOR)
                .filter { it.isNotBlank() },
            accelHz = fields.getValue("hz").toDouble(),
            samples = samples,
        )
    }.getOrNull()

    /** Header row for the CSV the phone accumulates. */
    const val CSV_HEADER =
        "capturedAtMillis,startNanos,accepted,rejections,peakDeviation,baseline," +
            "peakGyro,riseTimeMs,durationMs,sampleCount,accelHz,waveform"

    /**
     * One CSV row per trace, waveform inlined as a space-separated
     * `offsetMicros:accel:gyro` list so a whole capture session stays one file.
     */
    fun toCsvRow(trace: SnapTrace, capturedAtMillis: Long): String {
        val waveform = trace.samples.joinToString(" ") {
            "${it.offsetMicros}:${it.accelMagnitude}:${it.gyroMagnitude}"
        }
        return listOf(
            capturedAtMillis.toString(),
            trace.startNanos.toString(),
            if (trace.accepted) "1" else "0",
            trace.rejections.joinToString("|"),
            trace.peakDeviation.toString(),
            trace.baseline.toString(),
            trace.peakGyro.toString(),
            trace.riseTimeMs.toString(),
            trace.durationMs.toString(),
            trace.sampleCount.toString(),
            trace.accelHz.toString(),
            waveform,
        ).joinToString(",")
    }
}
