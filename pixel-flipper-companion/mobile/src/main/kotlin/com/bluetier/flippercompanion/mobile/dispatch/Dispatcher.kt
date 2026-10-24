package com.bluetier.flippercompanion.mobile.dispatch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** What came of trying to act on a target. */
sealed interface DispatchResult {
    data class Success(val detail: String) : DispatchResult

    /** Reached the far end and it said no. */
    data class Rejected(val detail: String) : DispatchResult

    /** Could not reach it at all. */
    data class Unreachable(val detail: String) : DispatchResult

    /** The action parsed to something this build cannot perform yet. */
    data class NotSupported(val detail: String) : DispatchResult
}

/**
 * Carries out a target's action.
 *
 * HTTP only, for now. One transport reaches a Home Assistant webhook, a relay
 * script on a PC, a Raspberry Pi and most of what a home automation setup
 * exposes, so it buys the most per line of code. ADB key events parse already
 * but are not performed: talking ADB from a phone means an ADB client and key
 * management in the app, which is worth doing only once the gesture itself has
 * proved worth keeping.
 *
 * Uses HttpURLConnection rather than adding an HTTP client dependency. The
 * calls are a POST with no body to a device on the local network; a library
 * would be weight for nothing.
 */
class Dispatcher(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
) {

    companion object {
        private const val TAG = "Dispatcher"
    }

    suspend fun dispatch(action: ActionSpec): DispatchResult = when (action) {
        is ActionSpec.Http -> performHttp(action)

        is ActionSpec.AdbKeyEvent -> DispatchResult.NotSupported(
            "ADB dispatch is not built yet - point this target at a relay over http:// " +
                "(${action.keyCode} to ${action.host}:${action.port})",
        )

        is ActionSpec.Unsupported -> DispatchResult.NotSupported(action.reason)
    }

    private suspend fun performHttp(action: ActionSpec.Http): DispatchResult =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(action.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = action.method
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    // A gesture that fires twice because a redirect was replayed
                    // is worse than one that fails and says so.
                    instanceFollowRedirects = false
                }

                action.body?.let { body ->
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }

                val code = connection.responseCode
                if (code in 200..299) {
                    DispatchResult.Success("HTTP $code")
                } else {
                    DispatchResult.Rejected("HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Dispatch to ${action.url} failed", e)
                DispatchResult.Unreachable(e.message ?: e::class.java.simpleName)
            } finally {
                connection?.disconnect()
            }
        }
}
