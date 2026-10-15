package com.bluetier.flippercompanion.mobile.obd

/**
 * Decodes a handful of common OBD-II Mode 01 responses into human-readable text.
 * Formulas are from the SAE J1979 standard PID table. Pure and unit-testable.
 *
 * A Mode 01 reply looks like "41 0C 1A F8" — 0x41 is the mode byte (request mode
 * 0x01 + 0x40), 0x0C is the PID, and the rest are data bytes A, B, …
 */
object ObdPids {

    /** @return a decoded description, or null if the reply isn't a recognized Mode 01 frame. */
    fun decode(reply: String): String? {
        val bytes = hexBytes(reply) ?: return null
        if (bytes.size < 2 || bytes[0] != 0x41) return null
        val pid = bytes[1]
        val data = bytes.drop(2)
        fun a() = data.getOrNull(0)
        fun b() = data.getOrNull(1)
        return when (pid) {
            0x04 -> a()?.let { "Engine load ${"%.1f".format(it * 100.0 / 255)} %" }
            0x05 -> a()?.let { "Coolant ${it - 40} °C" }
            0x0C -> { val x = a(); val y = b(); if (x != null && y != null) "RPM ${(x * 256 + y) / 4}" else null }
            0x0D -> a()?.let { "Speed $it km/h" }
            0x0F -> a()?.let { "Intake air ${it - 40} °C" }
            0x11 -> a()?.let { "Throttle ${"%.1f".format(it * 100.0 / 255)} %" }
            0x2F -> a()?.let { "Fuel level ${"%.1f".format(it * 100.0 / 255)} %" }
            0x42 -> { val x = a(); val y = b(); if (x != null && y != null) "Module voltage ${"%.3f".format((x * 256 + y) / 1000.0)} V" else null }
            else -> null
        }
    }

    /** Parses whitespace-tolerant hex ("41 0C 1A F8" or "410C1AF8") into unsigned byte ints. */
    private fun hexBytes(reply: String): List<Int>? {
        val cleaned = reply.filter { it.isHexDigit() }
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        return (cleaned.indices step 2).map { cleaned.substring(it, it + 2).toInt(16) }
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
