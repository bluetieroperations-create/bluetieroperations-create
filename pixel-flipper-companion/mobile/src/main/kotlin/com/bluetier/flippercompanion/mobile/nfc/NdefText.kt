package com.bluetier.flippercompanion.mobile.nfc

/**
 * Decodes the payload of an NFC Forum Text record (RTD "T"). Kept free of
 * Android types so it can be unit-tested on the JVM.
 *
 * Payload layout: one status byte — bit 7 set means UTF-16, bits 0-5 hold the
 * language-code length — then the IANA language code (e.g. "en"), then the text.
 */
object NdefText {

    /** @return the text, or null if the payload is malformed. */
    fun decode(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt()
        val textStart = 1 + (status and LANGUAGE_LENGTH_MASK)
        if (textStart > payload.size) return null
        val charset = if (status and UTF16_FLAG != 0) Charsets.UTF_16 else Charsets.UTF_8
        return String(payload, textStart, payload.size - textStart, charset)
    }

    private const val UTF16_FLAG = 0x80
    private const val LANGUAGE_LENGTH_MASK = 0x3F
}
