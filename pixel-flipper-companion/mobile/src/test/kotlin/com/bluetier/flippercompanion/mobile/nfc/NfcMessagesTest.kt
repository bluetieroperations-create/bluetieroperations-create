package com.bluetier.flippercompanion.mobile.nfc

import android.nfc.NdefRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds messages with NfcMessages and checks them against the platform's own
 * decoders — so what we write is what a standard reader would read back.
 */
class NfcMessagesTest {

    @Test fun textRecordRoundTripsThroughTheDecoder() {
        val record = NfcMessages.text("Hello, tag").records.single()
        assertEquals(NdefRecord.TNF_WELL_KNOWN, record.tnf)
        assertTrue(record.type.contentEquals(NdefRecord.RTD_TEXT))
        assertEquals("Hello, tag", NdefText.decode(record.payload))
    }

    @Test fun textRecordHandlesUnicode() {
        val record = NfcMessages.text("café ✓ こんにちは").records.single()
        assertEquals("café ✓ こんにちは", NdefText.decode(record.payload))
    }

    @Test fun uriRecordParsesBackToTheSameUri() {
        val record = NfcMessages.uri("https://example.com/x").records.single()
        assertEquals("https://example.com/x", record.toUri().toString())
    }

    @Test fun singleRecordPerMessage() {
        assertEquals(1, NfcMessages.text("a").records.size)
        assertEquals(1, NfcMessages.uri("https://a.b").records.size)
    }
}
