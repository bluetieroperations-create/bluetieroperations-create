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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluetier.flippercompanion.mobile.probe.PointingStrategy
import com.bluetier.flippercompanion.mobile.probe.SensorCapability
import com.bluetier.flippercompanion.mobile.snap.ShareResult
import com.bluetier.flippercompanion.mobile.snap.SnapTraceSharing
import kotlin.math.roundToInt

/**
 * Reports what this phone can offer a point-and-snap gesture, and collects the
 * snap traces the watch sends.
 *
 * Two questions, one screen. What the phone can do about *pointing* is fixed
 * hardware, read once. What the watch can do about *snapping* is a measurement
 * that accumulates while you stand there snapping, so the trace counter and the
 * CSV path are here too.
 */
@Composable
fun ProbeScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val capabilities by viewModel.pointingCapabilities.collectAsStateWithLifecycle()
    val traceCount by viewModel.snapTraceCount.collectAsStateWithLifecycle()
    val acceptedCount by viewModel.snapTraceAccepted.collectAsStateWithLifecycle()
    val tracePath by viewModel.snapTracePath.collectAsStateWithLifecycle()
    val lastTrace by viewModel.lastSnapTrace.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var shareMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text(
                "Pointing & snap probe",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        val caps = capabilities
        if (caps == null) {
            Text("Probing…")
            return@Column
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(caps.deviceModel, style = MaterialTheme.typography.titleMedium)
                Text("Android API ${caps.androidApiLevel}")
                Text(
                    if (caps.uwbPresent) "UWB radio: present" else "UWB radio: absent",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(strategyExplanation(caps.strategy))
                if (caps.rotationVectorLacksMagnetometer) {
                    Text(
                        "Warning: rotation vector with no magnetometer — no true north.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text("Sensors", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(caps.sensors, key = { it.sensor.name }) { sensor -> SensorRow(sensor) }
        }

        Text("Snap traces from the watch", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("$traceCount captured, $acceptedCount accepted")
                lastTrace?.let { trace ->
                    Text(
                        "last: ${trace.sampleCount} samples over " +
                            "${"%.1f".format(trace.durationMs)} ms at " +
                            "${trace.accelHz.roundToInt()} Hz",
                    )
                    Text(
                        if (trace.accepted) "verdict: accepted"
                        else "verdict: ${trace.rejections.joinToString(", ")}",
                    )
                }
                Text(
                    "CSV: $tracePath",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // Sharing beats adb for getting the data off the phone: tuning means
        // fetching this file repeatedly, and the share sheet is two taps.
        Button(
            onClick = {
                shareMessage = when (val result = SnapTraceSharing.share(context)) {
                    ShareResult.Shared -> null
                    ShareResult.NothingToShare -> "No traces captured yet"
                    is ShareResult.Failed -> "Could not share: ${result.reason}"
                }
            },
            enabled = traceCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (traceCount > 0) "Share CSV ($traceCount traces)" else "Share CSV")
        }

        shareMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::refreshSnapTraces) { Text("Refresh") }
            OutlinedButton(onClick = viewModel::clearSnapTraces) { Text("Clear CSV") }
        }
    }
}

@Composable
private fun SensorRow(sensor: SensorCapability) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(sensor.sensor.name, style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("max ")
                    // 0 Hz means the sensor reports on change rather than at a rate.
                    append(if (sensor.maxRateHz > 0) "${sensor.maxRateHz.roundToInt()} Hz" else "on change")
                    append("  ·  resolution ${sensor.resolution}")
                    if (sensor.vendor.isNotBlank()) append("  ·  ${sensor.vendor}")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun strategyExplanation(strategy: PointingStrategy): String = when (strategy) {
    PointingStrategy.UWB_ANGLE_OF_ARRIVAL ->
        "Use UWB angle of arrival: a real bearing, roughly ±5°. Needs anchors and androidx.core.uwb."
    PointingStrategy.FUSED_ROTATION_VECTOR ->
        "Use compass heading + pitch against calibrated targets. Indoor magnetic error ±10–20° " +
            "sets how close two targets can sit."
    PointingStrategy.GAME_ROTATION_VECTOR_ONLY ->
        "Relative orientation only — no true north, so the registry needs re-zeroing. Fallback."
    PointingStrategy.UNSUPPORTED ->
        "No sensor here can establish a direction."
}
