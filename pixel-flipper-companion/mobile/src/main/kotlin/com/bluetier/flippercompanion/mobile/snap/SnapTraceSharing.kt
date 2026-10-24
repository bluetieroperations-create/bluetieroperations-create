package com.bluetier.flippercompanion.mobile.snap

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider

/** Outcome of trying to share the trace CSV, so the UI can say what happened. */
sealed interface ShareResult {
    data object Shared : ShareResult

    /** Capture has not written anything yet, so there is nothing to send. */
    data object NothingToShare : ShareResult

    data class Failed(val reason: String) : ShareResult
}

/**
 * Hands the accumulated snap-trace CSV to any app that can take a file —
 * email, Drive, a messaging app — so the data can leave the phone without adb.
 *
 * Pulling the file over adb means pairing the phone, finding a rotating port
 * and getting a path right, every time. Tuning the detector means fetching this
 * file over and over, so the share sheet is the difference between a round trip
 * that takes two taps and one that takes ten minutes.
 */
object SnapTraceSharing {

    private const val TAG = "SnapTraceSharing"

    /** Must match the authority declared for the provider in the manifest. */
    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    fun share(context: Context): ShareResult {
        val file = SnapTraceStore.file(context)
        if (!file.exists() || file.length() == 0L) return ShareResult.NothingToShare

        return runCatching {
            val uri = FileProvider.getUriForFile(context, authority(context), file)

            val send = Intent(Intent.ACTION_SEND).apply {
                // text/csv rather than text/plain: plain text tempts apps into
                // pasting the contents into a message body instead of attaching.
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Snap traces (${file.length() / 1024} KB)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // The chooser needs the grant too, or targets that preview the file
            // before launching cannot read it.
            val chooser = Intent.createChooser(send, "Share snap traces").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(chooser)
            ShareResult.Shared
        }.getOrElse {
            Log.w(TAG, "Could not share the snap trace file", it)
            ShareResult.Failed(it.message ?: it::class.java.simpleName)
        }
    }
}
