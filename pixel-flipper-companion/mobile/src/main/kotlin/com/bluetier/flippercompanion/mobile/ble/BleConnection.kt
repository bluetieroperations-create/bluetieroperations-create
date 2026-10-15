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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
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

/** Pairing/bond state of the remote device, mirroring BluetoothDevice.BOND_*. */
enum class BondState { None, Bonding, Bonded }

/**
 * Identifies one characteristic instance on the peer. UUIDs alone are not unique:
 * GATT allows several services/characteristics with the same UUID (e.g. an HID
 * keyboard's multiple 0x2A4D Report characteristics), so the attribute-handle
 * based instance ids are part of the identity. Handles are stable for a given
 * peer firmware, so a ref stays valid across reconnects.
 */
data class CharRef(
    val serviceUuid: UUID,
    val serviceInstance: Int,
    val charUuid: UUID,
    val charInstance: Int,
)

/** A characteristic flattened with the capabilities the UI cares about. */
data class GattCharacteristicInfo(
    val ref: CharRef,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canWriteNoResponse: Boolean,
    val canNotify: Boolean,
) {
    val uuid: UUID get() = ref.charUuid
}

data class GattServiceInfo(
    val uuid: UUID,
    val instanceId: Int,
    val characteristics: List<GattCharacteristicInfo>,
)

/**
 * Delay before reconnect attempt [attempt] (1-based): 1s, 2s, 4s… capped at 30s.
 * Top-level so it can be unit-tested without Android.
 */
internal fun reconnectDelayMs(attempt: Int): Long {
    require(attempt >= 1) { "attempt is 1-based" }
    val shift = (attempt - 1).coerceAtMost(30) // keep the shift from overflowing
    return min(BleConnection.BASE_BACKOFF_MS shl shift, BleConnection.MAX_BACKOFF_MS)
}

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
 * resolve their characteristic by [CharRef] when they run, so they stay valid
 * across a reconnect.
 *
 * Requires BLUETOOTH_CONNECT (API 31+); the caller must hold it before [connect].
 */
