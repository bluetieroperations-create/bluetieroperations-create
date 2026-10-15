package com.bluetier.flippercompanion.mobile.obd

import com.bluetier.flippercompanion.mobile.ble.CharRef
import com.bluetier.flippercompanion.mobile.ble.GattCharacteristicInfo
import com.bluetier.flippercompanion.mobile.ble.GattServiceInfo
import java.util.UUID

/** The write (phone -> dongle) and notify (dongle -> phone) characteristics of a UART link. */
data class UartChannel(
    val tx: CharRef,
    val rx: CharRef,
    /** True if the TX characteristic supports Write Without Response (typical for UART). */
    val txWriteNoResponse: Boolean,
)

/**
 * Picks the UART write/notify pair on a serial BLE adapter (ELM327 OBD dongles,
 * HM-10 modules, etc.). Tries the well-known vendor layouts first, then falls
 * back to "first writable + first notifiable". Pure, so it is unit-testable.
 */
object UartDetect {

    fun detect(services: List<GattServiceInfo>): UartChannel? {
        for (service in services) {
            // 1. HM-10 / cheap ELM327 clones: one characteristic 0xFFE1 does both.
            service.characteristic(FFE1)?.takeIf { it.canNotify && it.writable() }?.let {
                return channel(tx = it, rx = it)
            }
            // 2. Nordic UART Service: 0002 is write, 0003 is notify.
            val nusWrite = service.characteristic(NUS_WRITE)?.takeIf { it.writable() }
            val nusNotify = service.characteristic(NUS_NOTIFY)?.takeIf { it.canNotify }
            if (nusWrite != null && nusNotify != null) return channel(tx = nusWrite, rx = nusNotify)
            // 3. Vgate-style 0xFFF0 service: 0xFFF2 write, 0xFFF1 notify.
            val fff2 = service.characteristic(FFF2)?.takeIf { it.writable() }
            val fff1 = service.characteristic(FFF1)?.takeIf { it.canNotify }
            if (fff2 != null && fff1 != null) return channel(tx = fff2, rx = fff1)
        }
        // 4. Generic fallback: first notifiable + first writable within one service.
        for (service in services) {
            val notify = service.characteristics.firstOrNull { it.canNotify } ?: continue
            val write = service.characteristics.firstOrNull { it.writable() } ?: continue
            return channel(tx = write, rx = notify)
        }
        return null
    }

    private fun channel(tx: GattCharacteristicInfo, rx: GattCharacteristicInfo) =
        UartChannel(tx = tx.ref, rx = rx.ref, txWriteNoResponse = tx.canWriteNoResponse)

    private fun GattServiceInfo.characteristic(uuid: UUID) =
        characteristics.firstOrNull { it.uuid == uuid }

    private fun GattCharacteristicInfo.writable() = canWrite || canWriteNoResponse

    /** Builds a 16-bit Bluetooth SIG UUID (0000xxxx-0000-1000-8000-00805F9B34FB). */
    private fun uuid16(short: Int): UUID =
        UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))

    private val FFE1 = uuid16(0xFFE1)
    private val FFF1 = uuid16(0xFFF1)
    private val FFF2 = uuid16(0xFFF2)
    private val NUS_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
}
