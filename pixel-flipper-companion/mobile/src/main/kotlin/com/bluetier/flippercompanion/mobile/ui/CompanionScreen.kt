package com.bluetier.flippercompanion.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluetier.flippercompanion.mobile.ble.BleDeviceInfo
import com.bluetier.flippercompanion.mobile.ble.DeviceIdentity
import com.bluetier.flippercompanion.mobile.nfc.NfcTagInfo

/**
 * Root phone UI. Shows the NFC panel + BLE scanner, and switches to the GATT
 * browser once a device connection is in progress.
 */
@Composable
fun CompanionScreen(
    viewModel: MainViewModel,
    nfcSupported: Boolean,
    nfcEnabled: Boolean,
    onToggleScan: () -> Unit,
    onFind: () -> Unit,
    onSweep: () -> Unit,
    onProbe: () -> Unit,
    onCalibrate: () -> Unit,
    onArmTextWrite: (String) -> Unit,
    onArmUriWrite: (String) -> Unit,
    onCancelNfcWrite: () -> Unit,
) {
    val connectedAddress by viewModel.connectedAddress.collectAsStateWithLifecycle()
    val obdActive by viewModel.obdActive.collectAsStateWithLifecycle()
    val scanMode by viewModel.scanMode.collectAsStateWithLifecycle()

    if (connectedAddress != null) {
        if (obdActive) {
            ObdScreen(viewModel = viewModel, address = connectedAddress!!, onBack = viewModel::exitObd)
        } else {
            GattScreen(viewModel = viewModel, address = connectedAddress!!, onBack = viewModel::disconnect)
        }
        return
    }

    when (scanMode) {
        ScanMode.FINDER -> { FinderScreen(viewModel, onBack = viewModel::exitScanMode); return }
        ScanMode.SWEEP -> { SweepScreen(viewModel, onBack = viewModel::exitScanMode); return }
        ScanMode.PROBE -> { ProbeScreen(viewModel, onBack = viewModel::exitScanMode); return }
        ScanMode.CALIBRATE -> {
            CalibrateScreen(
                viewModel = viewModel,
                onBack = viewModel::exitCalibrate,
                onArmUriWrite = onArmUriWrite,
                onCancelNfcWrite = onCancelNfcWrite,
                nfcSupported = nfcSupported,
                nfcEnabled = nfcEnabled,
            )
            return
        }
        ScanMode.LIST -> Unit
    }

    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val nfcTag by viewModel.nfcTag.collectAsStateWithLifecycle()
    val nfcArmed by viewModel.nfcArmed.collectAsStateWithLifecycle()
    val nfcWriteStatus by viewModel.nfcWriteStatus.collectAsStateWithLifecycle()
    val watchAction by viewModel.lastWatchAction.collectAsStateWithLifecycle()
    val tagStatus by viewModel.tagStatus.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding() // edge-to-edge is enforced at targetSdk 35
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A tag tapped from the lock screen lands here, so what it did has to
        // be visible without going looking for it.
        if (tagStatus.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(tagStatus, modifier = Modifier.padding(12.dp))
            }
        }

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

        if (nfcSupported && nfcEnabled) {
            NfcWriteCard(
                armed = nfcArmed,
                status = nfcWriteStatus,
                onArmText = onArmTextWrite,
                onArmUri = onArmUriWrite,
                onCancel = onCancelNfcWrite,
            )
        }

        Button(onClick = onToggleScan, modifier = Modifier.fillMaxWidth()) {
            Text(if (scanning) "Stop BLE scan" else "Start BLE scan")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onFind, modifier = Modifier.weight(1f)) { Text("Find a device") }
            OutlinedButton(onClick = onSweep, modifier = Modifier.weight(1f)) { Text("Tracker sweep") }
        }
        // Neither needs BLE permission, so they are not routed through the
        // scan gate. Calibration does read the BLE census when it is running,
        // but works without it.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onProbe, modifier = Modifier.weight(1f)) {
                Text("Probe")
            }
            OutlinedButton(onClick = onCalibrate, modifier = Modifier.weight(1f)) {
                Text("Calibrate targets")
            }
        }

        Text("BLE devices (${devices.size}) — tap to connect", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.address }) { device ->
                BleRow(device, onConnect = { viewModel.connect(device.address) })
            }
        }
    }
}

@Composable
private fun NfcWriteCard(
    armed: Boolean,
    status: String?,
    onArmText: (String) -> Unit,
    onArmUri: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var isUri by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Write a tag", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !isUri, onClick = { isUri = false }, label = { Text("Text") })
                FilterChip(selected = isUri, onClick = { isUri = true }, label = { Text("URL") })
            }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(if (isUri) "https://example.com" else "Text to write") },
                singleLine = true,
                enabled = !armed,
                modifier = Modifier.fillMaxWidth(),
            )
            if (armed) {
                Text("Armed — hold a writable tag to the phone now.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            } else {
                Button(
                    onClick = { if (isUri) onArmUri(value.trim()) else onArmText(value) },
                    enabled = value.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Arm write — then tap a tag") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
private fun BleRow(device: BleDeviceInfo, onConnect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(device.name ?: "(unnamed)", style = MaterialTheme.typography.titleSmall)
            Text(DeviceIdentity.describe(device), style = MaterialTheme.typography.labelMedium)
            Text(device.address, fontFamily = FontFamily.Monospace)
            Text("RSSI ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
        }
    }
}
