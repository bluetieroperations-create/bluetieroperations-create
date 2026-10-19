package com.bluetier.flippercompanion.mobile.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerClassifierTest {

    private fun device(
        name: String? = null,
        companyIds: Set<Int> = emptySet(),
        serviceUuids16: Set<String> = emptySet(),
        appleFindMy: Boolean = false,
    ) = BleDeviceInfo(
        address = "AA:BB:CC:DD:EE:FF", name = name, rssi = -60, smoothedRssi = -60.0,
        firstSeenMillis = 0, lastSeenMillis = 0, count = 1, minRssi = -60, maxRssi = -60,
        companyIds = companyIds, serviceUuids16 = serviceUuids16, appleFindMy = appleFindMy,
    )

    @Test fun flagsAppleFindMyAsPossibleTracker() {
        val v = TrackerClassifier.classify(device(companyIds = setOf(0x004C), appleFindMy = true))
        assertEquals(TrackerKind.FIND_MY, v.kind)
        assertTrue(v.isPossibleTracker)
    }

    @Test fun plainAppleDeviceIsNotFlagged() {
        // Apple present but not the Find My type byte: AirPods/iPhone, not a tag.
        val v = TrackerClassifier.classify(device(companyIds = setOf(0x004C), appleFindMy = false))
        assertEquals(TrackerKind.APPLE_DEVICE, v.kind)
        assertFalse(v.isPossibleTracker)
    }

    @Test fun flagsSmartTagByServiceOrName() {
        assertEquals(TrackerKind.SMART_TAG, TrackerClassifier.classify(device(serviceUuids16 = setOf("FD5A"))).kind)
        assertEquals(TrackerKind.SMART_TAG, TrackerClassifier.classify(device(serviceUuids16 = setOf("FD59"))).kind)
        assertTrue(TrackerClassifier.classify(device(name = "Galaxy SmartTag2")).isPossibleTracker)
    }

    @Test fun flagsTile() {
        val v = TrackerClassifier.classify(device(serviceUuids16 = setOf("FEED")))
        assertEquals(TrackerKind.TILE, v.kind)
        assertTrue(v.isPossibleTracker)
    }

    @Test fun fastPairIsLabelledButNotFlagged() {
        val v = TrackerClassifier.classify(device(serviceUuids16 = setOf("FE2C")))
        assertEquals(TrackerKind.FAST_PAIR, v.kind)
        assertFalse(v.isPossibleTracker)
    }

    @Test fun unknownDeviceIsNotFlagged() {
        val v = TrackerClassifier.classify(device(serviceUuids16 = setOf("180F")))
        assertEquals(TrackerKind.UNKNOWN, v.kind)
        assertFalse(v.isPossibleTracker)
    }

    @Test fun findMyWinsOverPlainAppleCompanyId() {
        val v = TrackerClassifier.classify(device(companyIds = setOf(0x004C), appleFindMy = true))
        assertEquals(TrackerKind.FIND_MY, v.kind)
    }
}
