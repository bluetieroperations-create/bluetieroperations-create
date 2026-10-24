package com.bluetier.flippercompanion.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bluetier.flippercompanion.mobile.ble.BleConnection
import com.bluetier.flippercompanion.mobile.ble.BondState
import com.bluetier.flippercompanion.mobile.ble.BleDeviceInfo
import com.bluetier.flippercompanion.mobile.ble.CharRef
import com.bluetier.flippercompanion.mobile.ble.BleScanner
import com.bluetier.flippercompanion.mobile.ble.ConnectionState
import com.bluetier.flippercompanion.mobile.ble.GattEvent
import com.bluetier.flippercompanion.mobile.ble.GattServiceInfo
import com.bluetier.flippercompanion.mobile.ble.Hex
import com.bluetier.flippercompanion.mobile.ble.Proximity
import com.bluetier.flippercompanion.mobile.ble.ProximityBand
import com.bluetier.flippercompanion.mobile.ble.SweepAnalysis
import com.bluetier.flippercompanion.mobile.ble.SweepEntry
import com.bluetier.flippercompanion.mobile.ble.Trend
import com.bluetier.flippercompanion.mobile.ble.TrendTracker
import com.bluetier.flippercompanion.mobile.nfc.NfcTagInfo
import com.bluetier.flippercompanion.mobile.probe.CapabilityProbe
import com.bluetier.flippercompanion.mobile.probe.PointingCapabilities
import com.bluetier.flippercompanion.mobile.snap.SnapTraceStore
import com.bluetier.flippercompanion.mobile.obd.ObdController
import com.bluetier.flippercompanion.mobile.obd.ObdLine
import com.bluetier.flippercompanion.mobile.obd.UartChannel
import com.bluetier.flippercompanion.mobile.wear.WearBridge
import com.bluetier.flippercompanion.shared.WearContract
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which scan-screen view is showing when no device is connected. */
enum class ScanMode { LIST, FINDER, SWEEP, PROBE }

/** A finder target unseen for this long is treated as out of range. */
private const val STALE_AFTER_MILLIS = 5_000L

/** A live reading for the hot/cold finder's selected target. */
data class FinderReading(
    val device: BleDeviceInfo,
    val band: ProximityBand,
    val meters: Double,
    val trend: Trend,
    /** True when the target hasn't been seen for a few seconds (likely out of range). */
    val stale: Boolean,
)

