package com.bluetier.flippercompanion.mobile.nfc

import android.app.Activity
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Summary of a scanned NFC tag. */
data class NfcTagInfo(
    val uidHex: String,
    val techList: List<String>,
    val ndefText: String?,
)

/**
 * Reads 13.56 MHz NFC tags using NfcAdapter *reader mode* (the modern,
 * foreground-dispatch-free API). Bind it to an Activity's lifecycle:
 * call [enable] in onResume and [disable] in onPause.
 *
 * Reader mode is the right tool here because the Pixel 11 exposes full NFC to
 * apps (unlike the Watch, which reserves NFC for Google Wallet).
 */
class NfcReader(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    private val _lastTag = MutableStateFlow<NfcTagInfo?>(null)
    val lastTag: StateFlow<NfcTagInfo?> = _lastTag.asStateFlow()

    fun enable() {
        val adapter = adapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
        // No FLAG_READER_SKIP_NDEF_CHECK: the platform only adds the Ndef tech to
        // the Tag when it runs its NDEF check, so skipping it makes Ndef.get()
        // return null for every tag.
        adapter.enableReaderMode(activity, ::onTagDiscovered, flags, Bundle())
    }

    fun disable() {
        adapter?.disableReaderMode(activity)
    }

    private fun onTagDiscovered(tag: Tag) {
        _lastTag.value = NfcTagInfo(
            uidHex = tag.id.toHex(),
            techList = tag.techList.map { it.substringAfterLast('.') },
            ndefText = readNdefText(tag),
        )
    }

    /** Uses the message the platform read during its NDEF check — no tag I/O needed. */
    private fun readNdefText(tag: Tag): String? {
        val message = Ndef.get(tag)?.cachedNdefMessage ?: return null
        return message.records.joinToString("\n") { describe(it) }
    }

    private fun describe(record: NdefRecord): String {
        record.toUri()?.let { return it.toString() } // URI + Smart Poster, prefix decoded
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            NdefText.decode(record.payload)?.let { return it }
        }
        if (record.tnf == NdefRecord.TNF_EMPTY) return "(empty record)"
        val mime = record.toMimeType()
        return when {
            mime != null && mime.startsWith("text/") -> String(record.payload, Charsets.UTF_8)
            mime != null -> "$mime (${record.payload.size} B)"
            else -> "TNF ${record.tnf} (${record.payload.size} B)"
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(":") { "%02X".format(it) }
}
