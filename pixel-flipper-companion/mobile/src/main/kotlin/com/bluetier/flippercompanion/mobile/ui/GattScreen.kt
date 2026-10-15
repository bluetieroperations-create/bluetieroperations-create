package com.bluetier.flippercompanion.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluetier.flippercompanion.mobile.ble.BleConnection
import com.bluetier.flippercompanion.mobile.ble.BondState
import com.bluetier.flippercompanion.mobile.ble.CharRef
import com.bluetier.flippercompanion.mobile.ble.ConnectionState
import com.bluetier.flippercompanion.mobile.ble.GattCharacteristicInfo

/**
 * GATT browser: lists discovered services/characteristics with read, write, and
 * notify controls, plus a rolling event log. The write field targets whichever
 * characteristic is currently selected.
 */
@Composable
fun GattScreen(viewModel: MainViewModel, address: String, onBack: () -> Unit) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val services by viewModel.gattServices.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val log by viewModel.gattLog.collectAsStateWithLifecycle()
    val mtu by viewModel.mtu.collectAsStateWithLifecycle()
    val reconnectAttempt by viewModel.reconnectAttempt.collectAsStateWithLifecycle()
    val bondState by viewModel.bondState.collectAsStateWithLifecycle()

    val notifying = remember { mutableStateMapOf<CharRef, Boolean>() }
    var writeHex by remember { mutableStateOf("") }
    var withResponse by remember { mutableStateOf(true) }
    var obdError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding() // edge-to-edge is enforced at targetSdk 35
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Disconnect") }
            Column(Modifier.padding(start = 12.dp)) {
                Text(address, fontFamily = FontFamily.Monospace)
                Text(
                    statusLine(state, mtu, reconnectAttempt, viewModel.maxReconnectAttempts),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        BondRow(bondState = bondState, onPair = viewModel::pair)

        if (state == ConnectionState.Ready) {
            OutlinedButton(
                onClick = {
                    if (!viewModel.enterObd()) {
                        obdError = "No UART service found — this doesn't look like a serial/OBD adapter."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open as OBD terminal") }
            obdError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                services.forEach { service ->
                    // Keys must be unique across the whole list (a repeat crashes
                    // Compose) and Bundle-saveable, so build Strings from instance
                    // ids: UUIDs repeat on real devices (e.g. HID Report 0x2A4D).
                    item(key = "svc-${service.instanceId}-${service.uuid}") {
                        Text(
                            "Service ${service.uuid}",
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    items(
                        service.characteristics,
                        key = { "chr-${it.ref.serviceInstance}-${it.ref.charInstance}-${it.uuid}" },
                    ) { ch ->
                        CharacteristicCard(
                            ch = ch,
                            isSelected = selected == ch.ref,
                            isNotifying = notifying[ch.ref] == true,
                            onSelect = { viewModel.select(ch.ref) },
                            onRead = { viewModel.read(ch.ref) },
                            onToggleNotify = {
                                val enable = notifying[ch.ref] != true
                                notifying[ch.ref] = enable
                                viewModel.toggleNotify(ch.ref, enable)
                            },
                        )
                    }
                }
            }

            WritePanel(
                enabled = selected?.let { sel ->
                    services.flatMap { it.characteristics }
                        .firstOrNull { it.ref == sel }
                        ?.let { it.canWrite || it.canWriteNoResponse } == true
                } ?: false,
                maxNoResponseBytes = mtu - BleConnection.ATT_HEADER_BYTES,
                hex = writeHex,
                onHexChange = { writeHex = it },
                withResponse = withResponse,
                onWithResponseChange = { withResponse = it },
                onWrite = {
                    selected?.let { viewModel.write(it, writeHex, withResponse) }
                },
            )
        } else {
            Text(
                when (state) {
                    ConnectionState.Failed -> "Connection failed. Tap Disconnect and retry."
                    ConnectionState.Disconnected -> "Disconnected."
                    ConnectionState.Reconnecting ->
                        "Link lost — reconnecting automatically. Active notifications are re-armed once it's back."
                    else -> "Connecting / negotiating MTU / discovering services…"
                },
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }

        EventLog(log)
    }
}

@Composable
private fun CharacteristicCard(
    ch: GattCharacteristicInfo,
    isSelected: Boolean,
    isNotifying: Boolean,
    onSelect: () -> Unit,
    onRead: () -> Unit,
    onToggleNotify: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ch.uuid.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Text(ch.propertyBadges(), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ch.canRead) OutlinedButton(onClick = onRead) { Text("Read") }
                if (ch.canWrite || ch.canWriteNoResponse) {
                    OutlinedButton(onClick = onSelect) { Text(if (isSelected) "Selected" else "Write…") }
                }
                if (ch.canNotify) {
                    OutlinedButton(onClick = onToggleNotify) {
                        Text(if (isNotifying) "Unsubscribe" else "Notify")
                    }
                }
            }
        }
    }
}

@Composable
private fun BondRow(bondState: BondState, onPair: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Pairing", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (bondState) {
                        BondState.Bonded -> "Paired (bonded)"
                        BondState.Bonding -> "Pairing…"
                        BondState.None -> "Not paired"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = onPair,
                enabled = bondState == BondState.None,
            ) { Text("Pair") }
        }
    }
}

@Composable
private fun WritePanel(
    enabled: Boolean,
    maxNoResponseBytes: Int,
    hex: String,
    onHexChange: (String) -> Unit,
    withResponse: Boolean,
    onWithResponseChange: (Boolean) -> Unit,
    onWrite: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Write to selected characteristic", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = hex,
                onValueChange = onHexChange,
                label = { Text("Payload (hex, e.g. 01 A0 FF)") },
                supportingText = {
                    Text(
                        if (withResponse) {
                            "Over $maxNoResponseBytes B is sent as a long (prepared) write"
                        } else {
                            "Max $maxNoResponseBytes B without response at this MTU"
                        },
                    )
                },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = withResponse, onCheckedChange = onWithResponseChange, enabled = enabled)
                Text("With response", Modifier.padding(start = 8.dp))
                Button(
                    onClick = onWrite,
                    enabled = enabled && hex.isNotBlank(),
                    modifier = Modifier.padding(start = 16.dp),
                ) { Text("Write") }
            }
        }
    }
}

@Composable
private fun EventLog(log: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text("Log", style = MaterialTheme.typography.titleSmall)
            if (log.isEmpty()) {
                Text("No GATT activity yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                log.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun statusLine(state: ConnectionState, mtu: Int, attempt: Int, maxAttempts: Int): String =
    when (state) {
        ConnectionState.Disconnected -> "Disconnected"
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Discovering -> "Negotiating MTU, discovering services…"
        ConnectionState.Ready ->
            "Connected · MTU $mtu (${mtu - BleConnection.ATT_HEADER_BYTES} B/packet)"
        ConnectionState.Reconnecting -> "Reconnecting… attempt $attempt of $maxAttempts"
        ConnectionState.Failed -> "Failed"
    }

private fun GattCharacteristicInfo.propertyBadges(): String = buildList {
    if (canRead) add("READ")
    if (canWrite) add("WRITE")
    if (canWriteNoResponse) add("WRITE_NR")
    if (canNotify) add("NOTIFY")
}.joinToString("  ").ifEmpty { "(no accessible properties)" }
