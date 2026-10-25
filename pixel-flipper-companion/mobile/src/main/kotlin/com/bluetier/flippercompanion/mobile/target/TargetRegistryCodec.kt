package com.bluetier.flippercompanion.mobile.target

import com.bluetier.flippercompanion.shared.Bearing
/** A decoded registry, plus how much of the file could not be read. */
data class DecodeResult(
    val registry: TargetRegistry,
    /**
     * Lines that could not be parsed. Non-zero means the file was damaged and
     * some targets were lost -- worth telling the user rather than quietly
     * presenting a short registry as complete.
     */
    val skippedLines: Int,
)

/**
 * Reads and writes the target registry as delimited text.
 *
 * Hand-rolled for the same reason [com.bluetier.flippercompanion.shared.SnapTracePayload]
 * is: this project keeps its stored formats dependency-free and legible, so a
 * registry can be inspected or repaired in a text editor.
 *
 * Form is a version line followed by one target per line:
 * ```
 * v=1
 * id=tv;label=Living room TV;yaw=137.4;pitch=-2.1;action=media_player.tv;ble=AA:BB:..;nfc=04A2..
 * ```
 * Values are escaped, so a label may contain `;` or `=` without breaking the
 * line. A single corrupt line costs that one target rather than the whole file.
 */
object TargetRegistryCodec {

    const val VERSION = 1

    private const val FIELD = ';'
    private const val ASSIGN = '='

    fun encode(registry: TargetRegistry): String = buildString {
        append("v=").append(VERSION).append('\n')
        registry.targets.forEach { target ->
            append("id=").append(escape(target.id))
            append(FIELD).append("label=").append(escape(target.label))
            append(FIELD).append("yaw=").append(target.bearing.yawDegrees)
            append(FIELD).append("pitch=").append(target.bearing.pitchDegrees)
            if (target.extraAims.isNotEmpty()) {
                // yaw,pitch pairs joined by '|'. A file written before extra
                // aims existed simply has no such field and decodes to none,
                // so an upgrade keeps every target already calibrated.
                append(FIELD).append("aims=").append(
                    target.extraAims.joinToString("|") {
                        "${it.yawDegrees},${it.pitchDegrees}"
                    },
                )
            }
            append(FIELD).append("action=").append(escape(target.action))
            target.bleAddress?.let { append(FIELD).append("ble=").append(escape(it)) }
            target.nfcTagUid?.let { append(FIELD).append("nfc=").append(escape(it)) }
            append('\n')
        }
    }

    /** Returns null when the text is not a registry of a version understood here. */
    fun decode(text: String): DecodeResult? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.firstOrNull() ?: return null
        if (header.trim() != "v=$VERSION") return null

        var skipped = 0
        val targets = mutableListOf<Target>()
        lines.drop(1).forEach { line ->
            val target = runCatching { parse(line) }.getOrNull()
            if (target == null) {
                skipped++
            } else {
                // Last definition of an id wins, matching TargetRegistry.with.
                targets.removeAll { it.id == target.id }
                targets.add(target)
            }
        }
        return DecodeResult(TargetRegistry(targets), skipped)
    }

    private fun parse(line: String): Target {
        val fields = splitUnescaped(line, FIELD)
            .mapNotNull { field ->
                val at = field.indexOf(ASSIGN)
                if (at <= 0) null else field.substring(0, at) to unescape(field.substring(at + 1))
            }
            .toMap()

        return Target(
            id = fields.getValue("id"),
            label = fields.getValue("label"),
            bearing = Bearing(
                yawDegrees = fields.getValue("yaw").toDouble(),
                pitchDegrees = fields.getValue("pitch").toDouble(),
            ),
            extraAims = fields["aims"]
                ?.split('|')
                ?.mapNotNull { pair ->
                    // A malformed aim costs that aim, not the target: a
                    // television with one readable bearing still works.
                    val parts = pair.split(',')
                    val yaw = parts.getOrNull(0)?.toDoubleOrNull()
                    val pitch = parts.getOrNull(1)?.toDoubleOrNull()
                    if (yaw == null || pitch == null) null else Bearing(yaw, pitch)
                }
                .orEmpty(),
            action = fields.getValue("action"),
            bleAddress = fields["ble"],
            nfcTagUid = fields["nfc"],
        )
    }

    /**
     * Splits on [delimiter], ignoring any that was escaped. Needed because a
     * plain split would cut a label containing a semicolon in half.
     */
    private fun splitUnescaped(line: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length -> {
                    current.append(c).append(line[i + 1])
                    i += 2
                }
                c == delimiter -> {
                    parts.add(current.toString())
                    current.clear()
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        parts.add(current.toString())
        return parts
    }

    private fun escape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\s")
                '=' -> append("\\e")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> append('\\')
                    's' -> append(';')
                    'e' -> append('=')
                    'n' -> append('\n')
                    else -> append(value[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
