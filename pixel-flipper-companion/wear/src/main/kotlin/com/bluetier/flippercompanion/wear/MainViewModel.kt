package com.bluetier.flippercompanion.wear

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bluetier.flippercompanion.shared.WearContract
import com.bluetier.flippercompanion.wear.ble.WearBleScanner
import com.bluetier.flippercompanion.wear.phone.PhoneConnector
import com.bluetier.flippercompanion.wear.phone.SendResult
import com.bluetier.flippercompanion.shared.PointingAxis
import com.bluetier.flippercompanion.wear.phone.WatchResultBridge
import com.bluetier.flippercompanion.wear.sensors.SnapLab
import com.bluetier.flippercompanion.wear.sensors.SnapRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val connector = PhoneConnector(app)
    private val scanner = WearBleScanner(app)
    private val snapLab = SnapLab(app)
    private val axisStore = app.getSharedPreferences("snap-remote", Context.MODE_PRIVATE)
    private val snapRemote = SnapRemote(app, initialAxis = loadAxis())

    val remoteState: StateFlow<SnapRemote.State> = snapRemote.state

    /** What the phone made of the last snap. */
    val lastResult: StateFlow<String?> = WatchResultBridge.lastResult

    private val _remoteStatus = MutableStateFlow("")
    val remoteStatus: StateFlow<String> = _remoteStatus.asStateFlow()

    val snapState: StateFlow<SnapLab.State> = snapLab.state

    private val _tracesSent = MutableStateFlow(0)
    val tracesSent: StateFlow<Int> = _tracesSent.asStateFlow()

    init {
        // Every captured transient goes to the phone, rejected ones included:
        // tuning the detector is mostly a matter of studying the near-misses.
        viewModelScope.launch {
            snapLab.traces.collect { trace ->
                if (connector.sendSnapTrace(trace) is SendResult.Delivered) {
                    _tracesSent.value += 1
                }
            }
        }

        // Accepted snaps, with the aim attached, go to the phone to resolve.
        viewModelScope.launch {
            snapRemote.snaps.collect { point ->
                WatchResultBridge.clear()
                val sendResult = connector.sendSnapPoint(point)
                Log.d(
                    "FlipperSnap",
                    "send result=$sendResult aim=%.1f/%.1f wobble=%.1f"
                        .format(
                            point.bearing.yawDegrees,
                            point.bearing.pitchDegrees,
                            point.aimSpreadDegrees,
                        ),
                )
                _remoteStatus.value = when (val result = sendResult) {
                    SendResult.Delivered ->
                        "Sent ${point.bearing.yawDegrees.roundToInt()}°/" +
                            "${point.bearing.pitchDegrees.roundToInt()}°, " +
                            "wobble ${point.aimSpreadDegrees.roundToInt()}°"
                    SendResult.NoPhoneConnected -> "No phone connected"
                    is SendResult.Failed -> "Send failed: ${result.error.message}"
                }
            }
        }
    }

    private val _phoneReachable = MutableStateFlow(false)
    val phoneReachable: StateFlow<Boolean> = _phoneReachable.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    val scanning: StateFlow<Boolean> = scanner.scanning

    val devices: StateFlow<List<WearBleScanner.Device>> =
        scanner.devices
            .map { it.values.sortedByDescending { d -> d.rssi } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshConnection() {
        viewModelScope.launch {
            _phoneReachable.value = connector.isPhoneReachable()
        }
    }

    fun trigger(action: WearContract.Action) {
        viewModelScope.launch {
            _status.value = when (val result = connector.trigger(action)) {
                SendResult.Delivered -> "Sent: ${action.id}"
                SendResult.NoPhoneConnected -> "No phone connected"
                is SendResult.Failed -> "Failed: ${result.error.message}"
            }
            _phoneReachable.value = connector.isPhoneReachable()
        }
    }

    fun toggleScan() {
        if (scanner.scanning.value) scanner.stop() else scanner.start()
    }

    /**
     * Starts or stops the snap capture session. Also tells the phone, so the
     * two ends of a capture run line up in the logs.
     */
    fun toggleSnapLab() {
        if (snapLab.state.value.running) {
            snapLab.stop()
            trigger(WearContract.Action.SNAP_LAB_STOP)
        } else {
            snapLab.start()
            trigger(WearContract.Action.SNAP_LAB_START)
        }
    }

    /**
     * Starts or stops the snap remote: from here, snapping fires whatever the
     * arm is pointing at.
     */
    fun toggleSnapRemote() {
        if (snapRemote.state.value.running) {
            snapRemote.stop()
            _remoteStatus.value = ""
        } else {
            // The lab and the remote both drive the IMU flat out and both own a
            // detector; running them together would double-count every snap.
            snapLab.stop()
            WatchResultBridge.clear()
            _remoteStatus.value = ""
            snapRemote.start()
        }
    }

    /**
     * Cycles which way the watch counts as "pointing".
     *
     * Where a worn watch aims along the forearm depends on the wrist and the
     * band. It cannot be chosen by watching the live heading - turning the
     * wrist to read the display is a different orientation from pointing, so
     * the number being read describes the reading, not the aim.
     *
     * It does not have to be right in absolute terms. A target captured by
     * snapping and a snap that fires it are measured through the same axis, so
     * it cancels between them - provided it does not change in between, which
     * is why the choice is remembered rather than reset to a default on every
     * launch.
     */
    fun cyclePointingAxis() {
        val next = when (snapRemote.axis) {
            PointingAxis.TOP_EDGE -> PointingAxis.BOTTOM_EDGE
            PointingAxis.BOTTOM_EDGE -> PointingAxis.SCREEN_BACK
            PointingAxis.SCREEN_BACK -> PointingAxis.TOP_EDGE
        }
        snapRemote.axis = next
        axisStore.edit().putString(KEY_AXIS, next.name).apply()
    }

    private fun loadAxis(): PointingAxis {
        val stored = axisStore.getString(KEY_AXIS, null) ?: return PointingAxis.TOP_EDGE
        // An unknown name means a stored value from an older build, not a bug
        // worth taking the app down for.
        return PointingAxis.entries.firstOrNull { it.name == stored } ?: PointingAxis.TOP_EDGE
    }

    private companion object {
        const val KEY_AXIS = "pointing-axis"
    }

    override fun onCleared() {
        scanner.stop()
        snapLab.stop()
        snapRemote.stop()
        super.onCleared()
    }
}
