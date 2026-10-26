package com.bluetier.flippercompanion.mobile.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.util.Log
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

    private companion object {
        /** One tag for the whole NFC path, matching the watch's snap logging. */
        const val LOG_TAG = "FlipperNfc"
    }

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
        val adapter = adapter ?: run {
            Log.w(LOG_TAG, "enable: no NFC adapter on this device")
            return
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
        // No FLAG_READER_SKIP_NDEF_CHECK: the platform only adds the Ndef tech to
        // the Tag when it runs its NDEF check, so skipping it makes Ndef.get()
        // return null for every tag.
        adapter.enableReaderMode(activity, ::onTagDiscovered, flags, Bundle())
        Log.d(LOG_TAG, "reader mode on, adapter enabled=${adapter.isEnabled}")
    }

    fun disable() {
        adapter?.disableReaderMode(activity)
        Log.d(LOG_TAG, "reader mode off")
    }

    private fun onTagDiscovered(tag: Tag) {
        // Runs on a background (reader-mode) thread, so blocking tag I/O is fine here.
        val techs = tag.techList.map { it.substringAfterLast('.') }
        Log.d(LOG_TAG, "tag seen uid=${tag.id.toHex()} techs=$techs armed=${pendingMessage != null}")

        val message = pendingMessage
        if (message != null) {
            pendingMessage = null // one-shot
            _armed.value = false
            val result = writeTag(tag, message)
            Log.d(LOG_TAG, "write result=$result")
            _writeResult.tryEmit(result)
            return
        }

        // Reading NDEF off a tag is the one step here that touches the tag's
        // own contents, so it is the one that can fail on a tag that is
        // otherwise fine. Failing it must not lose the tag: the uid and the
        // technologies are worth having even when the payload is not readable.
        val text = runCatching { readNdefText(tag) }
            .onFailure { Log.w(LOG_TAG, "could not read NDEF from the tag", it) }
            .getOrNull()

        Log.d(LOG_TAG, "tag read uid=${tag.id.toHex()} ndef=${text?.take(80)}")
        _lastTag.value = NfcTagInfo(
            uidHex = tag.id.toHex(),
            techList = techs,
            ndefText = text,
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
