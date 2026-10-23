package com.bluetier.flippercompanion.mobile.wear

import android.util.Log
import com.bluetier.flippercompanion.mobile.snap.SnapTraceStore
import com.bluetier.flippercompanion.shared.SnapTracePayload
import com.bluetier.flippercompanion.shared.WearContract
import com.google.android.gms.wearable.MessageEvent
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
            else -> Unit
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

    companion object {
        private const val TAG = "PhoneMsgListener"
    }
}
