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
import kotlin.math.roundToInt

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
    val snap by viewModel.snapState.collectAsStateWithLifecycle()
    val tracesSent by viewModel.tracesSent.collectAsStateWithLifecycle()
    val remote by viewModel.remoteState.collectAsStateWithLifecycle()
    val remoteStatus by viewModel.remoteStatus.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()

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

        // The gesture itself: snap with the pointing hand and the watch sends
        // the direction; the phone decides what it meant and answers back.
        item { ListHeader { Text("Snap remote") } }

        item {
            Chip(
                label = { Text(if (remote.running) "Stop remote" else "Start remote") },
                onClick = { viewModel.toggleSnapRemote() },
                colors = if (remote.running) {
                    ChipDefaults.secondaryChipColors()
                } else {
                    ChipDefaults.primaryChipColors()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = remote.sensorsAvailable,
            )
        }

        if (!remote.sensorsAvailable) {
            item { CenteredText("This watch is missing a sensor the remote needs") }
        } else if (remote.running) {
            item {
                CenteredText(
                    remote.bearing?.let {
                        "aim ${it.yawDegrees.roundToInt()}° / ${it.pitchDegrees.roundToInt()}°"
                    } ?: "waiting for the compass…",
                )
            }
            item {
                // Which way the watch counts as pointing depends on the wrist
                // and the band, so it is chosen by watching the number above.
                Chip(
                    label = { Text("Aim axis: ${remote.axis.name}") },
                    onClick = { viewModel.cyclePointingAxis() },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                CenteredText("snaps ${remote.snapsDetected}  rejected ${remote.snapsRejected}")
            }
            remote.lastSkipped?.let { item { CenteredText("skipped: $it") } }
            if (remoteStatus.isNotEmpty()) item { CenteredText(remoteStatus) }
            // The phone's verdict. Without it a missed gesture, a failed
            // resolve and a dead relay all look identical from the wrist.
            lastResult?.let { item { ListHeader { Text(it) } } }
        }

        // Snap lab: the sample-rate spike. The headline number is the achieved
        // accelerometer rate, which decides whether the IMU can characterise a
        // snap at all or whether the microphone has to carry the decision.
        item { ListHeader { Text("Snap lab") } }

        item {
            Chip(
                label = { Text(if (snap.running) "Stop capture" else "Start capture") },
                onClick = { viewModel.toggleSnapLab() },
                colors = if (snap.running) {
                    ChipDefaults.secondaryChipColors()
                } else {
                    ChipDefaults.primaryChipColors()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = snap.accelAvailable,
            )
        }

        if (!snap.accelAvailable) {
            item { CenteredText("No accelerometer on this watch") }
        } else {
            item {
                CenteredText(
                    buildString {
                        append("accel ")
                        append(snap.accelRate?.let { "${it.hz.roundToInt()} Hz" } ?: "--")
                        append("  gyro ")
                        append(
                            when {
                                !snap.gyroAvailable -> "none"
                                else -> snap.gyroRate?.let { "${it.hz.roundToInt()} Hz" } ?: "--"
                            },
                        )
                    },
                )
            }
            item {
                CenteredText("snaps ${snap.acceptedCount}  rejected ${snap.rejectedCount}  sent $tracesSent")
            }
            snap.lastEvent?.let { event ->
                item {
                    CenteredText(
                        buildString {
                            append(if (event.accepted) "accepted" else event.rejections.joinToString(", ") { it.name })
                            append(" | ")
                            // Samples inside the transient: one or two means the
                            // stream is too slow to see a snap's shape.
                            append("${event.sampleCount} samples, ")
                            append("${"%.1f".format(event.durationMs)} ms, ")
                            append("peak ${"%.1f".format(event.peakDeviation)}")
                        },
                    )
                }
            }
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
private fun CenteredText(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        textAlign = TextAlign.Center,
    )
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