/** Drives the phone UI and reacts to actions triggered from the watch. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val bleScanner = BleScanner(app)
    private val connection = BleConnection(app)

    // Pointing & snap probe. The capability read is fixed hardware, so it is
    // done once and cached; the trace counters are live from the store the
    // watch's messages land in.
    private val _pointingCapabilities = MutableStateFlow<PointingCapabilities?>(null)
    val pointingCapabilities: StateFlow<PointingCapabilities?> = _pointingCapabilities.asStateFlow()

    val snapTraceCount: StateFlow<Int> = SnapTraceStore.count
    val snapTraceAccepted: StateFlow<Int> = SnapTraceStore.acceptedCount
    val snapTraceBytes: StateFlow<Long> = SnapTraceStore.fileBytes
    val lastSnapTrace = SnapTraceStore.lastTrace

    private val _snapTracePath = MutableStateFlow("")
    val snapTracePath: StateFlow<String> = _snapTracePath.asStateFlow()
    private val obd = ObdController(connection, viewModelScope)

    val scanning: StateFlow<Boolean> = bleScanner.scanning

    /** Discovered BLE devices, strongest signal first. */
    val devices: StateFlow<List<BleDeviceInfo>> =
        bleScanner.devices
            .map { map -> map.values.sortedByDescending { it.rssi } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _nfcTag = MutableStateFlow<NfcTagInfo?>(null)
    val nfcTag: StateFlow<NfcTagInfo?> = _nfcTag.asStateFlow()

    /** True while an NDEF write is armed, waiting for the next tag tap. */
    private val _nfcArmed = MutableStateFlow(false)
    val nfcArmed: StateFlow<Boolean> = _nfcArmed.asStateFlow()

    /** Human-readable outcome of the last NDEF write, or null. */
    private val _nfcWriteStatus = MutableStateFlow<String?>(null)
    val nfcWriteStatus: StateFlow<String?> = _nfcWriteStatus.asStateFlow()

    fun setNfcArmed(armed: Boolean) {
        _nfcArmed.value = armed
    }

    fun onNfcWriteStatus(status: String) {
        _nfcWriteStatus.value = status
    }

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

    /** The characteristic the write controls currently target. */
    private val _selected = MutableStateFlow<CharRef?>(null)
    val selected: StateFlow<CharRef?> = _selected.asStateFlow()

    /** Rolling human-readable log of GATT reads/writes/notifications. */
    private val _gattLog = MutableStateFlow<List<String>>(emptyList())
    val gattLog: StateFlow<List<String>> = _gattLog.asStateFlow()

    // --- OBD terminal --------------------------------------------------------

    /** True while the OBD terminal is shown (connection stays live underneath). */
    private val _obdActive = MutableStateFlow(false)
    val obdActive: StateFlow<Boolean> = _obdActive.asStateFlow()

    val obdLines: StateFlow<List<ObdLine>> = obd.lines
    val obdChannel: StateFlow<UartChannel?> = obd.channel

    // --- Finder / tracker sweep ---------------------------------------------

    private val _scanMode = MutableStateFlow(ScanMode.LIST)
    val scanMode: StateFlow<ScanMode> = _scanMode.asStateFlow()

    /** Address of the device the hot/cold finder is locked onto, or null. */
    private val _finderTarget = MutableStateFlow<String?>(null)
    val finderTarget: StateFlow<String?> = _finderTarget.asStateFlow()

    private val trendTracker = TrendTracker()

    private val _finderReading = MutableStateFlow<FinderReading?>(null)
    val finderReading: StateFlow<FinderReading?> = _finderReading.asStateFlow()

    private val _sweepEntries = MutableStateFlow<List<SweepEntry>>(emptyList())
    val sweepEntries: StateFlow<List<SweepEntry>> = _sweepEntries.asStateFlow()

    private var sweepStartMillis = 0L
    private val _sweepElapsedMillis = MutableStateFlow(0L)
    val sweepElapsedMillis: StateFlow<Long> = _sweepElapsedMillis.asStateFlow()

    /** 1 Hz tick so durations and staleness advance even when nothing is advertising. */
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    init {
        // React to triggers pushed from the watch.
        viewModelScope.launch {
            WearBridge.actions.collect { action ->
                _lastWatchAction.value = action
                when (action) {
                    WearContract.Action.BLE_SCAN -> startBleScan()
                    WearContract.Action.NFC_READ,
                    WearContract.Action.NFC_EMULATE -> Unit
                    // The capture session runs on the watch; the phone opens the
                    // probe screen so the trace counter is already up while you
                    // stand there snapping.
                    WearContract.Action.SNAP_LAB_START -> enterProbe()
                    WearContract.Action.SNAP_LAB_STOP -> refreshSnapTraces()
                }
            }
        }
        // Turn GATT events into log lines.
        viewModelScope.launch {
            connection.events.collect { event -> log(event.toLogLine()) }
        }
        // Hot/cold finder: recompute the locked target's reading on each sighting
        // and on each tick (so staleness advances even when it goes quiet).
        viewModelScope.launch {
            combine(bleScanner.devices, _finderTarget, ticker) { map, target, _ ->
                target?.let { map[it] }
            }.collect { obs ->
                _finderReading.value = obs?.let {
                    FinderReading(
                        device = it,
                        band = Proximity.band(it.smoothedRssi),
                        meters = Proximity.estimateMeters(it.smoothedRssi, it.txPower ?: Proximity.DEFAULT_TX_POWER),
                        trend = trendTracker.update(it.lastSeenMillis, it.smoothedRssi),
                        stale = System.currentTimeMillis() - it.lastSeenMillis > STALE_AFTER_MILLIS,
                    )
                }
            }
        }
        // Tracker sweep: re-rank on each sighting and each tick while sweeping.
        viewModelScope.launch {
            combine(bleScanner.devices, ticker) { map, _ -> map }.collect { map ->
                if (_scanMode.value == ScanMode.SWEEP) {
                    val now = System.currentTimeMillis()
                    _sweepEntries.value = SweepAnalysis.analyze(map.values, now)
                    _sweepElapsedMillis.value = now - sweepStartMillis
                }
            }
        }
    }

    // BLE scan
    fun startBleScan(): Boolean = bleScanner.start()
    fun stopBleScan() = bleScanner.stop()

    // Finder / sweep
    /** Enter the hot/cold finder (caller must have ensured scan permission). */
    fun enterFinder() {
        trendTracker.reset()
        _finderTarget.value = null
        _finderReading.value = null
        _scanMode.value = ScanMode.FINDER
        bleScanner.start()
    }

    fun selectFinderTarget(address: String?) {
        trendTracker.reset()
        _finderReading.value = null
        _finderTarget.value = address
    }

    /** Enter (or restart) the tracker sweep with a fresh sighting window. */
    fun enterSweep() {
        bleScanner.clearSightings()
        _sweepEntries.value = emptyList()
        sweepStartMillis = System.currentTimeMillis()
        _sweepElapsedMillis.value = 0L
        _scanMode.value = ScanMode.SWEEP
        bleScanner.start()
    }

    /**
     * Enter the pointing & snap probe. Reads the phone's hardware once and
     * recounts whatever traces are already on disk.
     */
    fun enterProbe() {
        val app = getApplication<Application>()
        if (_pointingCapabilities.value == null) {
            _pointingCapabilities.value = CapabilityProbe.probe(app)
        }
        _snapTracePath.value = SnapTraceStore.file(app).absolutePath
        SnapTraceStore.refresh(app)
        _scanMode.value = ScanMode.PROBE
    }

    fun refreshSnapTraces() {
        val app = getApplication<Application>()
        _snapTracePath.value = SnapTraceStore.file(app).absolutePath
        SnapTraceStore.refresh(app)
    }

    fun clearSnapTraces() = SnapTraceStore.clear(getApplication<Application>())

    /** Back to the plain scan list; keeps scanning. */
    fun exitScanMode() {
        _scanMode.value = ScanMode.LIST
        _finderTarget.value = null
        _finderReading.value = null
    }

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
        obd.stop()
        _obdActive.value = false
        connection.disconnect()
        _connectedAddress.value = null
        _selected.value = null
    }

    // OBD terminal
    /** Switch the connected device into OBD-terminal mode. @return false if no UART found. */
    fun enterObd(): Boolean {
        if (!obd.configure(gattServices.value)) return false
        obd.start()
        _obdActive.value = true
        return true
    }

    /** Leave OBD mode back to the GATT browser; the connection stays open. */
    fun exitObd() {
        obd.stop()
        _obdActive.value = false
    }

    fun obdSend(command: String) = obd.send(command)
    fun obdInit() = obd.sendInit()
    fun obdClear() = obd.clear()

    /** Begin pairing with the connected device (Android shows the system dialog). */
    fun pair() {
        connection.createBond()
    }

    fun select(ref: CharRef) {
        _selected.value = ref
    }

    fun read(ref: CharRef) = connection.readCharacteristic(ref)

    fun toggleNotify(ref: CharRef, enable: Boolean) = connection.setNotifications(ref, enable)

    /** @return false if [hexInput] is not valid hex. */
    fun write(ref: CharRef, hexInput: String, withResponse: Boolean): Boolean {
        val bytes = Hex.decode(hexInput) ?: run {
            log("Write ignored: '$hexInput' is not valid hex")
            return false
        }
        connection.writeCharacteristic(ref, bytes, withResponse)
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
        obd.stop()
        connection.disconnect()
        super.onCleared()
    }
}
