package com.bluetier.flippercompanion.mobile.obd

/**
 * Reassembles ELM327 responses from BLE notification chunks.
 *
 * An ELM327 answers over its UART characteristic in ASCII and ends every reply
 * with the prompt character '>'. A single reply can arrive split across several
 * notifications, and a single notification can carry more than one reply, so the
 * split happens here on the '>' boundary rather than per-notification.
 *
 * Pure and Android-free so it can be unit-tested.
 */
class ObdFraming {

    private val buffer = StringBuilder()

    /** Feed one notification's bytes; returns any now-complete replies (prompt stripped). */
    fun append(bytes: ByteArray): List<String> {
        val complete = mutableListOf<String>()
        for (b in bytes) {
            when (val c = (b.toInt() and 0xFF).toChar()) {
                PROMPT -> {
                    complete.add(buffer.toString())
                    buffer.setLength(0)
                }
                else -> buffer.append(c)
            }
        }
        return complete
    }

    /** Bytes received since the last prompt (useful for showing a partial reply). */
    fun pending(): String = buffer.toString()

    fun reset() = buffer.setLength(0)

    companion object {
        const val PROMPT = '>'

        /**
         * Collapses a raw reply into a single tidy line: CR/LF become spaces,
         * runs of whitespace collapse, and ELM status noise is left intact so the
         * user still sees "NO DATA", "SEARCHING...", "?", etc.
         */
        fun clean(raw: String): String =
            raw.replace('\r', ' ').replace('\n', ' ').trim().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("\\s+")
    }
}
