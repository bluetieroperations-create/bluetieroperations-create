package com.bluetier.flippercompanion.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluetier.flippercompanion.mobile.target.Candidate
import com.bluetier.flippercompanion.mobile.target.CompassTrust
import com.bluetier.flippercompanion.mobile.target.HoldState
import com.bluetier.flippercompanion.mobile.target.PointingAxis
import com.bluetier.flippercompanion.mobile.target.Resolution
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Records where things are by pointing the phone at them.
 *
 * A note on structure, because it is load-bearing rather than tidiness: the
 * live bearing changes many times a second, and reading it at the top of this
 * composable recomposed the entire screen at sensor rate. That was enough to
 * stop the text fields below showing what was being typed into them — each
 * keystroke's state update was racing a flood of sensor-driven recompositions.
 *
 * So anything that changes at sensor rate is read *inside* its own small
 * composable ([AimCard], [PointingAtCard]). Only those subtrees rebuild; the
 * fields, buttons and list stay still.
 */
@Composable
fun CalibrateScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    // Deliberately only slow-changing state at this level.
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val status by viewModel.calibrationStatus.collectAsStateWithLifecycle()
    val dispatchStatus by viewModel.dispatchStatus.collectAsStateWithLifecycle()
    val axis by viewModel.pointingAxis.collectAsStateWithLifecycle()
    val armed by viewModel.captureArmed.collectAsStateWithLifecycle()
    val captured by viewModel.capturedBearingLabel.collectAsStateWithLifecycle()

    var label by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    var bleAddress by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text(
                "Calibrate targets",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        if (!viewModel.compassAvailable) {
            Text("This phone has no rotation-vector sensor, so it cannot point.")
            return@Column
        }

        Text("How are you holding it?", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AxisButton(
                label = "Camera at it",
                selected = axis == PointingAxis.SCREEN_BACK,
                onClick = { viewModel.setPointingAxis(PointingAxis.SCREEN_BACK) },
                modifier = Modifier.weight(1f),
            )
            AxisButton(
                label = "Flat, like a remote",
                selected = axis == PointingAxis.TOP_EDGE,
                onClick = { viewModel.setPointingAxis(PointingAxis.TOP_EDGE) },
                modifier = Modifier.weight(1f),
            )
        }

        AimCard(viewModel = viewModel, axis = axis, armed = armed)

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Name (e.g. Living room TV)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = action,
            onValueChange = { action = it },
            label = { Text("Action (e.g. http://192.168.0.18:8080/wake)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = bleAddress,
            onValueChange = { bleAddress = it },
            label = { Text("BLE address (optional, helps disambiguate)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // The reason is stated *before* the button is pressed, which is the
        // part that was actually missing. Disabling it as well backfired: a
        // disabled filled button renders near-black on a dark theme and simply
        // vanishes, which reads as the app having lost a control rather than
        // as a form waiting to be filled in.
        val missing = buildList {
            if (captured == null) add("a captured bearing")
            if (label.isBlank()) add("a name")
            if (action.isBlank()) add("an action")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (viewModel.saveCapturedTarget(label, action, bleAddress)) {
                        label = ""
                        action = ""
                        bleAddress = ""
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Save target") }

            OutlinedButton(onClick = viewModel::restartHold, modifier = Modifier.weight(1f)) {
                Text("Re-aim")
            }
        }

        if (missing.isNotEmpty()) {
            Text(
                "Still needs ${missing.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (status.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(status, modifier = Modifier.padding(12.dp))
            }
        }

        Text("Pointing at", style = MaterialTheme.typography.titleMedium)
        PointingAtCard(viewModel)

        Button(onClick = viewModel::fireAtPointed, modifier = Modifier.fillMaxWidth()) {
            Text("Fire now")
        }

        if (dispatchStatus.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(dispatchStatus, modifier = Modifier.padding(12.dp))
            }
        }

        Text("Registered (${targets.size})", style = MaterialTheme.typography.titleMedium)
        targets.tightestSeparationDegrees()?.let { gap ->
            if (gap < 15.0) {
                Text(
                    "Closest pair is ${gap.roundToInt()}° apart — an indoor compass " +
                        "cannot reliably tell those two apart.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        targets.targets.forEach { target ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(target.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${target.bearing.yawDegrees.roundToInt()}° / " +
                                "${target.bearing.pitchDegrees.roundToInt()}°  ·  ${target.action}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = { viewModel.removeTarget(target.id) }) { Text("Remove") }
                }
            }
        }
    }
}

/**
 * The live aim readout. Reads the bearing and hold state itself, so that their
 * many-times-a-second changes recompose this card alone.
 */
@Composable
private fun AimCard(viewModel: MainViewModel, axis: PointingAxis, armed: Boolean) {
    val bearing by viewModel.liveBearing.collectAsStateWithLifecycle()
    val trust by viewModel.compassTrust.collectAsStateWithLifecycle()
    val hold by viewModel.holdState.collectAsStateWithLifecycle()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when (axis) {
                    PointingAxis.SCREEN_BACK -> "Aim the back of the phone at it"
                    PointingAxis.TOP_EDGE -> "Hold it flat, top edge towards it"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                bearing?.let {
                    "heading ${it.yawDegrees.roundToInt()}°   elevation ${it.pitchDegrees.roundToInt()}°"
                } ?: "waiting for the compass…",
                fontFamily = FontFamily.Monospace,
            )

            when (val state = hold) {
                HoldState.Moving -> Text(
                    if (armed) "Aim at it and hold still" else "Tap Capture below, then aim and hold",
                    style = MaterialTheme.typography.bodySmall,
                )
                is HoldState.Holding -> {
                    Text("Holding…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { state.progress.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is HoldState.Captured -> Text(
                    "Captured ${state.bearing.yawDegrees.roundToInt()}° / " +
                        "${state.bearing.pitchDegrees.roundToInt()}° — name it below",
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            Button(
                onClick = viewModel::armCapture,
                enabled = !armed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        armed -> "Aiming…"
                        hold is HoldState.Captured -> "Capture again"
                        else -> "Capture bearing"
                    },
                )
            }

            if (!trust.usableForCalibration) {
                Text(
                    "Compass accuracy: $trust — wave the phone in a figure of eight. " +
                        "Calibrating against an uncalibrated compass records the wrong direction.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("compass accuracy: $trust", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Live resolver verdict. Isolated for the same reason as [AimCard]. */
@Composable
private fun PointingAtCard(viewModel: MainViewModel) {
    val resolution by viewModel.liveResolution.collectAsStateWithLifecycle()

    Card(modifier = Modifier.fillMaxWidth()) {
        Text(describe(resolution), modifier = Modifier.padding(12.dp))
    }
}

private fun describe(resolution: Resolution?): String = when (resolution) {
    null -> "waiting…"
    Resolution.EmptyRegistry -> "nothing calibrated yet"

    // Heading and elevation are reported apart, because which of the two is
    // out says what to do about it: heading means you are aimed elsewhere,
    // elevation usually means the phone is held at a different tilt.
    is Resolution.NoMatch -> buildString {
        append("nothing in this direction")
        resolution.nearest?.let { near ->
            append(" — closest is ").append(near.target.label).append(", ")
            append(offsets(near))
        }
    }

    is Resolution.Ambiguous ->
        "ambiguous: ${resolution.first.target.label} or ${resolution.second.target.label} — " +
            "would do nothing"

    is Resolution.Resolved ->
        "${resolution.candidate.target.label} (${offsets(resolution.candidate)})"
}

/** "heading 4° off, elevation 30° off" — the diagnosis, not just a distance. */
private fun offsets(candidate: Candidate): String {
    val yaw = candidate.separation.yawDegrees.roundToInt()
    val pitch = candidate.separation.pitchDegrees.roundToInt()
    return "heading ${abs(yaw)}° off, elevation ${abs(pitch)}° off"
}

@Composable
private fun AxisButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}