@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    private val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    private val desiredMtu: Int = MAX_ATT_MTU,
    /** When true, [connect] initiates bonding right away if the device isn't bonded yet. */
    private val autoBond: Boolean = false,
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

    private val _bondState = MutableStateFlow(BondState.None)

    /** Current pairing/bond state of the connected (or connecting) device. */
    val bondState: StateFlow<BondState> = _bondState.asStateFlow()

    // Connection bookkeeping. Confined to the main thread: public calls come from
    // the UI, and GATT callbacks that touch it are re-posted via [onMain].
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null

    /** The device the user wants to be connected to; null after an explicit [disconnect]. */
    private var targetAddress: String? = null
    private var device: BluetoothDevice? = null
    private var discoveryStarted = false

    /** True while [bondReceiver] is registered, so we unregister exactly once. */
    private var bondReceiverRegistered = false

    /** Subscriptions to re-arm after a reconnect. */
    private val activeNotifications = LinkedHashSet<CharRef>()

    /** [autoBond] fires at most once per [connect], not on every reconnect. */
    private var autoBondTried = false

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
        val dev = remoteDevice(address) ?: return false
        disconnect()
        targetAddress = address
        device = dev
        _bondState.value = dev.bondState.toBondState()
        registerBondReceiver()
        autoBondTried = false
        openGatt(address)
        return true
    }

    /**
     * Initiates pairing with the connected device. Returns false if there is no
     * device, it is already bonded or bonding, or the platform rejected the
     * request. Progress is reported through [bondState]; Android shows the system
     * pairing dialog when the peer requires confirmation or a passkey.
     */
    fun createBond(): Boolean {
        val dev = device ?: return false
        return when (dev.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                _events.tryEmit(GattEvent.Info("Already paired"))
                false
            }
            BluetoothDevice.BOND_BONDING -> false
            else -> {
                val started = runCatching { dev.createBond() }.getOrDefault(false)
                if (!started) _events.tryEmit(GattEvent.Error("Could not start pairing"))
                started
            }
        }
    }

    /**
     * Tears the link down and cancels any pending reconnect. Does not remove an
     * existing bond — a bond is a system-level pairing the user manages in
     * Settings, and it persists across connections by design.
     */
    fun disconnect() {
        targetAddress = null
        device = null
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(mtuFallback)
        unregisterBondReceiver()
        clearQueue()
        activeNotifications.clear()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        resetLinkState()
        _reconnectAttempt.value = 0
        _bondState.value = BondState.None
        _state.value = ConnectionState.Disconnected
    }

    fun readCharacteristic(ref: CharRef) = enqueue {
        val (g, ch) = resolve(ref) ?: return@enqueue
        if (!g.readCharacteristic(ch)) failOperation("Read could not be started")
    }

    fun writeCharacteristic(ref: CharRef, value: ByteArray, withResponse: Boolean) {
        if (value.size > MAX_ATTR_VALUE_LEN) {
            // ATT caps an attribute value at 512 bytes, and BluetoothGatt throws
            // IllegalArgumentException above that (which would crash the app).
            _events.tryEmit(
                GattEvent.Error("${value.size} B exceeds the $MAX_ATTR_VALUE_LEN B GATT attribute limit"),
            )
            return
        }
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
            val (g, ch) = resolve(ref) ?: return@enqueue
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

    fun setNotifications(ref: CharRef, enable: Boolean) {
        if (enable) activeNotifications += ref else activeNotifications -= ref
        enqueueNotify(ref, enable)
    }

    private fun enqueueNotify(ref: CharRef, enable: Boolean) = enqueue {
        val (g, ch) = resolve(ref) ?: return@enqueue
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
        // Pair only once the LE link is up: calling createBond() before connectGatt()
        // races two connection attempts, and on dual-mode peers may bond over
        // classic Bluetooth instead of LE.
        if (autoBond && !autoBondTried && _bondState.value == BondState.None) {
            autoBondTried = true
            createBond()
        }
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
                instanceId = service.instanceId,
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
            activeNotifications.toList().forEach { enqueueNotify(it, true) }
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

        val delayMs = reconnectDelayMs(attempt)
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

    // --- Bonding -------------------------------------------------------------

    /**
     * Receives ACTION_BOND_STATE_CHANGED for the target device and mirrors it
     * into [bondState]. Registered only while a connection is wanted.
     *
     * Must be EXPORTED: the broadcast is sent by the Bluetooth app (shared UID
     * android.uid.bluetooth), not the system server, and Android only delivers to
     * a NOT_EXPORTED receiver from root/system or the app itself. Exporting is
     * safe because BOND_STATE_CHANGED is a protected broadcast (declared in the
     * framework manifest), so no other app can spoof it.
     */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val changed = intent.bluetoothDevice() ?: return
            if (changed.address != targetAddress) return

            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val previous = intent.getIntExtra(
                BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                BluetoothDevice.ERROR,
            )
            mainHandler.post {
                if (changed.address != targetAddress) return@post
                _bondState.value = state.toBondState()
                when (state) {
                    BluetoothDevice.BOND_BONDED -> _events.tryEmit(GattEvent.Info("Paired"))
                    BluetoothDevice.BOND_NONE ->
                        if (previous == BluetoothDevice.BOND_BONDING) {
                            _events.tryEmit(GattEvent.Error("Pairing failed or was rejected"))
                        }
                    BluetoothDevice.BOND_BONDING -> _events.tryEmit(GattEvent.Info("Pairing…"))
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        runCatching { context.unregisterReceiver(bondReceiver) }
        bondReceiverRegistered = false
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun Int.toBondState(): BondState = when (this) {
        BluetoothDevice.BOND_BONDED -> BondState.Bonded
        BluetoothDevice.BOND_BONDING -> BondState.Bonding
        else -> BondState.None
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
    private fun resolve(ref: CharRef): Pair<BluetoothGatt, BluetoothGattCharacteristic>? {
        val g = gatt ?: run {
            failOperation("Not connected")
            return null
        }
        // Match on instance id as well as UUID: getService()/getCharacteristic()
        // return the first UUID match, which is the wrong one for duplicates.
        val ch = g.services
            .firstOrNull { it.uuid == ref.serviceUuid && it.instanceId == ref.serviceInstance }
            ?.characteristics
            ?.firstOrNull { it.uuid == ref.charUuid && it.instanceId == ref.charInstance }
            ?: run {
                failOperation("Characteristic ${ref.charUuid} not found")
                return null
            }
        return g to ch
    }

    private fun BluetoothGattCharacteristic.toInfo() = GattCharacteristicInfo(
        ref = CharRef(service.uuid, service.instanceId, uuid, instanceId),
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

        /** Max length of a GATT attribute value (Bluetooth Core spec; BluetoothGatt enforces it). */
        const val MAX_ATTR_VALUE_LEN = 512

        private const val MTU_TIMEOUT_MS = 3_000L
        internal const val BASE_BACKOFF_MS = 1_000L
        internal const val MAX_BACKOFF_MS = 30_000L
    }
}
