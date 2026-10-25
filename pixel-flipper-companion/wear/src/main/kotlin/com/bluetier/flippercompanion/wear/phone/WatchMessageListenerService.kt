package com.bluetier.flippercompanion.wear.phone

import android.util.Log
import com.bluetier.flippercompanion.shared.WearContract
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the phone's verdict on a snap and hands it to [WatchResultBridge].
 *
 * Registered in the manifest for the "/flipper" path prefix so the system can
 * start it on demand, mirroring the phone's listener.
 */
class WatchMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearContract.PATH_RESULT) return

        val text = String(event.data, Charsets.UTF_8)
        Log.d(TAG, "Phone replied: $text")
        WatchResultBridge.post(text)
    }

    private companion object {
        const val TAG = "WatchMsgListener"
    }
}
