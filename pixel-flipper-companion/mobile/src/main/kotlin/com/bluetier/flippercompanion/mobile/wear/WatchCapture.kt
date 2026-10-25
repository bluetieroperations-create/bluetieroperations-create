package com.bluetier.flippercompanion.mobile.wear

import com.bluetier.flippercompanion.shared.Bearing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets a snap define where a target is, instead of triggering one.
 *
 * Calibrating with the phone and pointing with the watch cannot work, and the
 * reason is not obvious: they are different compasses, held in different
 * places, in different poses. The phone is aimed by hand at eye level; the
 * watch is strapped to a wrist at the end of an extended arm. Two readings of
 * the same television disagree by more than the resolver's whole tolerance.
 *
 * Worse, the watch's pointing axis could not be chosen by watching its own
 * screen, because turning the wrist to read the display is itself a different
 * orientation from pointing.
 *
 * Both problems vanish if the gesture defines the target. Point and snap to
 * record it, point and snap to fire it: same sensor, same axis, same arm. The
 * axis never has to be correct in absolute terms, only unchanged between the
 * two, so it cancels - which is why the watch now remembers it.
 *
 * Process-wide, like the other bridges here, because the snap arrives at a
 * service that the Activity need not be alive for.
 */
object WatchCapture {

    private val _armed = MutableStateFlow(false)

    /** True while the next deliberate snap will be taken as a calibration. */
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _captured = MutableStateFlow<Bearing?>(null)

    /** The most recent bearing a snap recorded, for the calibration screen. */
    val captured: StateFlow<Bearing?> = _captured.asStateFlow()

    fun arm() {
        _captured.value = null
        _armed.value = true
    }

    fun disarm() {
        _armed.value = false
    }

    /**
     * Offers a snap's bearing as a calibration. Returns true when it was taken,
     * in which case the snap must not also be treated as a trigger: a snap
     * recording where the television is should not turn it on at the same time.
     */
    fun offer(bearing: Bearing): Boolean {
        if (!_armed.value) return false
        _captured.value = bearing
        _armed.value = false
        return true
    }
}
