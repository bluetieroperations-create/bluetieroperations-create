package com.bluetier.flippercompanion.mobile.wear

import android.util.Log
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
        if (event.path != WearContract.PATH_TRIGGER) return

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
