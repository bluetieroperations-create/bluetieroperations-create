package com.bluetier.flippercompanion.mobile.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An aggregated view of one BLE advertiser, accumulated across every sighting in
 * the current scan (keyed by MAC address). Carries enough for the finder
 * ([smoothedRssi]) and the tracker sweep ([companyIds], [serviceUuids16],
 * [appleFindMy], [count], first/last-seen).
 */
data class BleDeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val smoothedRssi: Double,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val count: Int,
    val minRssi: Int,
    val maxRssi: Int,
    /** Bluetooth SIG company IDs present in the manufacturer-specific data. */
    val companyIds: Set<Int> = emptySet(),
    /** Advertised service UUIDs, as upper-case 16-bit shorts where applicable (else full). */
    val serviceUuids16: Set<String> = emptySet(),
    /** True when an Apple (0x004C) payload carries the Find My offline-finding type (0x12). */
    val appleFindMy: Boolean = false,
    /** Whether the advertiser accepts connections (false for most beacons/trackers). */
    val connectable: Boolean = true,
    /** Calibrated TX power at 1 m if advertised, else null. */
    val txPower: Int? = null,
)

/**
 * Thin wrapper around [android.bluetooth.le.BluetoothLeScanner].
 *
 * Call [start] after BLUETOOTH_SCAN / BLUETOOTH_CONNECT have been granted; collect
 * [devices] for the live result set. Each result updates the matching
 * [BleDeviceInfo] in place: RSSI is smoothed with an EMA, and sighting count and
 * first/last-seen accumulate for the lifetime of the scan.
 */
class BleScanner(private val context: Context) {

    private val _devices = MutableStateFlow<Map<String, BleDeviceInfo>>(emptyMap())
    val devices: StateFlow<Map<String, BleDeviceInfo>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val adapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            record(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::record)
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    private fun record(result: ScanResult) {
        val device = result.device ?: return
        val scanRecord = result.scanRecord
        val now = System.currentTimeMillis()
        val rssi = result.rssi
        val prev = _devices.value[device.address]

        val name = if (hasConnectPermission()) {
            @SuppressLint("MissingPermission")
            val n = device.name
            n ?: scanRecord?.deviceName
        } else {
            scanRecord?.deviceName
        }

        val companyIds = scanRecord?.companyIds() ?: emptySet()
        val info = BleDeviceInfo(
            address = device.address,
            name = name ?: prev?.name,
            rssi = rssi,
            smoothedRssi = if (prev == null) rssi.toDouble() else ema(prev.smoothedRssi, rssi),
            firstSeenMillis = prev?.firstSeenMillis ?: now,
            lastSeenMillis = now,
            count = (prev?.count ?: 0) + 1,
            minRssi = minOf(prev?.minRssi ?: rssi, rssi),
            maxRssi = maxOf(prev?.maxRssi ?: rssi, rssi),
            companyIds = companyIds,
            serviceUuids16 = scanRecord?.serviceUuidShorts() ?: emptySet(),
            appleFindMy = scanRecord?.isAppleFindMy() ?: false,
            connectable = result.isConnectable,
            txPower = scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
        )
        _devices.value = _devices.value + (info.address to info)
    }

    /** @return false if BLE is unavailable or the SCAN permission is missing. */
    fun start(): Boolean {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        if (!hasScanPermission()) return false
        if (_scanning.value) return true

        _devices.value = emptyMap()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        @SuppressLint("MissingPermission")
        val ok = runCatching { scanner.startScan(null, settings, callback) }.isSuccess
        _scanning.value = ok
        return ok
    }

    fun stop() {
        if (!_scanning.value) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner != null && hasScanPermission()) {
            @SuppressLint("MissingPermission")
            runCatching { scanner.stopScan(callback) }
        }
        _scanning.value = false
    }

    /** Drops accumulated sightings without stopping the scan (used to begin a fresh sweep). */
    fun clearSightings() {
        _devices.value = emptyMap()
    }

    /** BLUETOOTH_SCAN only exists from API 31; before that, scans need fine location. */
    private fun hasScanPermission(): Boolean = hasPermission(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        },
    )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Before API 31 the install-time BLUETOOTH permission covers this. */
    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun ema(previous: Double, sample: Int): Double =
        previous * (1 - EMA_ALPHA) + sample * EMA_ALPHA

    private fun ScanRecord.companyIds(): Set<Int> {
        val data = manufacturerSpecificData ?: return emptySet()
        return buildSet { for (i in 0 until data.size()) add(data.keyAt(i)) }
    }

    private fun ScanRecord.isAppleFindMy(): Boolean {
        val payload = manufacturerSpecificData?.get(APPLE_COMPANY_ID) ?: return false
        return payload.isNotEmpty() && payload[0] == APPLE_FIND_MY_TYPE
    }

    private fun ScanRecord.serviceUuidShorts(): Set<String> =
        serviceUuids?.mapTo(mutableSetOf()) { shortUuid(it.uuid.toString()) } ?: emptySet()

    companion object {
        private const val EMA_ALPHA = 0.35
        private const val APPLE_COMPANY_ID = 0x004C
        private const val APPLE_FIND_MY_TYPE = 0x12.toByte()

        /** "0000fd5a-0000-1000-8000-00805f9b34fb" -> "FD5A"; otherwise the full UUID upper-cased. */
        fun shortUuid(uuid: String): String {
            val lower = uuid.lowercase()
            return if (lower.startsWith("0000") && lower.endsWith("-0000-1000-8000-00805f9b34fb")) {
                lower.substring(4, 8).uppercase()
            } else {
                uuid.uppercase()
            }
        }
    }
}
