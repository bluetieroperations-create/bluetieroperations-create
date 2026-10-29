package com.bluetier.kidscoloring.ui

/** Bright, high-contrast crayon colours (ARGB). The last one is a white "eraser". */
object Palette {
    const val WHITE = 0xFFFFFFFF.toInt()

    val colors: List<Int> = listOf(
        0xFFE53935.toInt(), // red
        0xFFFB8C00.toInt(), // orange
        0xFFFDD835.toInt(), // yellow
        0xFF43A047.toInt(), // green
        0xFF1E88E5.toInt(), // blue
        0xFF8E24AA.toInt(), // purple
        0xFFEC407A.toInt(), // pink
        0xFF6D4C41.toInt(), // brown
        0xFF00ACC1.toInt(), // teal
        0xFF000000.toInt(), // black
        WHITE,              // eraser
    )
}
