package com.bluetier.flippercompanion.mobile.target

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The calibrated target registry, kept on disk.
 *
 * A process-wide object for the same reason as
 * [com.bluetier.flippercompanion.mobile.snap.SnapTraceStore]: the resolver may
 * be reached from a service started without the Activity, so there is no
 * natural owner to hang it off.
 *
 * Writes go through immediately rather than on a timer. A registry is small and
 * changes rarely, and losing a calibration to a process death would mean
 * walking round the house again.
 */
object TargetStore {

    private const val TAG = "TargetStore"
    private const val FILE_NAME = "targets.txt"

    private val _registry = MutableStateFlow(TargetRegistry())
    val registry: StateFlow<TargetRegistry> = _registry.asStateFlow()

    /** Non-zero after a load that could not read part of the file. */
    private val _skippedLines = MutableStateFlow(0)
    val skippedLines: StateFlow<Int> = _skippedLines.asStateFlow()

    /**
     * Internal storage, not external: this is app state rather than something
     * to hand to another app, and internal storage is always available.
     */
    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(context: Context) {
        val target = file(context)
        if (!target.exists()) {
            _registry.value = TargetRegistry()
            _skippedLines.value = 0
            return
        }
        runCatching {
            val decoded = TargetRegistryCodec.decode(target.readText())
            if (decoded == null) {
                // A file of an unknown version is left alone rather than
                // overwritten: a future build may understand it.
                Log.w(TAG, "Unrecognised target registry format; leaving it untouched")
                _registry.value = TargetRegistry()
                _skippedLines.value = 0
            } else {
                _registry.value = decoded.registry
                _skippedLines.value = decoded.skippedLines
                if (decoded.skippedLines > 0) {
                    Log.w(TAG, "Skipped ${decoded.skippedLines} unreadable target line(s)")
                }
            }
        }.onFailure { Log.w(TAG, "Could not read the target registry", it) }
    }

    @Synchronized
    fun save(context: Context, registry: TargetRegistry): Boolean =
        runCatching {
            file(context).writeText(TargetRegistryCodec.encode(registry))
            _registry.value = registry
            true
        }.getOrElse {
            Log.w(TAG, "Could not write the target registry", it)
            false
        }

    fun add(context: Context, target: Target): Boolean =
        save(context, _registry.value.with(target))

    fun remove(context: Context, id: String): Boolean =
        save(context, _registry.value.without(id))

    fun clear(context: Context): Boolean = save(context, TargetRegistry())
}
