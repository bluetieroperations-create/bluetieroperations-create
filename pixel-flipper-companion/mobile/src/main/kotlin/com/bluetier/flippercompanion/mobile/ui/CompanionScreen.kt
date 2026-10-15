package com.bluetier.flippercompanion.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluetier.flippercompanion.mobile.ble.BleDeviceInfo
import com.bluetier.flippercompanion.mobile.nfc.NfcTagInfo

/**
 * Single-screen phone UI: NFC status/last-tag on top, BLE scanner below, with a
 * banner showing the most recent action the watch triggered.
 */
@Composable
fun CompanionScreen(
    viewModel: MainViewModel,
    nfcSupported: Boolean,
    nfcEnabled: Boolean,
    onToggleScan: () -> Unit,
) {
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val nfcTag by viewModel.nfcTag.collectAsStateWithLifecycle()
    val watchAction by viewModel.lastWatchAction.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Flipper Companion", style = MaterialTheme.typography.headlineSmall)

        watchAction?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Last watch trigger: ${it.id}",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        NfcCard(nfcSupported, nfcEnabled, nfcTag)

        Button(onClick = onToggleScan, modifier = Modifier.fillMaxWidth()) {
            Text(if (scanning) "Stop BLE scan" else "Start BLE scan")
        }

        Text(
            "BLE devices (${devices.size})",
            style = MaterialTheme.typography.titleMedium,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.address }) { device -> BleRow(device) }
        }
    }
}

@Composable
private fun NfcCard(supported: Boolean, enabled: Boolean, tag: NfcTagInfo?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val status = when {
                !supported -> "NFC: not supported on this device"
                !enabled -> "NFC: turned off (enable in Settings)"
                else -> "NFC: ready — tap a tag"
            }
            Text(status, style = MaterialTheme.typography.titleMedium)
            if (tag != null) {
                Text("UID: ${tag.uidHex}", fontFamily = FontFamily.Monospace)
                Text("Tech: ${tag.techList.joinToString()}")
                tag.ndefText?.let { Text("NDEF: $it") }
            }
        }
    }
}

@Composable
private fun BleRow(device: BleDeviceInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(device.name ?: "(unnamed)", style = MaterialTheme.typography.titleSmall)
            Text(device.address, fontFamily = FontFamily.Monospace)
            Text("RSSI ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}
