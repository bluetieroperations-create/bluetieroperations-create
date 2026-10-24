package com.bluetier.flippercompanion.mobile.dispatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParserTest {

    @Test
    fun `parses a plain http action`() {
        val action = ActionParser.parse("http://192.168.0.5:8080/tv/wake") as ActionSpec.Http

        assertEquals("http://192.168.0.5:8080/tv/wake", action.url)
        assertEquals("POST", action.method)
        assertEquals(null, action.body)
    }

    @Test
    fun `defaults to POST but accepts an explicit method`() {
        val get = ActionParser.parse("https://ha.local/api/webhook/abc GET") as ActionSpec.Http

        assertEquals("GET", get.method)
        assertEquals("https://ha.local/api/webhook/abc", get.url)
    }

    @Test
    fun `carries a body through`() {
        val action = ActionParser.parse(
            """http://host/toggle POST {"state":"on"}""",
        ) as ActionSpec.Http

        assertEquals("""{"state":"on"}""", action.body)
    }

    @Test
    fun `parses an adb key event`() {
        val action = ActionParser.parse(
            "adb://192.168.0.12:5555/KEYCODE_WAKEUP",
        ) as ActionSpec.AdbKeyEvent

        assertEquals("192.168.0.12", action.host)
        assertEquals(5555, action.port)
        assertEquals("KEYCODE_WAKEUP", action.keyCode)
    }

    @Test
    fun `an adb action without a port uses the usual one`() {
        val action = ActionParser.parse("adb://192.168.0.12/KEYCODE_SLEEP") as ActionSpec.AdbKeyEvent

        assertEquals(5555, action.port)
    }

    @Test
    fun `a mistyped key code is refused rather than sent`() {
        // A key code the TV would silently ignore is indistinguishable from the
        // gesture not working, so it is caught here instead.
        val action = ActionParser.parse("adb://192.168.0.12:5555/WAKEUP") as ActionSpec.Unsupported

        assertTrue(action.reason.contains("KEYCODE_"))
    }

    @Test
    fun `nonsense is reported, not thrown`() {
        listOf(
            "",
            "   ",
            "turn on the telly",
            "ftp://host/thing",
            "adb://192.168.0.12:5555/",
            "adb://192.168.0.12:notaport/KEYCODE_WAKEUP",
            "http://",
        ).forEach { raw ->
            val parsed = ActionParser.parse(raw)
            assertTrue("expected $raw to be unsupported, got $parsed", parsed is ActionSpec.Unsupported)
            assertTrue((parsed as ActionSpec.Unsupported).reason.isNotBlank())
        }
    }

    @Test
    fun `an out of range port is refused`() {
        val action = ActionParser.parse(
            "adb://192.168.0.12:99999/KEYCODE_WAKEUP",
        ) as ActionSpec.Unsupported

        assertTrue(action.reason.contains("out of range"))
    }

    @Test
    fun `an unsupported http method is refused`() {
        val action = ActionParser.parse("http://host/x DELETE") as ActionSpec.Unsupported

        assertTrue(action.reason.contains("DELETE"))
    }

    @Test
    fun `schemes are case-insensitive and whitespace is forgiven`() {
        assertTrue(ActionParser.parse("  HTTP://host/x  ") is ActionSpec.Http)
        assertTrue(ActionParser.parse(" adb://host/KEYCODE_HOME ") is ActionSpec.AdbKeyEvent)
    }

    @Test
    fun `a hostname works as well as an address`() {
        val action = ActionParser.parse("adb://livingroom-tv.local/KEYCODE_HOME") as ActionSpec.AdbKeyEvent

        assertEquals("livingroom-tv.local", action.host)
        assertEquals(5555, action.port)
    }
}
