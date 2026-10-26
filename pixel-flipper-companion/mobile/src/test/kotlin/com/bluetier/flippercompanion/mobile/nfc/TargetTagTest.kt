package com.bluetier.flippercompanion.mobile.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetTagTest {

    @Test
    fun `a written tag reads back as the target it names`() {
        val uri = TargetTag.uriFor("tv-living-room")

        assertEquals("tv-living-room", TargetTag.targetIdFrom(uri))
    }

    @Test
    fun `someone else's tag is not ours`() {
        // A travel card, a hotel key, a tag written by another app: tapping one
        // should do nothing at all rather than resolve to some target.
        assertNull(TargetTag.targetIdFrom("https://example.com/target/tv"))
        assertNull(TargetTag.targetIdFrom("flipper://other/tv"))
        assertNull(TargetTag.targetIdFrom("tv"))
        assertNull(TargetTag.targetIdFrom(null))
    }

    @Test
    fun `a tag naming nothing is refused`() {
        assertNull(TargetTag.targetIdFrom("flipper://target/"))
        assertNull(TargetTag.targetIdFrom("flipper://target"))
    }

    @Test
    fun `scheme and host are matched without regard to case`() {
        // Some writers normalise a URI's scheme and authority to upper case.
        assertEquals("tv", TargetTag.targetIdFrom("FLIPPER://TARGET/tv"))
    }

    @Test
    fun `query and fragment are not part of the id`() {
        assertEquals("tv", TargetTag.targetIdFrom("flipper://target/tv?from=tag"))
        assertEquals("tv", TargetTag.targetIdFrom("flipper://target/tv#top"))
    }
}
