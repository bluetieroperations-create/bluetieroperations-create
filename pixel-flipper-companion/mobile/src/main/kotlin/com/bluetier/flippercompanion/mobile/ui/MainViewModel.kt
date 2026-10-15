package com.bluetier.flippercompanion.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bluetier.flippercompanion.mobile.ble.BleDeviceInfo
import com.bluetier.flippercompanion.mobile.ble.BleScanner
import com.bluetier.flippercompanion.mobile.nfc.NfcTagInfo
import com.bluetier.flippercompanion.mobile.wear.WearBridge
import com.bluetier.flippercompanion.shared.WearContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Drives the phone UI and reacts to actions triggered from the watch. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val bleScanner = BleScanner(app)

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

    init {
        // React to triggers pushed from the watch.
        viewModelScope.launch {
            WearBridge.actions.collect { action ->
                _lastWatchAction.value = action
                when (action) {
                    WearContract.Action.BLE_SCAN -> startBleScan()
                    // NFC actions surface in the UI; the Activity owns the
                    // reader-mode lifecycle and performs the tap.
                    WearContract.Action.NFC_READ,
                    WearContract.Action.NFC_EMULATE -> Unit
                }
            }
        }
    }

    fun startBleScan(): Boolean = bleScanner.start()

    fun stopBleScan() = bleScanner.stop()

    fun onNfcTag(info: NfcTagInfo) {
        _nfcTag.value = info
    }

    override fun onCleared() {
        bleScanner.stop()
        super.onCleared()
    }
}
