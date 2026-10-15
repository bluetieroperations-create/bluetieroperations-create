package com.bluetier.flippercompanion.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearContractTest {

    @Test fun everyActionRoundTripsThroughItsId() {
        WearContract.Action.entries.forEach { assertEquals(it, WearContract.Action.fromId(it.id)) }
    }

    @Test fun unknownIdsAreRejected() {
        assertNull(WearContract.Action.fromId("self_destruct"))
        assertNull(WearContract.Action.fromId(""))
        assertNull(WearContract.Action.fromId("BLE_SCAN")) // ids are case-sensitive
    }

    @Test fun actionIdsAreUnique() {
        val ids = WearContract.Action.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
