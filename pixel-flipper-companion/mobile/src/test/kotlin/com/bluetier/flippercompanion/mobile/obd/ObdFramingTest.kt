package com.bluetier.flippercompanion.mobile.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class ObdFramingTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test fun emitsReplyOnlyAtPrompt() {
        val f = ObdFraming()
        assertEquals(emptyList<String>(), f.append(bytes("41 0C 1A F8\r")))
        assertEquals(listOf("41 0C 1A F8\r"), f.append(bytes(">")))
    }

    @Test fun reassemblesAcrossChunks() {
        val f = ObdFraming()
        assertEquals(emptyList<String>(), f.append(bytes("41 0")))
        assertEquals(emptyList<String>(), f.append(bytes("C 1A")))
        assertEquals(listOf("41 0C 1A F8\r"), f.append(bytes(" F8\r>")))
    }

    @Test fun splitsMultipleRepliesInOneChunk() {
        val f = ObdFraming()
        assertEquals(listOf("ELM327 v1.5\r", "OK\r"), f.append(bytes("ELM327 v1.5\r>OK\r>")))
    }

    @Test fun pendingHoldsPartialReply() {
        val f = ObdFraming()
        f.append(bytes("SEARCH"))
        assertEquals("SEARCH", f.pending())
        f.append(bytes("ING...\r>"))
        assertEquals("", f.pending())
    }

    @Test fun cleanCollapsesWhitespaceAndLineEndings() {
        assertEquals("41 0C 1A F8", ObdFraming.clean("41 0C 1A F8\r"))
        assertEquals("SEARCHING... 41 00 BE", ObdFraming.clean("SEARCHING...\r\r41 00 BE\r"))
        assertEquals("NO DATA", ObdFraming.clean("\r NO DATA \r"))
    }
}
