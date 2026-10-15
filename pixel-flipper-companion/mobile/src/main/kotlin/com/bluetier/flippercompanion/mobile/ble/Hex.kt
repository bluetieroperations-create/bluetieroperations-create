package com.bluetier.flippercompanion.mobile.ble

/** Format/parse helpers for the hex byte strings the GATT UI reads and writes. */
object Hex {

    fun encode(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    /** Decodes "DE AD BE EF" / "deadbeef" / "de:ad" into bytes, or null if malformed. */
    fun decode(text: String): ByteArray? {
        val cleaned = text.filterNot { it.isWhitespace() || it == ':' }
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        // ASCII only: Char.isDigit() would also accept e.g. Arabic-Indic digits.
        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Best-effort printable rendering of a byte payload, for display next to hex. */
    fun asText(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).map { if (it.isISOControl()) '.' else it }.joinToString("")
}
