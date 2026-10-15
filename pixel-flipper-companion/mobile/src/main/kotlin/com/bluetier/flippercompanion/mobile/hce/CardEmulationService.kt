package com.bluetier.flippercompanion.mobile.hce

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/**
 * Minimal Host Card Emulation (HCE) service. When the Pixel 11 is tapped
 * against an external reader, Android routes ISO-DEP (ISO 14443-4) APDUs that
 * match the AID in res/xml/apduservice.xml to this service.
 *
 * This reference implementation answers the SELECT-by-AID command and echoes a
 * static payload. Replace [buildResponse] with your own applet logic.
 *
 * Note on scope: HCE emulates a card to a reader you control. It does not clone
 * someone else's credential — the secure crypto on real access/payment cards is
 * not exposed to the host. Use this against your own readers/tags.
 */
class CardEmulationService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) return STATUS_FAILED
        Log.d(TAG, "APDU in: ${commandApdu.toHex()}")

        return when {
            commandApdu.isSelectAidCommand() -> {
                Log.d(TAG, "SELECT AID -> returning hello payload")
                HELLO_PAYLOAD + STATUS_SUCCESS
            }
            else -> STATUS_FAILED
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "HCE deactivated, reason=$reason")
    }

    /** CLA=00 INS=A4 (SELECT), P1=04 (select by name/AID). */
    private fun ByteArray.isSelectAidCommand(): Boolean =
        size >= 4 && this[0] == 0x00.toByte() &&
            this[1] == 0xA4.toByte() && this[2] == 0x04.toByte()

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG = "CardEmulationService"

        // "Hi from Pixel" in ASCII — demo payload only.
        private val HELLO_PAYLOAD =
            "Hi from Pixel".toByteArray(Charsets.US_ASCII)

        // ISO 7816-4 status words.
        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }
}
