package com.bluetier.flippercompanion.mobile.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** High-level connection state surfaced to the UI. */
enum class ConnectionState { Disconnected, Connecting, Discovering, Ready, Reconnecting, Failed }

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
    data class Info(val message: String) : GattEvent
    data class Error(val message: String) : GattEvent
}

/**
 * Wraps a single [BluetoothGatt] connection: connect, negotiate MTU, discover,
 * read, write, and subscribe to notifications — and reconnect on its own when
 * the link drops.
 *
 * Connection lifecycle (all state transitions run on the main thread):
 * `Connecting → (requestMtu) → Discovering → Ready`. Any drop while the user
 * still wants the link — including a failed first attempt, e.g. the common
 * status 133 — closes the dead client and retries with exponential backoff
 * (1s, 2s, 4s… capped at 30s) up to [maxReconnectAttempts]. After a successful
 * reconnect, notification subscriptions that were active are re-armed.
 *
 * Android's GATT stack tolerates exactly one outstanding operation at a time, so
 * every read/write/descriptor-write goes through a serial [enqueue] queue and the
 * next op only starts once the previous one's callback has fired. Queued ops
 * resolve their characteristic by UUID when they run, so they stay valid across
 * a reconnect.
 *
 * Requires BLUETOOTH_CONNECT (API 31+); the caller must hold it before [connect].
 */
