package com.bluetier.flippercompanion.mobile.nfc

import android.app.Activity
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
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
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

    private fun readNdefText(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return runCatching {
            ndef.connect()
            val message = ndef.ndefMessage ?: return@runCatching null
            message.records.joinToString("\n") { record ->
                String(record.payload, Charsets.UTF_8).trim()
            }
        }.also { runCatching { ndef.close() } }.getOrNull()
    }

    private fun ByteArray.toHex(): String =
        joinToString(":") { "%02X".format(it) }
}
