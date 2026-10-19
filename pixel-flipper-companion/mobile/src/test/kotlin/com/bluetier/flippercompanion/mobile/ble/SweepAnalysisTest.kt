package com.bluetier.flippercompanion.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepAnalysisTest {

    private fun device(
        address: String, firstSeen: Long, count: Int = 1, smoothed: Double = -80.0,
        connectable: Boolean = true, serviceUuids16: Set<String> = emptySet(), appleFindMy: Boolean = false,
    ) = BleDeviceInfo(
        address = address, name = null, rssi = smoothed.toInt(), smoothedRssi = smoothed,
        firstSeenMillis = firstSeen, lastSeenMillis = firstSeen, count = count,
        minRssi = smoothed.toInt(), maxRssi = smoothed.toInt(),
        serviceUuids16 = serviceUuids16, appleFindMy = appleFindMy, connectable = connectable,
    )

    @Test fun knownTrackersRankAboveOrdinaryDevices() {
        val now = 60_000L
        val tracker = device("T1", firstSeen = 55_000, appleFindMy = true) // seen briefly
        val ordinary = device("O1", firstSeen = 0, count = 50)             // persistent but not a tracker
        val ranked = SweepAnalysis.analyze(listOf(ordinary, tracker), now)
        assertEquals("T1", ranked.first().address)
        assertTrue(ranked.first().verdict.isPossibleTracker)
    }

    @Test fun persistentNonConnectableClosePushesScoreUp() {
        val now = 200_000L
        val persistent = device("P", firstSeen = 0, count = 40, smoothed = -60.0, connectable = false)
        val blip = device("B", firstSeen = 199_000, count = 1, smoothed = -90.0)
        val ranked = SweepAnalysis.analyze(listOf(blip, persistent), now)
        assertEquals("P", ranked.first().address)
        assertTrue(ranked.first().suspicion > ranked.last().suspicion)
    }

    @Test fun durationIsComputedFromFirstSeen() {
        val entry = SweepAnalysis.analyze(listOf(device("X", firstSeen = 10_000)), 70_000L).single()
        assertEquals(60_000L, entry.durationMillis)
    }

    @Test fun emptyInputYieldsEmptyRanking() {
        assertTrue(SweepAnalysis.analyze(emptyList(), 0L).isEmpty())
    }
}
