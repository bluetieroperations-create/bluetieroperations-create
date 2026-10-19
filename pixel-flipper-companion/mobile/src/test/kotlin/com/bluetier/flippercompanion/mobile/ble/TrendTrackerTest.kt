package com.bluetier.flippercompanion.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendTrackerTest {

    @Test fun risingSignalReadsWarmer() {
        val t = TrendTracker(windowMs = 1_000, hysteresisDb = 2.0)
        assertEquals(Trend.STEADY, t.update(0, -80.0))
        assertEquals(Trend.WARMER, t.update(500, -70.0)) // +10 dB vs baseline
    }

    @Test fun fallingSignalReadsColder() {
        val t = TrendTracker(windowMs = 1_000, hysteresisDb = 2.0)
        t.update(0, -60.0)
        assertEquals(Trend.COLDER, t.update(400, -75.0))
    }

    @Test fun smallWobbleStaysSteady() {
        val t = TrendTracker(windowMs = 1_000, hysteresisDb = 2.0)
        t.update(0, -70.0)
        assertEquals(Trend.STEADY, t.update(300, -71.0)) // within hysteresis
    }

    @Test fun baselineSlidesWithTheWindow() {
        val t = TrendTracker(windowMs = 1_000, hysteresisDb = 2.0)
        t.update(0, -80.0)       // old baseline, slides out
        t.update(2_000, -70.0)   // becomes the only in-window sample -> baseline
        assertEquals(Trend.STEADY, t.update(2_200, -70.0))
    }
}
