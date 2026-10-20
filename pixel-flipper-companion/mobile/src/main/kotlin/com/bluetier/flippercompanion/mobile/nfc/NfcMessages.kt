package com.bluetier.flippercompanion.mobile.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord

/**
 * Builds the NDEF messages the writer puts on a tag. Uses the platform's own
 * record encoders so the output matches what any standard NFC reader expects:
 * an RTD-Text record for text, a URI record (with the standard prefix byte) for
 * links.
 */
object NfcMessages {

    fun text(value: String, languageCode: String = "en"): NdefMessage =
        NdefMessage(arrayOf(NdefRecord.createTextRecord(languageCode, value)))

    fun uri(value: String): NdefMessage =
        NdefMessage(arrayOf(NdefRecord.createUri(value)))
}
