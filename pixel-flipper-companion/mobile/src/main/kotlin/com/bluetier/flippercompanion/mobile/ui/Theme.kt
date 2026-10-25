package com.bluetier.flippercompanion.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The app's Material 3 theme.
 *
 * This exists because its absence was a real, long-lived bug. Material 3
 * components fall back to [lightColorScheme] when no [MaterialTheme] is in
 * scope, while the Activity's platform theme (DeviceDefault.DayNight) paints a
 * dark window in dark mode. The result was light-scheme content on a dark
 * background: cards drew near-white and read fine, but anything painted
 * directly on the window — text field contents above all — used an `onSurface`
 * that is nearly black, and became invisible.
 *
 * It looked like text fields were not accepting input. They were; the
 * characters were simply black on black, and only appeared once something
 * echoed them inside a light card.
 *
 * Follows the system setting rather than pinning one scheme, so the window and
 * the content agree in both modes.
 */
@Composable
fun FlipperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
