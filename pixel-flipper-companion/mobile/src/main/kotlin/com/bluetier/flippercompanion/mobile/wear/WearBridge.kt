package com.bluetier.flippercompanion.mobile.wear

import com.bluetier.flippercompanion.shared.WearContract
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus that carries actions the watch triggers into the running UI.
 *
 * [PhoneMessageListenerService] can be started by the system before (or without)
 * the Activity, so a plain singleton SharedFlow is the simplest way to hand an
 * incoming trigger to whatever part of the app is alive to act on it.
 */
object WearBridge {
    private val _actions = MutableSharedFlow<WearContract.Action>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val actions: SharedFlow<WearContract.Action> = _actions.asSharedFlow()

    fun emit(action: WearContract.Action) {
        _actions.tryEmit(action)
    }
}
