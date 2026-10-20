package com.bluetier.flippercompanion.mobile.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Summary of a scanned NFC tag. */
data class NfcTagInfo(
    val uidHex: String,
    val techList: List<String>,
    val ndefText: String?,
)

/** Outcome of an armed NDEF write, when the next tag is tapped. */
sealed interface NfcWriteResult {
    data class Success(val bytesWritten: Int) : NfcWriteResult
    data class Failure(val reason: String) : NfcWriteResult
}

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

    private val _writeResult = MutableSharedFlow<NfcWriteResult>(extraBufferCapacity = 4)
    val writeResult: SharedFlow<NfcWriteResult> = _writeResult.asSharedFlow()

    private val _armed = MutableStateFlow(false)

    /** True while a message is armed and waiting for the next tag tap. */
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    // Written on the main thread (arm/disarm), read on the reader-mode thread.
    @Volatile
    private var pendingMessage: NdefMessage? = null

    /** Arm a one-shot write: the next tag tapped gets [message] instead of being read. */
    fun armWrite(message: NdefMessage) {
        pendingMessage = message
        _armed.value = true
    }

    fun disarmWrite() {
        pendingMessage = null
        _armed.value = false
    }

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
        // Runs on a background (reader-mode) thread, so blocking tag I/O is fine here.
        val message = pendingMessage
        if (message != null) {
            pendingMessage = null // one-shot
            _armed.value = false
            _writeResult.tryEmit(writeTag(tag, message))
            return
        }
        _lastTag.value = NfcTagInfo(
            uidHex = tag.id.toHex(),
            techList = tag.techList.map { it.substringAfterLast('.') },
            ndefText = readNdefText(tag),
        )
    }

    /** Writes [message] to [tag], formatting a blank tag if needed. */
    private fun writeTag(tag: Tag, message: NdefMessage): NfcWriteResult {
        val size = message.toByteArray().size

        Ndef.get(tag)?.let { ndef ->
            return runCatching {
                ndef.connect()
                when {
                    !ndef.isWritable -> NfcWriteResult.Failure("Tag is read-only")
                    size > ndef.maxSize ->
                        NfcWriteResult.Failure("Too big: $size B exceeds the ${ndef.maxSize} B capacity")
                    else -> {
                        ndef.writeNdefMessage(message)
                        NfcWriteResult.Success(size)
                    }
                }
            }.also { runCatching { ndef.close() } }
                .getOrElse { NfcWriteResult.Failure(it.message ?: "Write failed") }
        }

        NdefFormatable.get(tag)?.let { formatable ->
            return runCatching {
                formatable.connect()
                formatable.format(message)
                NfcWriteResult.Success(size)
            }.also { runCatching { formatable.close() } }
                .getOrElse { NfcWriteResult.Failure(it.message ?: "Format failed") }
        }

        return NfcWriteResult.Failure("Tag doesn't support NDEF writing")
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
