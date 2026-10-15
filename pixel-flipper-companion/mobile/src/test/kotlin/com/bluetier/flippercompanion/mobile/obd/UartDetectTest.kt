package com.bluetier.flippercompanion.mobile.obd

import com.bluetier.flippercompanion.mobile.ble.CharRef
import com.bluetier.flippercompanion.mobile.ble.GattCharacteristicInfo
import com.bluetier.flippercompanion.mobile.ble.GattServiceInfo
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UartDetectTest {

    private fun uuid16(short: Int) = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
    private val nusService = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val nusWrite = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val nusNotify = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    private fun char(
        service: UUID, uuid: UUID, read: Boolean = false, write: Boolean = false,
        writeNr: Boolean = false, notify: Boolean = false, si: Int = 0, ci: Int = 0,
    ) = GattCharacteristicInfo(CharRef(service, si, uuid, ci), read, write, writeNr, notify)

    private fun service(uuid: UUID, chars: List<GattCharacteristicInfo>, si: Int = 0) =
        GattServiceInfo(uuid, si, chars)

    @Test fun detectsHm10SharedCharacteristic() {
        val svc = uuid16(0xFFE0)
        val ch = char(svc, uuid16(0xFFE1), write = true, writeNr = true, notify = true)
        val result = UartDetect.detect(listOf(service(svc, listOf(ch))))!!
        assertEquals(uuid16(0xFFE1), result.tx.charUuid)
        assertEquals(uuid16(0xFFE1), result.rx.charUuid)
        assertEquals(true, result.txWriteNoResponse)
    }

    @Test fun detectsNordicUartService() {
        val svc = service(nusService, listOf(
            char(nusService, nusWrite, write = true, writeNr = true),
            char(nusService, nusNotify, notify = true),
        ))
        val result = UartDetect.detect(listOf(svc))!!
        assertEquals(nusWrite, result.tx.charUuid)
        assertEquals(nusNotify, result.rx.charUuid)
    }

    @Test fun detectsVgateFff0Layout() {
        val svc = uuid16(0xFFF0)
        val result = UartDetect.detect(listOf(service(svc, listOf(
            char(svc, uuid16(0xFFF1), notify = true),
            char(svc, uuid16(0xFFF2), write = true),
        ))))!!
        assertEquals(uuid16(0xFFF2), result.tx.charUuid)
        assertEquals(uuid16(0xFFF1), result.rx.charUuid)
    }

    @Test fun fallsBackToFirstWritableAndNotifiable() {
        val svc = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val result = UartDetect.detect(listOf(service(svc, listOf(
            char(svc, UUID.fromString("0000ab01-0000-1000-8000-00805f9b34fb"), notify = true, ci = 1),
            char(svc, UUID.fromString("0000ab02-0000-1000-8000-00805f9b34fb"), write = true, ci = 2),
        ))))!!
        assertEquals("0000ab02-0000-1000-8000-00805f9b34fb", result.tx.charUuid.toString())
        assertEquals("0000ab01-0000-1000-8000-00805f9b34fb", result.rx.charUuid.toString())
    }

    @Test fun returnsNullWhenNoUsableCharacteristics() {
        val svc = uuid16(0x180A) // Device Information: read-only
        assertNull(UartDetect.detect(listOf(service(svc, listOf(
            char(svc, uuid16(0x2A29), read = true),
        )))))
        assertNull(UartDetect.detect(emptyList()))
    }

    @Test fun prefersKnownLayoutOverFallbackOrdering() {
        // A notify-only char appears first, but the NUS pair must still win.
        val svc = service(nusService, listOf(
            char(nusService, nusNotify, notify = true, ci = 3),
            char(nusService, nusWrite, write = true, writeNr = true, ci = 2),
        ))
        val result = UartDetect.detect(listOf(svc))!!
        assertEquals(nusWrite, result.tx.charUuid)
        assertEquals(nusNotify, result.rx.charUuid)
    }
}
