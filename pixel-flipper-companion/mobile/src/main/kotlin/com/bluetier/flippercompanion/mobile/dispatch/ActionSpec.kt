package com.bluetier.flippercompanion.mobile.dispatch

/**
 * What a resolved target should actually do.
 *
 * A target stores its action as one opaque string, so the registry never needs
 * to know how anything is driven. Parsing happens here, at the moment of
 * dispatch, which keeps the resolver free of transport concerns and lets a
 * target's backend change without re-recording where it is in the room.
 */
sealed interface ActionSpec {

    /**
     * An HTTP call. Covers a Home Assistant webhook, a relay on a PC, a Pi, or
     * anything else that answers a URL — which is why it is the first one
     * built: one transport reaching nearly everything.
     */
    data class Http(
        val url: String,
        val method: String = "POST",
        val body: String? = null,
    ) : ActionSpec

    /**
     * A key event sent straight to an Android TV over ADB.
     *
     * Parsed from the start even though dispatching it is not built yet, so
     * targets can be recorded in their final form today rather than re-entered
     * once the transport arrives.
     */
    data class AdbKeyEvent(
        val host: String,
        val port: Int,
        val keyCode: String,
    ) : ActionSpec

    /** Recognised as nothing in particular; [reason] says what was wrong. */
    data class Unsupported(val raw: String, val reason: String) : ActionSpec
}

/**
 * Reads a target's action string.
 *
 * The form is URI-ish so an action reads as what it does:
 * ```
 * http://192.168.0.5:8080/tv/wake          an HTTP POST
 * https://ha.local/api/webhook/abc123      the same, over TLS
 * adb://192.168.0.12:5555/KEYCODE_WAKEUP   a key event to an Android TV
 * ```
 * Anything unrecognised comes back as [ActionSpec.Unsupported] with a reason
 * rather than throwing: a typo in one target should produce a clear message on
 * screen, not take down a dispatch.
 */
object ActionParser {

    private const val DEFAULT_ADB_PORT = 5555

    fun parse(raw: String): ActionSpec {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ActionSpec.Unsupported(raw, "the action is empty")
        }

        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> parseHttp(trimmed)

            trimmed.startsWith("adb://", ignoreCase = true) -> parseAdb(trimmed)

            else -> ActionSpec.Unsupported(
                raw,
                "expected it to start with http://, https:// or adb://",
            )
        }
    }

    /**
     * `http://host/path` optionally followed by a method and a body:
     * `http://host/path GET` or `http://host/path POST {"on":true}`.
     */
    private fun parseHttp(raw: String): ActionSpec {
        val parts = raw.split(' ', limit = 3)
        val url = parts[0]
        if (url.length <= "http://".length) {
            return ActionSpec.Unsupported(raw, "the URL has no host")
        }

        val method = parts.getOrNull(1)?.uppercase() ?: "POST"
        if (method !in ALLOWED_METHODS) {
            return ActionSpec.Unsupported(raw, "unsupported HTTP method $method")
        }

        return ActionSpec.Http(url = url, method = method, body = parts.getOrNull(2))
    }

    /** `adb://host[:port]/KEYCODE_NAME`. */
    private fun parseAdb(raw: String): ActionSpec {
        val withoutScheme = raw.removePrefix("adb://").removePrefix("ADB://")
        val slash = withoutScheme.indexOf('/')
        if (slash <= 0) {
            return ActionSpec.Unsupported(raw, "expected adb://host:port/KEYCODE_...")
        }

        val authority = withoutScheme.substring(0, slash)
        val keyCode = withoutScheme.substring(slash + 1).trim()
        if (keyCode.isEmpty()) {
            return ActionSpec.Unsupported(raw, "no key code given")
        }
        // Guards against a key code being quietly mistyped into something the
        // TV would ignore, which is indistinguishable from the gesture failing.
        if (!keyCode.startsWith("KEYCODE_")) {
            return ActionSpec.Unsupported(raw, "key codes start with KEYCODE_")
        }

        val colon = authority.lastIndexOf(':')
        val host = if (colon > 0) authority.substring(0, colon) else authority
        val port = if (colon > 0) {
            authority.substring(colon + 1).toIntOrNull()
                ?: return ActionSpec.Unsupported(raw, "the port is not a number")
        } else {
            DEFAULT_ADB_PORT
        }

        if (host.isBlank()) return ActionSpec.Unsupported(raw, "no host given")
        if (port !in 1..65535) return ActionSpec.Unsupported(raw, "port $port is out of range")

        return ActionSpec.AdbKeyEvent(host = host, port = port, keyCode = keyCode)
    }

    private val ALLOWED_METHODS = setOf("GET", "POST", "PUT")
}
