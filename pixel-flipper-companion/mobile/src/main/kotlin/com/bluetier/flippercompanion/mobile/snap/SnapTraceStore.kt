package com.bluetier.flippercompanion.mobile.snap

import android.content.Context
import android.util.Log
import com.bluetier.flippercompanion.shared.SnapTrace
import com.bluetier.flippercompanion.shared.SnapTracePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Accumulates snap traces arriving from the watch into a single CSV.
 *
 * A process-wide object for the same reason as
 * [com.bluetier.flippercompanion.mobile.wear.WearBridge]: the listener service
 * that receives traces can be started by the system without the Activity, so
 * there is no shared owner to hang this off.
 *
 * The file lands in the app's external files directory so it can be pulled with
 * the adb-over-Wi-Fi workflow already used to install the app:
 * ```
 * adb pull /sdcard/Android/data/com.bluetier.flippercompanion/files/snap-traces.csv
 * ```
 * [file] is surfaced in the UI so the real path can be read off the device
 * rather than guessed.
 */
object SnapTraceStore {

    private const val TAG = "SnapTraceStore"
    private const val FILE_NAME = "snap-traces.csv"

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    private val _lastTrace = MutableStateFlow<SnapTrace?>(null)
    val lastTrace: StateFlow<SnapTrace?> = _lastTrace.asStateFlow()

    private val _acceptedCount = MutableStateFlow(0)
    val acceptedCount: StateFlow<Int> = _acceptedCount.asStateFlow()

    /**
     * Prefers external app storage so the CSV can be pulled over adb; falls
     * back to internal storage when external is unavailable (no media mounted),
     * where it is still reachable via `adb shell run-as`.
     */
    fun file(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    /**
     * Appends one trace, writing the header first if this is a new file.
     *
     * Synchronized because [com.bluetier.flippercompanion.mobile.wear.PhoneMessageListenerService]
     * delivers on a binder thread and a burst of snaps can overlap.
     */
    @Synchronized
    fun append(
        context: Context,
        trace: SnapTrace,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ) {
        val target = file(context)
        runCatching {
            val isNew = !target.exists() || target.length() == 0L
            target.appendText(
                buildString {
                    if (isNew) {
                        append(SnapTracePayload.CSV_HEADER)
                        append('\n')
                    }
                    append(SnapTracePayload.toCsvRow(trace, capturedAtMillis))
                    append('\n')
                },
            )
            _count.value += 1
            if (trace.accepted) _acceptedCount.value += 1
            _lastTrace.value = trace
        }.onFailure { Log.w(TAG, "Could not append a snap trace", it) }
    }

    /**
     * Recounts rows from disk, so a restart does not reset the session counter
     * while the CSV still holds the data.
     */
    @Synchronized
    fun refresh(context: Context) {
        val target = file(context)
        if (!target.exists()) {
            _count.value = 0
            _acceptedCount.value = 0
            return
        }
        runCatching {
            // Drop the header; a trailing newline yields one blank entry.
            val rows = target.readLines().drop(1).filter { it.isNotBlank() }
            _count.value = rows.size
            // Column 2 (zero-based) is the accepted flag; see CSV_HEADER.
            _acceptedCount.value = rows.count { it.split(",").getOrNull(2) == "1" }
        }.onFailure { Log.w(TAG, "Could not read the snap trace file", it) }
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
            .onFailure { Log.w(TAG, "Could not delete the snap trace file", it) }
        _count.value = 0
        _acceptedCount.value = 0
        _lastTrace.value = null
    }
}
