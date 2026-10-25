package com.bluetier.flippercompanion.shared

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Which way a device is "pointing", which depends entirely on how it is held.
 *
 * Android's device frame is +X right, +Y towards the top of the screen, +Z out
 * of the screen towards the user, regardless of orientation.
 */
enum class PointingAxis {
    /**
     * Held like a TV remote: screen up, top edge towards the thing. Points
     * along device +Y. The natural way to aim a phone at something.
     */
    TOP_EDGE,

    /**
     * Held like a camera: screen facing you, back towards the thing. Points
     * along device -Z, the direction the rear camera looks.
     */
    SCREEN_BACK,

    /**
     * Device -Y, out of the bottom edge.
     *
     * Added for the watch. Which way a worn watch points along the forearm
     * depends on which wrist it is on and how the band is oriented, and it is
     * as likely to be this as [TOP_EDGE]. Rather than guess, both are offered
     * and the live readout settles it.
     */
    BOTTOM_EDGE,
}

/**
 * Turns Android's rotation matrix into a compass [Bearing].
 *
 * `SensorManager.getRotationMatrixFromVector` yields a matrix that maps device
 * coordinates into the world frame of east, north and up. Pointing is then just
 * a matter of asking where one device axis ends up in that frame.
 *
 * Kept pure - the matrix comes in as nine doubles - so the conversion is
 * testable without a sensor, a compass or a room.
 */
object DeviceBearing {

    /**
     * [rotationMatrix] is the 9-element row-major matrix Android produces,
     * mapping device coordinates to world coordinates (X east, Y north, Z up).
     *
     * Returns null for a matrix that is not of that shape, rather than
     * producing a plausible-looking bearing from nonsense.
     */
    fun fromRotationMatrix(
        rotationMatrix: DoubleArray,
        axis: PointingAxis = PointingAxis.TOP_EDGE,
    ): Bearing? {
        if (rotationMatrix.size != 9) return null
        if (rotationMatrix.any { !it.isFinite() }) return null

        // world = R * deviceAxis. Picking the column directly avoids a
        // multiply by a basis vector that is mostly zeros.
        val (east, north, up) = when (axis) {
            // device +Y is column 1
            PointingAxis.TOP_EDGE -> Triple(
                rotationMatrix[1],
                rotationMatrix[4],
                rotationMatrix[7],
            )
            // device -Z is the negated column 2
            PointingAxis.SCREEN_BACK -> Triple(
                -rotationMatrix[2],
                -rotationMatrix[5],
                -rotationMatrix[8],
            )
            // device -Y is the negated column 1
            PointingAxis.BOTTOM_EDGE -> Triple(
                -rotationMatrix[1],
                -rotationMatrix[4],
                -rotationMatrix[7],
            )
        }

        return fromWorldVector(east, north, up)
    }

    /**
     * Mean of several bearings.
     *
     * Averages the unit vectors, not the yaw figures. Averaging angles is wrong
     * across the compass wraparound - the mean of 359 and 1 comes out as 180,
     * pointing exactly backwards - and wrong again near vertical. Returns null
     * for an empty list, or for directions that cancel out and leave no
     * meaningful mean.
     */
    fun average(bearings: List<Bearing>): Bearing? {
        if (bearings.isEmpty()) return null

        var east = 0.0
        var north = 0.0
        var up = 0.0
        bearings.forEach { bearing ->
            val v = bearing.toVector()
            east += v[0]
            north += v[1]
            up += v[2]
        }
        val n = bearings.size
        return fromWorldVector(east / n, north / n, up / n)
    }

    /**
     * Converts a world-frame direction into a bearing.
     *
     * Exposed separately because it is the part worth reasoning about: yaw is
     * the compass angle of the horizontal component, and pitch the elevation.
     */
    fun fromWorldVector(east: Double, north: Double, up: Double): Bearing? {
        val length = kotlin.math.sqrt(east * east + north * north + up * up)
        // A zero-length direction has no bearing; without this the atan2 below
        // would happily return 0 degrees for it.
        if (length < 1e-9 || !length.isFinite()) return null

        val e = east / length
        val n = north / length
        val u = (up / length).coerceIn(-1.0, 1.0)

        // atan2(east, north) rather than the usual atan2(y, x): compass yaw is
        // measured clockwise from north, not anticlockwise from east.
        val yaw = Math.toDegrees(atan2(e, n))
        val pitch = Math.toDegrees(asin(u))

        return Bearing(
            yawDegrees = ((yaw % 360.0) + 360.0) % 360.0,
            // asin can drift a hair outside the range through rounding, and
            // Bearing rejects anything beyond vertical.
            pitchDegrees = pitch.coerceIn(-90.0, 90.0),
        )
    }
}
