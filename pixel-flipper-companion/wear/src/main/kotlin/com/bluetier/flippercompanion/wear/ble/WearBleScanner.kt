package com.bluetier.flippercompanion.wear.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Minimal BLE scanner for the watch — Wear OS does expose BLE APIs to apps. */
class WearBleScanner(private val context: Context) {

    data class Device(val address: String, val name: String?, val rssi: Int)

    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val scanner by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = Device(
                address = result.device.address,
                name = result.scanRecord?.deviceName,
                rssi = result.rssi,
            )
            _devices.value = _devices.value + (d.address to d)
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    fun start(): Boolean {
        val scanner = scanner ?: return false
        if (!hasScanPermission() || _scanning.value) return _scanning.value
        _devices.value = emptyMap()
        @SuppressLint("MissingPermission")
        val ok = runCatching { scanner.startScan(callback) }.isSuccess
        _scanning.value = ok
        return ok
    }

    fun stop() {
        if (!_scanning.value) return
        if (hasScanPermission()) {
            @SuppressLint("MissingPermission")
            runCatching { scanner?.stopScan(callback) }
        }
        _scanning.value = false
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
}
