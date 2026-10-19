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
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluetier.flippercompanion.mobile.ble.ProximityBand
import com.bluetier.flippercompanion.mobile.ble.Trend

/**
 * "Hot/cold" finder: lock onto a BLE device, then walk around watching the signal
 * climb as you get closer. RSSI is noisy, so this shows a smoothed value and a
 * warmer/colder trend rather than pretending to give an exact position.
 */
@Composable
fun FinderScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val target by viewModel.finderTarget.collectAsStateWithLifecycle()
    val reading by viewModel.finderReading.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Find a device", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
        }

        if (target == null) {
            Text("Pick the device to home in on:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.address }) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { viewModel.selectFinderTarget(device.address) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(device.name ?: "(unnamed)", style = MaterialTheme.typography.titleSmall)
                            Text(device.address, fontFamily = FontFamily.Monospace)
                            Text("RSSI ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            val r = reading
            Text(
                r?.device?.name ?: target.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (r == null) {
                Text("Searching…")
            } else {
                val bandText = when (r.band) {
                    ProximityBand.IMMEDIATE -> "RIGHT HERE"
                    ProximityBand.NEAR -> "CLOSE"
                    ProximityBand.FAR -> "FAR"
                    ProximityBand.DISTANT -> "VERY FAR"
                }
                Text(
                    if (r.stale) "SIGNAL LOST" else bandText,
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Map smoothed RSSI (roughly -100..-40 dBm) to a 0..1 bar.
                val fraction = ((r.device.smoothedRssi + 100) / 60.0).coerceIn(0.0, 1.0)
                LinearProgressIndicator(
                    progress = { fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                val trendText = when (r.trend) {
                    Trend.WARMER -> "▲ warmer — getting closer"
                    Trend.COLDER -> "▼ colder — moving away"
                    Trend.STEADY -> "– holding steady"
                }
                Text(if (r.stale) "–" else trendText, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Signal ${"%.0f".format(r.device.smoothedRssi)} dBm · ~${"%.1f".format(r.meters)} m (rough)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Distance is a rough estimate from signal strength — use the warmer/colder cue and sweep slowly.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = { viewModel.selectFinderTarget(null) }) { Text("Change target") }
        }
    }
}
