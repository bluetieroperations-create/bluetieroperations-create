package com.bluetier.flippercompanion.mobile.nfc

/**
 * How a target is written onto, and recognised from, an NFC tag.
 *
 * A custom URI rather than the tag's own UID, because a UID can only be read
 * while this app is in the foreground running reader mode - which would mean
 * opening the app before tapping, and an app you must open first is not worth
 * walking to a television for. A URI in an NDEF record is dispatched by the
 * system, so a tap can start the app and act in one movement.
 *
 * The id travels rather than the label or the action: a target renamed or
 * repointed keeps working, and a tag never holds a stale copy of what it meant.
 */
object TargetTag {

    const val SCHEME = "flipper"
    const val HOST = "target"

    fun uriFor(targetId: String): String = "$SCHEME://$HOST/$targetId"

    /**
     * The target id a tapped tag refers to, or null if it is some other tag.
     *
     * Parsed from the string rather than through android.net.Uri so the rule
     * can be tested on a JVM. Everything NFC touches is awkward to reach on a
     * desk; the part that decides what a tag means need not be.
     */
    fun targetIdFrom(uri: String?): String? {
        val prefix = "$SCHEME://$HOST/"
        if (uri == null || !uri.startsWith(prefix, ignoreCase = true)) return null
        // substring, not removePrefix: the match above ignores case and
        // removePrefix does not, so an upper-cased scheme would be accepted
        // and then left on the front of the id.
        return uri.substring(prefix.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
            .takeIf { it.isNotBlank() }
    }
}
