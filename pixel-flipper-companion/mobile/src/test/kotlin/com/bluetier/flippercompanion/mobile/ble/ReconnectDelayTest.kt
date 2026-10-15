package com.bluetier.flippercompanion.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectDelayTest {

    @Test fun doublesFromOneSecond() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), (1..5).map(::reconnectDelayMs))
    }

    @Test fun capsAtThirtySecondsWithoutOverflow() {
        assertEquals(30_000L, reconnectDelayMs(6))
        assertEquals(30_000L, reconnectDelayMs(64))
        assertEquals(30_000L, reconnectDelayMs(Int.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun attemptsAreOneBased() {
        reconnectDelayMs(0)
    }
}
