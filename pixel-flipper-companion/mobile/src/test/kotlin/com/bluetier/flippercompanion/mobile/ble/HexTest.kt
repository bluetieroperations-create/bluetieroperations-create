package com.bluetier.flippercompanion.mobile.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexTest {

    @Test fun encodesUppercaseSpaceSeparated() {
        assertEquals("00 7F 80 FF", Hex.encode(byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())))
        assertEquals("", Hex.encode(ByteArray(0)))
    }

    @Test fun decodesSpacesColonsAndMixedCase() {
        val expected = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertArrayEquals(expected, Hex.decode("DE AD BE EF"))
        assertArrayEquals(expected, Hex.decode("deadbeef"))
        assertArrayEquals(expected, Hex.decode("de:ad:Be:eF"))
        assertArrayEquals(expected, Hex.decode("  de ad\tbe ef  "))
    }

    @Test fun rejectsMalformedInput() {
        assertNull(Hex.decode(""))
        assertNull(Hex.decode("   "))
        assertNull(Hex.decode("ABC")) // odd number of digits
        assertNull(Hex.decode("GG"))
        assertNull(Hex.decode("0x01"))
    }

    @Test fun rejectsNonAsciiDigits() {
        // Char.isDigit() is Unicode-aware; these must not be read as hex.
        assertNull(Hex.decode("٠١")) // Arabic-Indic 0 and 1
        assertNull(Hex.decode("０１")) // fullwidth 0 and 1
    }

    @Test fun roundTripsEveryByteValue() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, Hex.decode(Hex.encode(all)))
    }

    @Test fun asTextMasksControlCharacters() {
        assertEquals("Hi.", Hex.asText(byteArrayOf('H'.code.toByte(), 'i'.code.toByte(), 0x07)))
    }
}
