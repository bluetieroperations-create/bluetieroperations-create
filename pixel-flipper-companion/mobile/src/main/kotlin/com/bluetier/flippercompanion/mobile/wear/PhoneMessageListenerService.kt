package com.bluetier.flippercompanion.mobile.wear

import android.util.Log
import com.bluetier.flippercompanion.mobile.snap.SnapTraceStore
import com.bluetier.flippercompanion.shared.SnapPointPayload
import com.bluetier.flippercompanion.shared.SnapTracePayload
import com.bluetier.flippercompanion.shared.WearContract
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives trigger messages the watch sends over the Wearable Data Layer and
 * forwards them to [WearBridge]. Registered in the manifest for the
 * "/flipper" path prefix, so the system can start it on demand.
 */
class PhoneMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearContract.PATH_TRIGGER -> onTrigger(event)
            WearContract.PATH_SNAP_TRACE -> onSnapTrace(event)
            WearContract.PATH_SNAP_POINT -> onSnapPoint(event)
            else -> Unit
        }
    }

    /**
     * A snap from the watch, with the direction the arm was aimed in.
     *
     * Handled here rather than in a ViewModel because the gesture has to work
     * with the phone in a pocket and the app nowhere on screen - which is the
     * entire point of it being a gesture.
     *
     * The verdict goes back to the watch, so a snap that resolved to nothing is
     * distinguishable from one that was never detected.
     */
    private fun onSnapPoint(event: MessageEvent) {
        val point = SnapPointPayload.decode(event.data)
        if (point == null) {
            Log.w(TAG, "Undecodable snap point from node ${event.sourceNodeId}")
            return
        }

        val node = event.sourceNodeId
        scope.launch {
            val outcome = runCatching {
                SnapPointHandler.handle(this@PhoneMessageListenerService, point)
            }.getOrElse { error ->
                Log.w(TAG, "Snap handling failed", error)
                "Failed: ${error.message ?: error::class.java.simpleName}"
            }

            runCatching {
                Wearable.getMessageClient(this@PhoneMessageListenerService)
                    .sendMessage(node, WearContract.PATH_RESULT, outcome.toByteArray(Charsets.UTF_8))
            }.onFailure { Log.w(TAG, "Could not reply to the watch", it) }
        }
    }

    /**
     * Stores one snap trace from the watch. Decode failures are logged and
     * dropped rather than thrown: a trace is diagnostic data, and losing one to
     * a version skew is not worth taking the service down for.
     */
    private fun onSnapTrace(event: MessageEvent) {
        val trace = SnapTracePayload.decode(event.data)
        if (trace == null) {
            Log.w(TAG, "Undecodable snap trace from node ${event.sourceNodeId}")
            return
        }
        SnapTraceStore.append(this, trace)
        Log.d(TAG, "Snap trace: accepted=${trace.accepted} hz=${trace.accelHz} n=${trace.sampleCount}")
    }

    private fun onTrigger(event: MessageEvent) {
        val actionId = String(event.data, Charsets.UTF_8)
        val action = WearContract.Action.fromId(actionId)
        if (action == null) {
            Log.w(TAG, "Unknown action from watch: $actionId")
            return
        }
        Log.d(TAG, "Watch triggered: ${action.id} (from node ${event.sourceNodeId})")
        WearBridge.emit(action)
    }

    /**
     * Outlives any single message: a dispatch is a network round trip, and the
     * service may be torn down once onMessageReceived returns.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "PhoneMsgListener"
    }
}
