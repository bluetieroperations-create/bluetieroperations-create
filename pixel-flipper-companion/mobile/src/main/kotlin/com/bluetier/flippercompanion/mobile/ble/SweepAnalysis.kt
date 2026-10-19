package com.bluetier.flippercompanion.mobile.ble

/** One advertiser in a tracker sweep, with its classification and persistence. */
data class SweepEntry(
    val device: BleDeviceInfo,
    val verdict: TrackerVerdict,
    /** How long it has been visible during this sweep. */
    val durationMillis: Long,
    /** Ranking score — higher is more worth a look. */
    val suspicion: Int,
) {
    val address: String get() = device.address
}

/**
 * Ranks the devices seen during a sweep by how much they warrant attention.
 *
 * The signal is persistence, not a single reading: an item tracker left to follow
 * someone is non-connectable, keeps reappearing, and often matches a known tracker
 * signature. So the score combines (a) a known tracker signature, (b) how long and
 * how consistently it has been seen, and (c) how close it is. Pure and testable.
 */
object SweepAnalysis {

    fun analyze(devices: Collection<BleDeviceInfo>, nowMillis: Long): List<SweepEntry> =
        devices.map { device ->
            val verdict = TrackerClassifier.classify(device)
            val duration = (nowMillis - device.firstSeenMillis).coerceAtLeast(0)
            SweepEntry(device, verdict, duration, score(device, verdict, duration))
        }.sortedWith(compareByDescending<SweepEntry> { it.suspicion }.thenByDescending { it.durationMillis })

    private fun score(device: BleDeviceInfo, verdict: TrackerVerdict, durationMillis: Long): Int {
        var score = 0
        if (verdict.isPossibleTracker) score += 100
        // Persistence: visible a long time, and seen many times (not a one-off blip).
        if (durationMillis >= 30_000) score += 30
        if (durationMillis >= 120_000) score += 30
        if (device.count >= 20) score += 20
        // Non-connectable + close is the shape of a nearby beacon/tracker.
        if (!device.connectable) score += 10
        if (device.smoothedRssi >= -70) score += 10
        return score
    }
}
