package com.bluetier.flippercompanion.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdentityTest {

    private fun device(
        companyIds: Set<Int> = emptySet(),
        serviceUuids16: Set<String> = emptySet(),
        appleFindMy: Boolean = false,
    ) = BleDeviceInfo(
        address = "AA:BB:CC:DD:EE:FF", name = null, rssi = -60, smoothedRssi = -60.0,
        firstSeenMillis = 0, lastSeenMillis = 0, count = 1, minRssi = -60, maxRssi = -60,
        companyIds = companyIds, serviceUuids16 = serviceUuids16, appleFindMy = appleFindMy,
    )

    @Test fun makerAndType() {
        assertEquals("Apple · heart rate", DeviceIdentity.describe(device(setOf(0x004C), setOf("180D"))))
        assertEquals("Google · Fast Pair", DeviceIdentity.describe(device(setOf(0x00E0), setOf("FE2C"))))
        assertEquals("Samsung · SmartTag", DeviceIdentity.describe(device(setOf(0x0075), setOf("FD5A"))))
    }

    @Test fun makerOnly() {
        assertEquals("Nordic Semiconductor", DeviceIdentity.describe(device(setOf(0x0059))))
        assertEquals("Microsoft", DeviceIdentity.describe(device(setOf(0x0006))))
    }

    @Test fun typeOnlyIsCapitalized() {
        assertEquals("Heart rate", DeviceIdentity.describe(device(serviceUuids16 = setOf("180D"))))
        assertEquals("Tile", DeviceIdentity.describe(device(serviceUuids16 = setOf("FEED"))))
    }

    @Test fun findMyBecomesAType() {
        assertEquals("Apple · Find My tag", DeviceIdentity.describe(device(setOf(0x004C), appleFindMy = true)))
    }

    @Test fun unknownWhenNothingIdentifiable() {
        assertEquals("Unknown device", DeviceIdentity.describe(device()))
        assertEquals("Unknown device", DeviceIdentity.describe(device(companyIds = setOf(0xABCD))))
        assertEquals("Unknown device", DeviceIdentity.describe(device(serviceUuids16 = setOf("1234"))))
    }

    @Test fun specificTypeWinsOverGeneric() {
        // A device advertising both a SmartTag UUID and a generic service -> SmartTag.
        assertEquals(
            "SmartTag",
            DeviceIdentity.describe(device(serviceUuids16 = setOf("181A", "FD5A"))),
        )
    }
}
