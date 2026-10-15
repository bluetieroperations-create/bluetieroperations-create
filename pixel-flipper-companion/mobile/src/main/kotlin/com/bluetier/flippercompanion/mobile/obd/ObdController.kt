package com.bluetier.flippercompanion.mobile.obd

import com.bluetier.flippercompanion.mobile.ble.BleConnection
import com.bluetier.flippercompanion.mobile.ble.GattEvent
import com.bluetier.flippercompanion.mobile.ble.GattServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ObdDirection { SENT, RECEIVED, DECODED, INFO }

data class ObdLine(val direction: ObdDirection, val text: String)

/**
 * A line-oriented ELM327 terminal layered on [BleConnection].
 *
 * ELM327 adapters are strictly request/response: you send one AT/OBD command,
 * wait for the '>' prompt, then send the next. So commands are serialized through
 * a channel — each is written, then its reply is awaited (with a timeout) before
 * the next is sent. Replies are reassembled by [ObdFraming] and, where possible,
 * decoded by [ObdPids].
 *
 * This talks only to a serial adapter the user has plugged into their own OBD-II
 * port; it does not interpret or act on vehicle data beyond displaying it.
 */
class ObdController(
    private val connection: BleConnection,
    private val scope: CoroutineScope,
) {
    private val framing = ObdFraming()
    private val replies = Channel<String>(Channel.UNLIMITED)
    private val commands = Channel<String>(Channel.UNLIMITED)

    private val _lines = MutableStateFlow<List<ObdLine>>(emptyList())
    val lines: StateFlow<List<ObdLine>> = _lines.asStateFlow()

    private val _channel = MutableStateFlow<UartChannel?>(null)
    val channel: StateFlow<UartChannel?> = _channel.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var notifyJob: Job? = null
    private var commandJob: Job? = null

    /** Auto-detect the UART characteristics from [services]. @return false if none found. */
    fun configure(services: List<GattServiceInfo>): Boolean {
        val found = UartDetect.detect(services) ?: return false
        _channel.value = found
        return true
    }

    /** Start the terminal: subscribe to notifications and begin processing commands. */
    fun start() {
        if (_running.value) return
        val ch = _channel.value ?: run {
            addLine(ObdDirection.INFO, "No UART characteristics detected on this device")
            return
        }
        framing.reset()
        _running.value = true
        connection.setNotifications(ch.rx, true)
        addLine(ObdDirection.INFO, "OBD terminal ready. Try ATZ, then 0100.")

        notifyJob = scope.launch {
            connection.events.collect { event ->
                if (event is GattEvent.Notification && event.uuid == ch.rx.charUuid) {
                    framing.append(event.value).forEach { replies.trySend(it) }
                }
            }
        }
        commandJob = scope.launch {
            for (command in commands) deliver(ch, command)
        }
    }

    fun stop() {
        notifyJob?.cancel(); notifyJob = null
        commandJob?.cancel(); commandJob = null
        _channel.value?.let { connection.setNotifications(it.rx, false) }
        _running.value = false
    }

    /** Queue a command (CR is appended by the writer). Ignored until [start]. */
    fun send(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || !_running.value) return
        commands.trySend(trimmed)
    }

    /** Queue the usual ELM327 setup: reset, echo/linefeeds/spaces off, auto protocol. */
    fun sendInit() = INIT_SEQUENCE.forEach(::send)

    fun clear() {
        _lines.value = emptyList()
    }

    private suspend fun deliver(ch: UartChannel, command: String) {
        drainStaleReplies()
        addLine(ObdDirection.SENT, command)
        write(ch, command)
        val reply = withTimeoutOrNull(REPLY_TIMEOUT_MS) { replies.receive() }
        if (reply == null) {
            addLine(ObdDirection.INFO, "(no response — check the dongle and protocol)")
            return
        }
        val cleaned = ObdFraming.clean(reply)
        addLine(ObdDirection.RECEIVED, cleaned.ifEmpty { "(empty)" })
        ObdPids.decode(cleaned)?.let { addLine(ObdDirection.DECODED, it) }
    }

    private fun write(ch: UartChannel, command: String) {
        val bytes = (command + "\r").toByteArray(Charsets.US_ASCII)
        val maxChunk = (connection.mtu.value - BleConnection.ATT_HEADER_BYTES).coerceAtLeast(20)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + maxChunk, bytes.size)
            connection.writeCharacteristic(ch.tx, bytes.copyOfRange(offset, end), !ch.txWriteNoResponse)
            offset = end
        }
    }

    private fun drainStaleReplies() {
        while (replies.tryReceive().isSuccess) { /* discard unsolicited/leftover replies */ }
    }

    private fun addLine(direction: ObdDirection, text: String) {
        _lines.value = (_lines.value + ObdLine(direction, text)).takeLast(MAX_LINES)
    }

    companion object {
        /** Reset, echo off, linefeeds off, spaces off, automatic protocol select. */
        val INIT_SEQUENCE = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")

        /** Handy read-only Mode 01 queries for the quick-command row. */
        val QUICK_COMMANDS = listOf("0100", "010C", "010D", "0105", "ATRV")

        private const val REPLY_TIMEOUT_MS = 5_000L
        private const val MAX_LINES = 200
    }
}
