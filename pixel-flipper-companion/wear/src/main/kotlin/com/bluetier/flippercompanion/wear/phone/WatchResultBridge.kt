package com.bluetier.flippercompanion.wear.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the phone made of the last snap, carried to whatever part of the watch
 * app is alive to show it.
 *
 * A process-wide object for the same reason as the phone's `WearBridge`: the
 * listener service can be started by the system without the Activity.
 *
 * This closes the loop. Snapping at a television and getting no answer leaves
 * you unable to tell a missed gesture from a missed resolve from a dead relay,
 * which is the difference between a remote and a guess.
 */
object WatchResultBridge {

    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    fun post(result: String) {
        _lastResult.value = result
    }

    fun clear() {
        _lastResult.value = null
    }
}
