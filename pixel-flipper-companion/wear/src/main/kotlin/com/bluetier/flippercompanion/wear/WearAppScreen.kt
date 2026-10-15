package com.bluetier.flippercompanion.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import com.bluetier.flippercompanion.shared.WearContract

/**
 * Wear OS UI. The watch's strongest role is a remote: each chip fires an action
 * on the phone over the Data Layer. A local BLE scan is included because Wear OS
 * does permit app BLE access.
 */
@Composable
fun WearAppScreen(viewModel: MainViewModel) {
    val reachable by viewModel.phoneReachable.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()

    ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
        item { ListHeader { Text("Flipper Remote") } }

        item {
            Text(
                text = if (reachable) "Phone connected" else "Phone not found",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
            )
        }

        item {
            TriggerChip("Scan BLE on phone") { viewModel.trigger(WearContract.Action.BLE_SCAN) }
        }
        item {
            TriggerChip("Read NFC on phone") { viewModel.trigger(WearContract.Action.NFC_READ) }
        }
        item {
            TriggerChip("Emulate card on phone") {
                viewModel.trigger(WearContract.Action.NFC_EMULATE)
            }
        }

        item {
            Chip(
                label = { Text(if (scanning) "Stop watch BLE scan" else "Scan BLE on watch") },
                onClick = { viewModel.toggleScan() },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (status.isNotEmpty()) {
            item {
                Text(
                    text = status,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (devices.isNotEmpty()) {
            item { ListHeader { Text("Watch BLE (${devices.size})") } }
            items(devices, key = { it.address }) { d ->
                Chip(
                    label = { Text(d.name ?: d.address) },
                    secondaryLabel = { Text("RSSI ${d.rssi}") },
                    onClick = {},
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TriggerChip(label: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label) },
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
