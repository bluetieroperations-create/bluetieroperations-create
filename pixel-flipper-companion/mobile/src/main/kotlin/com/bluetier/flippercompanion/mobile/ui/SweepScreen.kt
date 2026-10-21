package com.bluetier.flippercompanion.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluetier.flippercompanion.mobile.ble.DeviceIdentity
import com.bluetier.flippercompanion.mobile.ble.SweepEntry

/**
 * Unwanted-tracker sweep: catalogs every BLE advertiser seen during the sweep and
 * ranks them, flagging the ones that match a known item-tracker signature or that
 * persist nearby. It can't by itself prove something is following you — re-run it
 * in a different place; a device that shows up strongly in both is worth a look.
 * This supplements, not replaces, the phone's built-in unknown-tracker alerts.
 */
@Composable
fun SweepScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val entries by viewModel.sweepEntries.collectAsStateWithLifecycle()
    val elapsed by viewModel.sweepElapsedMillis.collectAsStateWithLifecycle()
    val flagged = entries.count { it.verdict.isPossibleTracker }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Column(Modifier.padding(start = 12.dp)) {
                Text("Tracker sweep", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${elapsed / 1000}s · ${entries.size} devices · $flagged flagged",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            "Walk around for a minute, then re-run in another spot. A flagged device " +
                "that reappears strongly in both places may be travelling with you.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = viewModel::enterSweep, modifier = Modifier.fillMaxWidth()) {
            Text("Restart sweep")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.address }) { entry -> SweepRow(entry) }
        }
    }
}

@Composable
private fun SweepRow(entry: SweepEntry) {
    val tracker = entry.verdict.isPossibleTracker
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (tracker) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (tracker) "⚠ ${entry.verdict.label}" else entry.verdict.label,
                style = MaterialTheme.typography.titleSmall,
            )
            entry.device.name?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(DeviceIdentity.describe(entry.device), style = MaterialTheme.typography.labelMedium)
            Text(entry.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                "seen ${entry.device.count}× over ${entry.durationMillis / 1000}s · " +
                    "RSSI ${entry.device.minRssi}..${entry.device.maxRssi} dBm" +
                    if (!entry.device.connectable) " · non-connectable" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
