package com.bluetier.flippercompanion.wear.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
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

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
}
