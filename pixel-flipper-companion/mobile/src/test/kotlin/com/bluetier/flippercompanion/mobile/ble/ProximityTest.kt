package com.bluetier.flippercompanion.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {

    @Test fun bandsFollowSignalStrength() {
        assertEquals(ProximityBand.IMMEDIATE, Proximity.band(-40.0))
        assertEquals(ProximityBand.IMMEDIATE, Proximity.band(-55.0))
        assertEquals(ProximityBand.NEAR, Proximity.band(-56.0))
        assertEquals(ProximityBand.NEAR, Proximity.band(-70.0))
        assertEquals(ProximityBand.FAR, Proximity.band(-71.0))
        assertEquals(ProximityBand.DISTANT, Proximity.band(-95.0))
    }

    @Test fun oneMetreIsAboutCalibratedPower() {
        // At RSSI == txPower the estimate is 1 m.
        assertEquals(1.0, Proximity.estimateMeters(-59.0, -59), 0.001)
    }

    @Test fun closerSignalMeansSmallerDistance() {
        val near = Proximity.estimateMeters(-50.0)
        val far = Proximity.estimateMeters(-85.0)
        assertTrue("near=$near far=$far", near < far)
    }

    @Test fun distanceIsClamped() {
        assertEquals(0.1, Proximity.estimateMeters(50.0), 0.0001)   // absurdly strong
        assertEquals(100.0, Proximity.estimateMeters(-200.0), 0.0001) // absurdly weak
    }
}
