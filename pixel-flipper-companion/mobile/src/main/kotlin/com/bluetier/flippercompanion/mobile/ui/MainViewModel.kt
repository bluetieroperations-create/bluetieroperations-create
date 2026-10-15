package com.bluetier.flippercompanion.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bluetier.flippercompanion.mobile.ble.BleConnection
import com.bluetier.flippercompanion.mobile.ble.BondState
import com.bluetier.flippercompanion.mobile.ble.BleDeviceInfo
import com.bluetier.flippercompanion.mobile.ble.BleScanner
import com.bluetier.flippercompanion.mobile.ble.ConnectionState
import com.bluetier.flippercompanion.mobile.ble.GattEvent
import com.bluetier.flippercompanion.mobile.ble.GattServiceInfo
import com.bluetier.flippercompanion.mobile.ble.Hex
import com.bluetier.flippercompanion.mobile.nfc.NfcTagInfo
import com.bluetier.flippercompanion.mobile.wear.WearBridge
import com.bluetier.flippercompanion.shared.WearContract
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Identifies the characteristic the write controls currently target. */
data class SelectedCharacteristic(val serviceUuid: UUID, val charUuid: UUID)

/** Drives the phone UI and reacts to actions triggered from the watch. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val bleScanner = BleScanner(app)
    private val connection = BleConnection(app)

    val scanning: StateFlow<Boolean> = bleScanner.scanning

    /** Discovered BLE devices, strongest signal first. */
    val devices: StateFlow<List<BleDeviceInfo>> =
        bleScanner.devices
            .map { map -> map.values.sortedByDescending { it.rssi } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _nfcTag = MutableStateFlow<NfcTagInfo?>(null)
    val nfcTag: StateFlow<NfcTagInfo?> = _nfcTag.asStateFlow()

    private val _lastWatchAction = MutableStateFlow<WearContract.Action?>(null)
    val lastWatchAction: StateFlow<WearContract.Action?> = _lastWatchAction.asStateFlow()

    // --- GATT ----------------------------------------------------------------

    /** Address of the device we are connected to / connecting to, or null. */
    private val _connectedAddress = MutableStateFlow<String?>(null)
    val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = connection.state
    val gattServices: StateFlow<List<GattServiceInfo>> = connection.services

    /** Negotiated ATT MTU (23 until negotiation completes). */
    val mtu: StateFlow<Int> = connection.mtu

    /** 0 unless an automatic reconnect is in progress. */
    val reconnectAttempt: StateFlow<Int> = connection.reconnectAttempt
    val maxReconnectAttempts: Int get() = connection.maxReconnectAttempts

    /** Pairing/bond state of the connected device. */
    val bondState: StateFlow<BondState> = connection.bondState

    private val _selected = MutableStateFlow<SelectedCharacteristic?>(null)
    val selected: StateFlow<SelectedCharacteristic?> = _selected.asStateFlow()

    /** Rolling human-readable log of GATT reads/writes/notifications. */
    private val _gattLog = MutableStateFlow<List<String>>(emptyList())
    val gattLog: StateFlow<List<String>> = _gattLog.asStateFlow()

    init {
        // React to triggers pushed from the watch.
        viewModelScope.launch {
            WearBridge.actions.collect { action ->
                _lastWatchAction.value = action
                when (action) {
                    WearContract.Action.BLE_SCAN -> startBleScan()
                    WearContract.Action.NFC_READ,
                    WearContract.Action.NFC_EMULATE -> Unit
                }
            }
        }
        // Turn GATT events into log lines.
        viewModelScope.launch {
            connection.events.collect { event -> log(event.toLogLine()) }
        }
    }

    // BLE scan
    fun startBleScan(): Boolean = bleScanner.start()
    fun stopBleScan() = bleScanner.stop()

    // GATT lifecycle
    fun connect(address: String) {
        bleScanner.stop()
        _gattLog.value = emptyList()
        _selected.value = null
        _connectedAddress.value = address
        if (!connection.connect(address)) {
            log("Connect failed: Bluetooth is off or the address is invalid")
            _connectedAddress.value = null
        }
    }

    fun disconnect() {
        connection.disconnect()
        _connectedAddress.value = null
        _selected.value = null
    }

    /** Begin pairing with the connected device (Android shows the system dialog). */
    fun pair() {
        connection.createBond()
    }

    fun select(serviceUuid: UUID, charUuid: UUID) {
        _selected.value = SelectedCharacteristic(serviceUuid, charUuid)
    }

    fun read(serviceUuid: UUID, charUuid: UUID) =
        connection.readCharacteristic(serviceUuid, charUuid)

    fun toggleNotify(serviceUuid: UUID, charUuid: UUID, enable: Boolean) =
        connection.setNotifications(serviceUuid, charUuid, enable)

    /** @return false if [hexInput] is not valid hex. */
    fun write(serviceUuid: UUID, charUuid: UUID, hexInput: String, withResponse: Boolean): Boolean {
        val bytes = Hex.decode(hexInput) ?: run {
            log("Write ignored: '$hexInput' is not valid hex")
            return false
        }
        connection.writeCharacteristic(serviceUuid, charUuid, bytes, withResponse)
        return true
    }

    fun onNfcTag(info: NfcTagInfo) {
        _nfcTag.value = info
    }

    private fun log(line: String) {
        _gattLog.value = (_gattLog.value + line).takeLast(50)
    }

    private fun GattEvent.toLogLine(): String = when (this) {
        is GattEvent.Read -> "READ ${uuid.short()} = ${Hex.encode(value)}  \"${Hex.asText(value)}\""
        is GattEvent.Written -> "WRITE ${uuid.short()} ${if (success) "ok" else "failed"}"
        is GattEvent.Notification -> "NOTIFY ${uuid.short()} = ${Hex.encode(value)}"
        is GattEvent.Info -> "INFO $message"
        is GattEvent.Error -> "ERROR $message"
    }

    private fun UUID.short(): String = toString().take(8)

    override fun onCleared() {
        bleScanner.stop()
        connection.disconnect()
        super.onCleared()
    }
}
