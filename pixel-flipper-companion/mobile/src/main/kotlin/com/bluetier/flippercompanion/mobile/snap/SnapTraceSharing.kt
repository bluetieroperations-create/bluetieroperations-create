package com.bluetier.flippercompanion.mobile.snap

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Outcome of trying to share the trace CSV, so the UI can say what happened. */
sealed interface ShareResult {
    data object Shared : ShareResult

    /** Capture has not written anything yet, so there is nothing to send. */
    data object NothingToShare : ShareResult

    data class Failed(val reason: String) : ShareResult
}

/** Outcome of copying the CSV into the public Downloads folder. */
sealed interface ExportResult {
    data class Exported(val fileName: String, val bytes: Long) : ExportResult
    /** Carries what was actually found, so the screen can say why. */
    data class NothingToExport(
        val path: String,
        val exists: Boolean,
        val bytes: Long,
    ) : ExportResult
    data class Failed(val reason: String) : ExportResult
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

    /**
     * Copies the CSV into the device's public Downloads folder.
     *
     * The share sheet hands another app a `content://` URI with a read grant
     * scoped to that one share. Apps that copy the bytes immediately are fine;
     * apps that defer reading until later — an email client waiting until you
     * press send — can find the grant already gone and attach nothing, which
     * looks like success and produces a zero-byte file.
     *
     * Writing into Downloads sidesteps the whole mechanism: a real file lands
     * where the Files app, a USB connection and every other app can see it, with
     * no grant to expire. Uses MediaStore because an app cannot write there
     * directly under scoped storage.
     *
     * Each export is timestamped rather than overwriting, so a capture session
     * cannot be clobbered by a later one.
     */
    fun exportToDownloads(context: Context): ExportResult {
        val source = SnapTraceStore.file(context)
        if (!source.exists() || source.length() == 0L) {
            return ExportResult.NothingToExport(
                path = source.absolutePath,
                exists = source.exists(),
                bytes = if (source.exists()) source.length() else 0L,
            )
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "snap-traces-$stamp.csv"

        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return ExportResult.Failed("could not create a file in Downloads")

            val written = context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: return ExportResult.Failed("could not open the Downloads file for writing")

            ExportResult.Exported(fileName, written)
        }.getOrElse {
            Log.w(TAG, "Could not export the snap trace file", it)
            ExportResult.Failed(it.message ?: it::class.java.simpleName)
        }
    }
}
