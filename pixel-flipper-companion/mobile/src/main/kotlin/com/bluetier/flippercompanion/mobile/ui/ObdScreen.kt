package com.bluetier.flippercompanion.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluetier.flippercompanion.mobile.obd.ObdController
import com.bluetier.flippercompanion.mobile.obd.ObdDirection
import com.bluetier.flippercompanion.mobile.obd.ObdLine

/**
 * ELM327 terminal: quick commands, a free-text command field, and a scrolling
 * transcript. Talks to a serial/OBD adapter the user has plugged into their own
 * vehicle's OBD-II port.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow
@Composable
fun ObdScreen(viewModel: MainViewModel, address: String, onBack: () -> Unit) {
    val lines by viewModel.obdLines.collectAsStateWithLifecycle()
    val channel by viewModel.obdChannel.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    fun submit() {
        val c = command.trim()
        if (c.isNotEmpty()) {
            viewModel.obdSend(c)
            command = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Column(Modifier.padding(start = 12.dp)) {
                Text("OBD terminal", style = MaterialTheme.typography.titleMedium)
                Text(
                    channel?.let { "$address · TX ${it.tx.charUuid.short()} · RX ${it.rx.charUuid.short()}" }
                        ?: address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::obdInit) { Text("Init") }
            ObdController.QUICK_COMMANDS.forEach { cmd ->
                OutlinedButton(onClick = { viewModel.obdSend(cmd) }) { Text(cmd) }
            }
            OutlinedButton(onClick = viewModel::obdClear) { Text("Clear") }
        }

        Card(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (lines.isEmpty()) {
                    item { Text("No output yet.", style = MaterialTheme.typography.bodySmall) }
                }
                itemsIndexed(lines) { _, line -> TranscriptLine(line) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Command (e.g. 010C)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = ::submit, modifier = Modifier.padding(start = 8.dp)) { Text("Send") }
        }
    }
}

@Composable
private fun TranscriptLine(line: ObdLine) {
    val (prefix, color) = when (line.direction) {
        ObdDirection.SENT -> ">> " to MaterialTheme.colorScheme.primary
        ObdDirection.RECEIVED -> "<< " to MaterialTheme.colorScheme.onSurface
        ObdDirection.DECODED -> "=  " to MaterialTheme.colorScheme.tertiary
        ObdDirection.INFO -> "-- " to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        "$prefix${line.text}",
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun java.util.UUID.short(): String = toString().take(8)
