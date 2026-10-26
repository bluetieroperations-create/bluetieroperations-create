// Top-level build file. Shared plugin versions live in gradle/libs.versions.toml.

/**
 * The commit each APK was built from, stamped into versionName and shown in
 * both apps.
 *
 * Without it there is no way to tell which build is installed on a device. The
 * release tag rolls, so every build downloads from the same URL under the same
 * filename, and a stale APK in a Downloads folder is indistinguishable from a
 * fresh one. That cost several rounds of field testing: a fix would be shipped,
 * the old build would be reinstalled, and the symptom it fixed would still be
 * there with nothing to explain why.
 *
 * providers.exec rather than a plain ProcessBuilder because the configuration
 * cache rejects running processes at configuration time any other way, and the
 * whole thing is wrapped so that a checkout without git still builds.
 */
val gitSha: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

extra["gitSha"] = gitSha
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
