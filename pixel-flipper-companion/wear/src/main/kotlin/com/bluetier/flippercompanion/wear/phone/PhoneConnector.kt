package com.bluetier.flippercompanion.wear.phone

import android.content.Context
import com.bluetier.flippercompanion.shared.SnapTrace
import com.bluetier.flippercompanion.shared.SnapTracePayload
import com.bluetier.flippercompanion.shared.WearContract
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Result of trying to deliver a trigger to the phone. */
sealed interface SendResult {
    data object Delivered : SendResult
    data object NoPhoneConnected : SendResult
    data class Failed(val error: Throwable) : SendResult
}

/**
 * Sends trigger actions from the watch to the phone app over the Wearable Data
 * Layer. The phone node is discovered by the capability it advertises
 * ([WearContract.CAPABILITY_PHONE_APP]) rather than by a hard-coded node id, so
 * this keeps working across re-pairings.
 */
class PhoneConnector(context: Context) {

    private val capabilityClient = Wearable.getCapabilityClient(context)
    private val messageClient = Wearable.getMessageClient(context)

    suspend fun isPhoneReachable(): Boolean = bestPhoneNodeId() != null

    suspend fun trigger(action: WearContract.Action): SendResult {
        val nodeId = bestPhoneNodeId() ?: return SendResult.NoPhoneConnected
        return runCatching {
            messageClient.sendMessage(
                nodeId,
                WearContract.PATH_TRIGGER,
                action.id.toByteArray(Charsets.UTF_8),
            ).await()
            SendResult.Delivered
        }.getOrElse { SendResult.Failed(it) }
    }

    /**
     * Ships one captured snap trace to the phone, which accumulates them into a
     * CSV. Same node discovery as [trigger], so a trace follows the phone
     * across re-pairings too.
     */
    suspend fun sendSnapTrace(trace: SnapTrace): SendResult {
        val nodeId = bestPhoneNodeId() ?: return SendResult.NoPhoneConnected
        return runCatching {
            messageClient.sendMessage(
                nodeId,
                WearContract.PATH_SNAP_TRACE,
                SnapTracePayload.encode(trace),
            ).await()
            SendResult.Delivered
        }.getOrElse { SendResult.Failed(it) }
    }

    /** Prefer a nearby (directly connected) node when several advertise the capability. */
    private suspend fun bestPhoneNodeId(): String? {
        val capability = runCatching {
            capabilityClient
                .getCapability(WearContract.CAPABILITY_PHONE_APP, CapabilityClient.FILTER_REACHABLE)
                .await()
        }.getOrNull() ?: return null

        val nodes = capability.nodes
        return (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    }
}
