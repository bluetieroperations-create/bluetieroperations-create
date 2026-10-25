package com.bluetier.flippercompanion.mobile.wear

import android.content.Context
import android.util.Log
import com.bluetier.flippercompanion.mobile.dispatch.ActionParser
import com.bluetier.flippercompanion.mobile.dispatch.DispatchResult
import com.bluetier.flippercompanion.mobile.dispatch.Dispatcher
import com.bluetier.flippercompanion.mobile.target.Observation
import com.bluetier.flippercompanion.mobile.target.Resolution
import com.bluetier.flippercompanion.mobile.target.TargetResolver
import com.bluetier.flippercompanion.mobile.target.TargetStore
import com.bluetier.flippercompanion.shared.SnapPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns a snap from the watch into something happening in the house.
 *
 * Deliberately independent of the UI. A snap has to work with the phone in a
 * pocket and the app nowhere on screen, so this lives where the listener
 * service can reach it rather than in a ViewModel that may not exist.
 *
 * It holds the one rule the watch cannot apply for itself: a snap made with
 * the arm in motion carries a direction nobody meant, and is declined.
 */
object SnapPointHandler {

    private const val TAG = "SnapPointHandler"

    /**
     * Widest wobble, in degrees, still counted as a deliberate aim.
     *
     * Measured snaps held the arm to well under this. The point is to reject a
     * snap thrown mid-gesture, where the "direction" is wherever the arm
     * happened to be sweeping through.
     */
    private const val MAX_AIM_SPREAD_DEGREES = 12.0

    private val resolver = TargetResolver()
    private val dispatcher = Dispatcher()

    private val _lastOutcome = MutableStateFlow<String?>(null)
    val lastOutcome: StateFlow<String?> = _lastOutcome.asStateFlow()

    /**
     * Resolves and acts on a snap. Returns a short line describing what
     * happened, suitable for sending back to the watch.
     *
     * [visibleRssi] is the live BLE census when there is one; an empty map
     * simply means presence cannot break a tie, not that nothing resolves.
     */
    suspend fun handle(
        context: Context,
        point: SnapPoint,
        visibleRssi: Map<String, Double> = emptyMap(),
    ): String {
        if (point.aimSpreadDegrees > MAX_AIM_SPREAD_DEGREES) {
            return outcome("Arm was moving — ignored")
        }

        // The registry may not have been loaded in this process yet: a snap can
        // arrive at a service started without the Activity ever running.
        if (TargetStore.registry.value.targets.isEmpty()) {
            TargetStore.load(context)
        }

        val resolution = resolver.resolve(
            TargetStore.registry.value,
            Observation(point.bearing, visibleRssi),
        )

        val target = when (resolution) {
            is Resolution.Resolved -> resolution.candidate.target
            is Resolution.Ambiguous -> return outcome(
                "Ambiguous: ${resolution.first.target.label} or ${resolution.second.target.label}",
            )
            is Resolution.NoMatch -> return outcome("Not pointing at a target")
            Resolution.EmptyRegistry -> return outcome("No targets calibrated")
        }

        Log.d(TAG, "Snap resolved to ${target.label}; dispatching ${target.action}")

        return when (val result = dispatcher.dispatch(ActionParser.parse(target.action))) {
            is DispatchResult.Success -> outcome("${target.label} ✓")
            is DispatchResult.Rejected -> outcome("${target.label}: refused (${result.detail})")
            is DispatchResult.Unreachable -> outcome("${target.label}: unreachable")
            is DispatchResult.NotSupported -> outcome("${target.label}: ${result.detail}")
        }
    }

    private fun outcome(text: String): String {
        _lastOutcome.value = text
        return text
    }
}
