package com.bluetier.flippercompanion.mobile.ble

/**
 * Best-effort human label for a BLE advertiser: "<maker> · <type>".
 *
 * - Maker comes from the Bluetooth SIG company ID in the manufacturer data.
 *   Only well-established IDs are mapped; an unknown ID yields no maker rather
 *   than a wrong brand.
 * - Type comes from advertised service UUIDs — standardized SIG services (heart
 *   rate, HID, …) and a few stable vendor service UUIDs (Fast Pair, SmartTag…).
 *
 * Deliberately conservative: lots of devices advertise no name, no company ID,
 * and no identifying service (and many rotate their MAC for privacy), so the
 * honest answer for those is "Unknown device". Pure, so it is unit-testable.
 */
object DeviceIdentity {

    fun describe(device: BleDeviceInfo): String {
        val maker = MAKER_ORDER.firstOrNull { it in device.companyIds }?.let { MANUFACTURERS[it] }
        val type = when {
            device.appleFindMy -> "Find My tag"
            else -> TYPE_ORDER.firstOrNull { it in device.serviceUuids16 }?.let { SERVICE_TYPES[it] }
        }
        return when {
            maker != null && type != null -> "$maker · $type"
            maker != null -> maker
            type != null -> type.replaceFirstChar { it.uppercase() }
            else -> "Unknown device"
        }
    }

    // Bluetooth SIG company identifiers (only long-established, high-confidence IDs).
    private val MANUFACTURERS = linkedMapOf(
        0x004C to "Apple",
        0x0075 to "Samsung",
        0x00E0 to "Google",
        0x0006 to "Microsoft",
        0x0059 to "Nordic Semiconductor",
    )
    private val MAKER_ORDER = MANUFACTURERS.keys.toList()

    // Advertised service UUIDs that indicate what a device is. Most specific first.
    private val SERVICE_TYPES = linkedMapOf(
        "FD5A" to "SmartTag",
        "FD59" to "SmartTag",
        "FEED" to "Tile",
        "FE2C" to "Fast Pair",
        "FEAA" to "beacon (Eddystone)",
        "FD6F" to "exposure notification",
        "180D" to "heart rate",
        "1812" to "keyboard/mouse (HID)",
        "1826" to "fitness machine",
        "1818" to "cycling power",
        "1816" to "cycling sensor",
        "181A" to "environmental sensor",
        "1808" to "glucose meter",
        "1809" to "thermometer",
        "1802" to "proximity tag",
        "1803" to "proximity tag",
    )
    private val TYPE_ORDER = SERVICE_TYPES.keys.toList()
}
