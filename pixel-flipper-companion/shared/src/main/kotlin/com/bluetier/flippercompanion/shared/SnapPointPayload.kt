package com.bluetier.flippercompanion.shared

/**
 * A snap, and where the arm was aimed when it happened.
 *
 * This is the message the whole project is for: the watch detects the gesture
 * and reports the direction, the phone decides what that means. Nothing about
 * targets travels over the wire — the watch has no idea what a television is,
 * and does not need one.
 *
 * [bearing] is taken from shortly *before* the snap. The snap itself jolts the
 * forearm, so the instant of detection is the worst moment to ask where it was
 * pointing.
 */
data class SnapPoint(
    val bearing: Bearing,
    /** Sensor timestamp of the snap, for ordering and for discarding stale ones. */
    val snapNanos: Long,
    /** Peak acceleration of the transient, in m/s^2. Kept for tuning. */
    val peakDeviation: Double,
    /** Peak angular rate, in rad/s. */
    val peakGyro: Double,
    /** Samples the transient spanned. */
    val sampleCount: Int,
    /** Achieved accelerometer rate when it was captured, in Hz. */
    val accelHz: Double,
    /**
     * How steady the arm was over the window the bearing came from, in degrees
     * of spread. A snap thrown mid-swing carries a direction worth less than
     * one made while held still, and the phone can decline on that.
     */
    val aimSpreadDegrees: Double,
)

/**
 * Encodes [SnapPoint] for the Wearable Data Layer.
 *
 * Hand-rolled delimited text, holding the line [WearContract] draws: the
 * phone/watch contract carries no shared serialization dependency. Same shape
 * as [SnapTracePayload], one line of `key=value` pairs.
 */
object SnapPointPayload {

    const val VERSION = 1

    private const val FIELD = ';'

    fun encode(point: SnapPoint): ByteArray = buildString {
        append("v=").append(VERSION)
        append(FIELD).append("yaw=").append(point.bearing.yawDegrees)
        append(FIELD).append("pitch=").append(point.bearing.pitchDegrees)
        append(FIELD).append("t=").append(point.snapNanos)
        append(FIELD).append("dev=").append(point.peakDeviation)
        append(FIELD).append("gyro=").append(point.peakGyro)
        append(FIELD).append("n=").append(point.sampleCount)
        append(FIELD).append("hz=").append(point.accelHz)
        append(FIELD).append("spread=").append(point.aimSpreadDegrees)
    }.toByteArray(Charsets.UTF_8)

    /** Returns null for anything unparseable or of an unknown version. */
    fun decode(bytes: ByteArray): SnapPoint? = runCatching {
        val fields = String(bytes, Charsets.UTF_8)
            .split(FIELD)
            .mapNotNull { field ->
                val at = field.indexOf('=')
                if (at <= 0) null else field.substring(0, at) to field.substring(at + 1)
            }
            .toMap()

        if (fields["v"]?.toIntOrNull() != VERSION) return null

        SnapPoint(
            bearing = Bearing(
                yawDegrees = fields.getValue("yaw").toDouble(),
                pitchDegrees = fields.getValue("pitch").toDouble(),
            ),
            snapNanos = fields.getValue("t").toLong(),
            peakDeviation = fields.getValue("dev").toDouble(),
            peakGyro = fields.getValue("gyro").toDouble(),
            sampleCount = fields.getValue("n").toInt(),
            accelHz = fields.getValue("hz").toDouble(),
            aimSpreadDegrees = fields.getValue("spread").toDouble(),
        )
    }.getOrNull()
}
