package com.bluetier.flippercompanion.shared

/**
 * The single source of truth for the phone <-> watch messaging contract.
 *
 * Both the :mobile and :wear modules depend on this so a path can never drift
 * between the two sides. Messages travel over the Google Play Services
 * Wearable Data Layer (MessageClient / CapabilityClient).
 */
object WearContract {

    /**
     * Capability advertised by the phone app (declared in :mobile's
     * res/values/wear.xml). The watch looks this capability up to discover
     * which connected node can service a trigger.
     */
    const val CAPABILITY_PHONE_APP = "flipper_companion_phone"

    /** MessageClient path: watch -> phone, "run this action now". */
    const val PATH_TRIGGER = "/flipper/trigger"

    /** MessageClient path: phone -> watch, "here is the result/ack". */
    const val PATH_RESULT = "/flipper/result"

    /**
     * MessageClient path: watch -> phone, one snap-candidate trace.
     *
     * Payload is encoded by [SnapTracePayload]. Covered by the same "/flipper"
     * path prefix the phone's listener service is registered for, so adding it
     * needs no manifest change.
     */
    const val PATH_SNAP_TRACE = "/flipper/snap-trace"

    /**
     * MessageClient path: watch -> phone, "I snapped, aimed this way".
     *
     * Payload is encoded by [SnapPointPayload]. Separate from [PATH_TRIGGER]
     * because a trigger names an action while this names a *direction* - the
     * phone decides what it meant, from a registry the watch knows nothing
     * about.
     */
    const val PATH_SNAP_POINT = "/flipper/snap-point"

    /**
     * Actions the watch can request of the phone. Sent as the UTF-8 message
     * payload on [PATH_TRIGGER]. Kept as plain strings so the payload stays a
     * trivial, versionable contract (no shared serialization dependency).
     */
    enum class Action(val id: String) {
        /** Begin a BLE scan on the phone. */
        BLE_SCAN("ble_scan"),

        /** Arm NFC host-card emulation on the phone. */
        NFC_EMULATE("nfc_emulate"),

        /** Read the next NFC tag the phone is tapped against. */
        NFC_READ("nfc_read"),

        /** Start the watch-side snap/sensor capture session. */
        SNAP_LAB_START("snap_lab_start"),

        /** Stop it. */
        SNAP_LAB_STOP("snap_lab_stop");

        companion object {
            fun fromId(id: String): Action? = entries.firstOrNull { it.id == id }
        }
    }
}
