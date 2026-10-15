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
        NFC_READ("nfc_read");

        companion object {
            fun fromId(id: String): Action? = entries.firstOrNull { it.id == id }
        }
    }
}