@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    private val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    private val desiredMtu: Int = MAX_ATT_MTU,
) {

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _services = MutableStateFlow<List<GattServiceInfo>>(emptyList())
    val services: StateFlow<List<GattServiceInfo>> = _services.asStateFlow()

    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GattEvent> = _events.asSharedFlow()

    private val _mtu = MutableStateFlow(DEFAULT_ATT_MTU)

    /** Negotiated ATT MTU. The largest single write/notification payload is `mtu - 3`. */
    val mtu: StateFlow<Int> = _mtu.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)

    /** 0 when not reconnecting; otherwise the 1-based attempt in progress. */
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    // Connection bookkeeping. Confined to the main thread: public calls come from
    // the UI, and GATT callbacks that touch it are re-posted via [onMain].
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null

    /** The device the user wants to be connected to; null after an explicit [disconnect]. */
    private var targetAddress: String? = null
    private var discoveryStarted = false

    /** Subscriptions to re-arm after a reconnect, keyed by (service, characteristic). */
    private val activeNotifications = LinkedHashSet<Pair<UUID, UUID>>()

    private val reconnectRunnable = Runnable { targetAddress?.let { openGatt(it) } }

    /** Proceeds to discovery if the peer never answers the MTU request. */
    private val mtuFallback = Runnable { gatt?.let { startDiscovery(it) } }

    // Serial operation queue (touched from binder threads too, hence the lock).
    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    @Volatile
    private var queueGeneration = 0

    /**
     * Starts connecting to [address]. Returns false if Bluetooth is off or the
     * address is invalid; otherwise progress is reported through [state].
     */
    fun connect(address: String): Boolean {
        if (remoteDevice(address) == null) return false
        disconnect()
        targetAddress = address
        openGatt(address)
        return true
    }

    /** Tears the link down and cancels any pending reconnect. */
    fun disconnect() {
        targetAddress = null
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(mtuFallback)
        clearQueue()
        activeNotifications.clear()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        resetLinkState()
        _reconnectAttempt.value = 0
        _state.value = ConnectionState.Disconnected
    }

    fun readCharacteristic(serviceUuid: UUID, charUuid: UUID) = enqueue {
        val (g, ch) = resolve(serviceUuid, charUuid) ?: return@enqueue
        if (!g.readCharacteristic(ch)) failOperation("Read could not be started")
    }

    fun writeCharacteristic(serviceUuid: UUID, charUuid: UUID, value: ByteArray, withResponse: Boolean) {
        val maxPayload = _mtu.value - ATT_HEADER_BYTES
        if (!withResponse && value.size > maxPayload) {
            // A Write Command must fit one packet; the stack would truncate or drop
            // it. Writes *with* response are split for us via Prepare/Execute Write.
            _events.tryEmit(
                GattEvent.Error(
                    "${value.size} B exceeds the $maxPayload B no-response limit at MTU ${_mtu.value}",
                ),
            )
            return
        }
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        enqueue {
            val (g, ch) = resolve(serviceUuid, charUuid) ?: return@enqueue
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = writeType
                    ch.value = value
                    g.writeCharacteristic(ch)
                }
            }
            if (!started) failOperation("Write could not be started")
        }
    }

    fun setNotifications(serviceUuid: UUID, charUuid: UUID, enable: Boolean) {
        val key = serviceUuid to charUuid
        if (enable) activeNotifications += key else activeNotifications -= key
        enqueueNotify(serviceUuid, charUuid, enable)
    }

    private fun enqueueNotify(serviceUuid: UUID, charUuid: UUID, enable: Boolean) = enqueue {
        val (g, ch) = resolve(serviceUuid, charUuid) ?: return@enqueue
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            failOperation("Characteristic has no CCCD descriptor")
            return@enqueue
        }
        g.setCharacteristicNotification(ch, enable)
        val payload = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            ch.supportsIndicate() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, payload) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = payload
                g.writeDescriptor(cccd)
            }
        }
        if (!started) failOperation("Updating notifications could not be started")
    }

    // --- Connection lifecycle (main thread) ---------------------------------

    private fun openGatt(address: String) {
        _state.value =
            if (_reconnectAttempt.value > 0) ConnectionState.Reconnecting else ConnectionState.Connecting
        val g = remoteDevice(address)?.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (g == null) {
            onLinkLost(BluetoothGatt.GATT_FAILURE)
            return
        }
        gatt = g
    }

    private fun onConnected(g: BluetoothGatt) {
        _state.value = ConnectionState.Discovering
        discoveryStarted = false
        // Ask for the largest MTU before anything else: fewer packets per
        // read/write/notify. Newer Android releases may negotiate 517 whatever we
        // ask for, so only the value reported to onMtuChanged is trusted.
        if (g.requestMtu(desiredMtu)) {
            mainHandler.postDelayed(mtuFallback, MTU_TIMEOUT_MS)
        } else {
            startDiscovery(g)
        }
    }

    private fun startDiscovery(g: BluetoothGatt) {
        mainHandler.removeCallbacks(mtuFallback)
        if (discoveryStarted) return // a late or peer-initiated MTU change
        discoveryStarted = true
        if (!g.discoverServices()) g.disconnect() // surfaces as a link loss -> retry
    }

    private fun onReady(g: BluetoothGatt) {
        _services.value = g.services.map { service ->
            GattServiceInfo(
                uuid = service.uuid,
                characteristics = service.characteristics.map { it.toInfo() },
            )
        }
        val reconnected = _reconnectAttempt.value > 0
        _reconnectAttempt.value = 0
        _state.value = ConnectionState.Ready
        if (reconnected) _events.tryEmit(GattEvent.Info("Reconnected"))

        if (activeNotifications.isNotEmpty()) {
            _events.tryEmit(
                GattEvent.Info("Re-arming ${activeNotifications.size} notification subscription(s)"),
            )
            activeNotifications.toList().forEach { (svc, ch) -> enqueueNotify(svc, ch, true) }
        }
    }

    /** Handles both failed connection attempts and dropped links. */
    private fun onLinkLost(status: Int) {
        mainHandler.removeCallbacks(mtuFallback)
        clearQueue()
        // A dead client must be closed, or Android leaks it and later connects fail (status 133).
        gatt?.let { runCatching { it.close() } }
        gatt = null
        resetLinkState()

        if (targetAddress == null || !autoReconnect) {
            _state.value =
                if (status == BluetoothGatt.GATT_SUCCESS) ConnectionState.Disconnected else ConnectionState.Failed
            return
        }

        val attempt = _reconnectAttempt.value + 1
        if (attempt > maxReconnectAttempts) {
            _events.tryEmit(
                GattEvent.Error("Gave up after $maxReconnectAttempts reconnect attempts (last status $status)"),
            )
            targetAddress = null
            _reconnectAttempt.value = 0
            _state.value = ConnectionState.Failed
            return
        }

        val delayMs = min(BASE_BACKOFF_MS shl (attempt - 1), MAX_BACKOFF_MS)
        _reconnectAttempt.value = attempt
        _state.value = ConnectionState.Reconnecting
        _events.tryEmit(
            GattEvent.Info("Disconnected (status $status) — retry $attempt/$maxReconnectAttempts in ${delayMs} ms"),
        )
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun resetLinkState() {
        _services.value = emptyList()
        _mtu.value = DEFAULT_ATT_MTU
        discoveryStarted = false
    }

    private fun remoteDevice(address: String): BluetoothDevice? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return null
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    }

    /**
     * Re-posts a callback to the main thread, dropping it if it came from a GATT
     * client that has since been closed or replaced.
     */
    private fun onMain(source: BluetoothGatt, block: () -> Unit) {
        mainHandler.post {
            if (source === gatt) block() else runCatching { source.close() }
        }
    }

    // --- GATT callback -------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            onMain(gatt) {
                when {
                    newState == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS -> onConnected(gatt)
                    newState == BluetoothProfile.STATE_DISCONNECTED ||
                        status != BluetoothGatt.GATT_SUCCESS -> onLinkLost(status)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            onMain(gatt) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _mtu.value = mtu
                    _events.tryEmit(
                        GattEvent.Info("MTU $mtu — up to ${mtu - ATT_HEADER_BYTES} B per write/notify"),
                    )
                } else {
                    _events.tryEmit(
                        GattEvent.Info("MTU request rejected (status $status); staying at ${_mtu.value}"),
                    )
                }
                startDiscovery(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            onMain(gatt) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onReady(gatt)
                } else {
                    _events.tryEmit(GattEvent.Error("Service discovery failed (status $status)"))
                    gatt.disconnect() // surfaces as a link loss -> retry
                }
            }
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
                _events.tryEmit(GattEvent.Error("Updating notifications failed ($status)"))
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
        synchronized(opQueue) { opQueue.addLast(op) }
        maybeRunNext()
    }

    private fun maybeRunNext() {
        synchronized(opQueue) {
            if (opInFlight) return
            val op = opQueue.removeFirstOrNull() ?: return
            opInFlight = true
            val generation = queueGeneration
            // Skip ops that were already posted when their connection was torn down.
            mainHandler.post { if (generation == queueGeneration) op() }
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
            queueGeneration++
        }
    }

    /** Report an op that never got off the ground, and free the queue. */
    private fun failOperation(message: String) {
        _events.tryEmit(GattEvent.Error(message))
        operationComplete()
    }

    /** Looks a characteristic up on the *current* link; fails the op if it's gone. */
    private fun resolve(serviceUuid: UUID, charUuid: UUID): Pair<BluetoothGatt, BluetoothGattCharacteristic>? {
        val g = gatt ?: run {
            failOperation("Not connected")
            return null
        }
        val ch = g.getService(serviceUuid)?.getCharacteristic(charUuid) ?: run {
            failOperation("Characteristic $charUuid not found")
            return null
        }
        return g to ch
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

        /** ATT MTU before any negotiation (Bluetooth Core spec). */
        const val DEFAULT_ATT_MTU = 23

        /** Largest ATT MTU Android will negotiate. */
        const val MAX_ATT_MTU = 517

        /** ATT opcode + handle overhead per packet: payload = MTU - 3. */
        const val ATT_HEADER_BYTES = 3

        private const val MTU_TIMEOUT_MS = 3_000L
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
