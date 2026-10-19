package com.bluetier.flippercompanion.mobile.ble

/** What a BLE advertiser looks like, from its advertised identifiers. */
enum class TrackerKind { FIND_MY, SMART_TAG, TILE, FAST_PAIR, APPLE_DEVICE, UNKNOWN }

data class TrackerVerdict(
    val kind: TrackerKind,
    val label: String,
    /** True for item-finder tags someone could use to track a person. */
    val isPossibleTracker: Boolean,
)

/**
 * Best-effort classification of a BLE advertiser from its company IDs, service
 * UUIDs, and name — using the published signatures of the common item trackers.
 * Pure, so it is unit-testable.
 *
 * This is a heuristic, not proof: a match means "looks like," and an UNKNOWN
 * device can still be a tracker. It complements, and does not replace, the
 * platform's own unwanted-tracker alerts.
 */
object TrackerClassifier {

    fun classify(device: BleDeviceInfo): TrackerVerdict {
        val services = device.serviceUuids16
        val name = device.name?.lowercase().orEmpty()

        return when {
            device.appleFindMy ->
                verdict(TrackerKind.FIND_MY, "Find My network (possible AirTag/accessory)", true)

            SMART_TAG_SERVICES.any { it in services } || "smarttag" in name || "smart tag" in name ->
                verdict(TrackerKind.SMART_TAG, "Samsung SmartTag", true)

            TILE_SERVICE in services || name == "tile" ->
                verdict(TrackerKind.TILE, "Tile tracker", true)

            FAST_PAIR_SERVICE in services ->
                verdict(TrackerKind.FAST_PAIR, "Google Fast Pair device", false)

            APPLE_COMPANY_ID in device.companyIds ->
                verdict(TrackerKind.APPLE_DEVICE, "Apple device (e.g. AirPods/iPhone)", false)

            else -> verdict(TrackerKind.UNKNOWN, "Unknown", false)
        }
    }

    private fun verdict(kind: TrackerKind, label: String, tracker: Boolean) =
        TrackerVerdict(kind, label, tracker)

    private const val APPLE_COMPANY_ID = 0x004C
    private val SMART_TAG_SERVICES = setOf("FD5A", "FD59")
    private const val TILE_SERVICE = "FEED"
    private const val FAST_PAIR_SERVICE = "FE2C"
}
