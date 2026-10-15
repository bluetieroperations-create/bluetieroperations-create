package com.bluetier.flippercompanion.mobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** High-level connection state surfaced to the UI. */
enum class ConnectionState { Disconnected, Connecting, Discovering, Ready, Failed }

/** A characteristic flattened with the capabilities the UI cares about. */
data class GattCharacteristicInfo(
    val serviceUuid: UUID,
    val uuid: UUID,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canWriteNoResponse: Boolean,
    val canNotify: Boolean,
)

data class GattServiceInfo(
    val uuid: UUID,
    val characteristics: List<GattCharacteristicInfo>,
)

/** One-shot results of GATT operations, consumed by the UI as a stream. */
sealed interface GattEvent {
    data class Read(val uuid: UUID, val value: ByteArray) : GattEvent
    data class Written(val uuid: UUID, val success: Boolean) : GattEvent
    data class Notification(val uuid: UUID, val value: ByteArray) : GattEvent
    data class Error(val message: String) : GattEvent
}

/**
 * Wraps a single [BluetoothGatt] connection: connect, discover, read, write, and
 * subscribe to notifications.
 *
 * Android's GATT stack tolerates exactly one outstanding operation at a time, so
 * every read/write/descriptor-write goes through a serial [enqueue] queue and the
 * next op only starts once the previous one's callback has fired. All GATT calls
 * are posted to the main thread, which the platform expects.
 *
 * Requires BLUETOOTH_CONNECT (API 31+); the caller must hold it before [connect].
 */
@SuppressLint("MissingPermission")
class BleConnection(private val context: Context) {

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _services = MutableStateFlow<List<GattServiceInfo>>(emptyList())
    val services: StateFlow<List<GattServiceInfo>> = _services.asStateFlow()

    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GattEvent> = _events.asSharedFlow()

    private var gatt: BluetoothGatt? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    fun connect(address: String): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false

        disconnect()
        _state.value = ConnectionState.Connecting
        gatt = device.connectGatt(context, false, callback, BluetoothDevice_TRANSPORT_LE)
        return gatt != null
    }

    fun disconnect() {
        clearQueue()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        _services.value = emptyList()
        _state.value = ConnectionState.Disconnected
    }

    fun readCharacteristic(serviceUuid: UUID, charUuid: UUID) {
        val characteristic = characteristicOrError(serviceUuid, charUuid) ?: return
        enqueue {
            val started = gatt?.readCharacteristic(characteristic) == true
            if (!started) failOperation("Read could not be started")
        }
    }

    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, value: ByteArray, withResponse: Boolean) {
        val characteristic = characteristicOrError(serviceUuid, charUuid) ?: return
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        enqueue {
            val g = gatt ?: run { failOperation("Not connected"); return@enqueue }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(characteristic, value, writeType) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = writeType
                    characteristic.value = value
                    g.writeCharacteristic(characteristic)
                }
            }
            if (!started) failOperation("Write could not be started")
        }
    }

    fun setNotifications(serviceUuid: UUID, charUuid: UUID, enable: Boolean) {
        val characteristic = characteristicOrError(serviceUuid, charUuid) ?: return
        enqueue {
            val g = gatt ?: return@enqueue
            g.setCharacteristicNotification(characteristic, enable)
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                _events.tryEmit(GattEvent.Error("Characteristic has no CCCD descriptor"))
                operationComplete()
                return@enqueue
            }
            val payload = when {
                !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                characteristic.supportsIndicate() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, payload) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = payload
                    g.writeDescriptor(cccd)
                }
            }
            if (!started) failOperation("Enabling notifications could not be started")
        }
    }

    // --- GATT callback -------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = ConnectionState.Discovering
                    mainHandler.post { gatt.discoverServices() }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clearQueue()
                    _services.value = emptyList()
                    _state.value =
                        if (status == BluetoothGatt.GATT_SUCCESS) ConnectionState.Disconnected
                        else ConnectionState.Failed
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.Failed
                return
            }
            _services.value = gatt.services.map { service ->
                GattServiceInfo(
                    uuid = service.uuid,
                    characteristics = service.characteristics.map { it.toInfo() },
                )
            }
            _state.value = ConnectionState.Ready
        }

        // API 33+ read callback (carries the value).
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            emitRead(characteristic.uuid, value, status)
            operationComplete()
        }

        // Pre-33 read callback (value lives on the characteristic).
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                emitRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)
                operationComplete()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            _events.tryEmit(
                GattEvent.Written(characteristic.uuid, status == BluetoothGatt.GATT_SUCCESS),
            )
            operationComplete()
        }

        // API 33+ notification callback.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            _events.tryEmit(GattEvent.Notification(characteristic.uuid, value))
        }

        // Pre-33 notification callback.
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                _events.tryEmit(
                    GattEvent.Notification(characteristic.uuid, characteristic.value ?: ByteArray(0)),
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _events.tryEmit(GattEvent.Error("Enabling notifications failed ($status)"))
            }
            operationComplete()
        }
    }

    private fun emitRead(uuid: UUID, value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            _events.tryEmit(GattEvent.Read(uuid, value))
        } else {
            _events.tryEmit(GattEvent.Error("Read failed ($status)"))
        }
    }

    // --- Serial operation queue ---------------------------------------------

    private fun enqueue(op: () -> Unit) {
        synchronized(opQueue) {
            opQueue.addLast(op)
            maybeRunNext()
        }
    }

    private fun maybeRunNext() {
        synchronized(opQueue) {
            if (opInFlight) return
            val op = opQueue.removeFirstOrNull() ?: return
            opInFlight = true
            mainHandler.post(op)
        }
    }

    private fun operationComplete() {
        synchronized(opQueue) { opInFlight = false }
        maybeRunNext()
    }

    private fun clearQueue() {
        synchronized(opQueue) {
            opQueue.clear()
            opInFlight = false
        }
    }

    /** Report an op that never got off the ground, and free the queue. */
    private fun failOperation(message: String) {
        _events.tryEmit(GattEvent.Error(message))
        operationComplete()
    }

    private fun characteristicOrError(serviceUuid: UUID, charUuid: UUID): BluetoothGattCharacteristic? {
        val characteristic = gatt?.getService(serviceUuid)?.getCharacteristic(charUuid)
        if (characteristic == null) {
            _events.tryEmit(GattEvent.Error("Characteristic $charUuid not found"))
        }
        return characteristic
    }

    private fun BluetoothGattCharacteristic.toInfo() = GattCharacteristicInfo(
        serviceUuid = service.uuid,
        uuid = uuid,
        canRead = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0,
        canWrite = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
        canWriteNoResponse = properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
        canNotify = properties and (
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            ) != 0,
    )

    private fun BluetoothGattCharacteristic.supportsIndicate(): Boolean =
        properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

    companion object {
        /** Client Characteristic Configuration Descriptor. */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** BluetoothDevice.TRANSPORT_LE — inlined to avoid an extra import. */
        private const val BluetoothDevice_TRANSPORT_LE = 2
    }
}
