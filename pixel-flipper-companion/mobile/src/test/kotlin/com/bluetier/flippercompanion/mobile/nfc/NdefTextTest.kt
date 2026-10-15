package com.bluetier.flippercompanion.mobile.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NdefTextTest {

    private fun payload(status: Int, language: String, text: ByteArray) =
        byteArrayOf(status.toByte()) + language.toByteArray(Charsets.US_ASCII) + text

    @Test fun decodesUtf8WithLanguageCode() {
        val p = payload(0x02, "en", "Hello, NFC".toByteArray(Charsets.UTF_8))
        assertEquals("Hello, NFC", NdefText.decode(p))
    }

    @Test fun decodesMultibyteUtf8() {
        val p = payload(0x05, "en-US", "café ✓".toByteArray(Charsets.UTF_8))
        assertEquals("café ✓", NdefText.decode(p))
    }

    @Test fun honoursUtf16Flag() {
        val p = payload(0x80 or 0x02, "en", "Hi".toByteArray(Charsets.UTF_16))
        assertEquals("Hi", NdefText.decode(p))
    }

    @Test fun emptyTextIsEmptyString() {
        assertEquals("", NdefText.decode(payload(0x02, "en", ByteArray(0))))
    }

    @Test fun rejectsMalformedPayloads() {
        assertNull(NdefText.decode(ByteArray(0)))
        // Claims a 5-byte language code but carries only 2 bytes after the status byte.
        assertNull(NdefText.decode(byteArrayOf(0x05, 'e'.code.toByte(), 'n'.code.toByte())))
    }
}
