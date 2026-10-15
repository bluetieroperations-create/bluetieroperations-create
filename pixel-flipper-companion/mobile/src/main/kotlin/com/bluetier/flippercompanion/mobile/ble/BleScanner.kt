package com.bluetier.flippercompanion.mobile.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single BLE advertisement, de-duplicated by MAC address. */
data class BleDeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenMillis: Long,
)

/**
 * Thin wrapper around [android.bluetooth.le.BluetoothLeScanner].
 *
 * This is the one Flipper-style capability the Pixel 11 can do with zero extra
 * hardware. Call [start] after BLUETOOTH_SCAN / BLUETOOTH_CONNECT have been
 * granted; collect [devices] for the live, RSSI-sorted result set.
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
        val name = if (hasConnectPermission()) {
            @SuppressLint("MissingPermission")
            val n = device.name
            n
        } else {
            result.scanRecord?.deviceName
        }
        val info = BleDeviceInfo(
            address = device.address,
            name = name,
            rssi = result.rssi,
            lastSeenMillis = System.currentTimeMillis(),
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

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
