package com.bluetier.flippercompanion.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bluetier.flippercompanion.shared.WearContract
import com.bluetier.flippercompanion.wear.ble.WearBleScanner
import com.bluetier.flippercompanion.wear.phone.PhoneConnector
import com.bluetier.flippercompanion.wear.phone.SendResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val connector = PhoneConnector(app)
    private val scanner = WearBleScanner(app)

    private val _phoneReachable = MutableStateFlow(false)
    val phoneReachable: StateFlow<Boolean> = _phoneReachable.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    val scanning: StateFlow<Boolean> = scanner.scanning

    val devices: StateFlow<List<WearBleScanner.Device>> =
        scanner.devices
            .map { it.values.sortedByDescending { d -> d.rssi } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshConnection() {
        viewModelScope.launch {
            _phoneReachable.value = connector.isPhoneReachable()
        }
    }

    fun trigger(action: WearContract.Action) {
        viewModelScope.launch {
            _status.value = when (val result = connector.trigger(action)) {
                SendResult.Delivered -> "Sent: ${action.id}"
                SendResult.NoPhoneConnected -> "No phone connected"
                is SendResult.Failed -> "Failed: ${result.error.message}"
            }
            _phoneReachable.value = connector.isPhoneReachable()
        }
    }

    fun toggleScan() {
        if (scanner.scanning.value) scanner.stop() else scanner.start()
    }

    override fun onCleared() {
        scanner.stop()
        super.onCleared()
    }
}
