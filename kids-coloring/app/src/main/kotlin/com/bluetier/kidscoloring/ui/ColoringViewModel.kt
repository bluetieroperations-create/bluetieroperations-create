package com.bluetier.kidscoloring.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.bluetier.kidscoloring.art.Level
import com.bluetier.kidscoloring.art.Picture
import com.bluetier.kidscoloring.art.Pictures

/**
 * Holds the chosen difficulty [level], which picture within that level is open,
 * the colour each region has been filled with, and the currently selected crayon.
 * Fills are kept per picture (keyed by the picture's unique name), so flipping
 * between pages or levels never loses a child's work.
 */
class ColoringViewModel : ViewModel() {

    var level by mutableStateOf(Level.BIOMES)
        private set

    var index by mutableIntStateOf(0)
        private set

    var selectedColor by mutableIntStateOf(Palette.colors.first())
        private set

    // Keyed "pictureName|regionId" -> ARGB colour. Names are globally unique.
    private val fills = mutableStateMapOf<String, Int>()

    /** The pictures in the currently selected level. */
    val pictures: List<Picture> get() = Pictures.byLevel(level)

    val picture: Picture get() = pictures[index]

    fun fillOf(regionId: String): Int? = fills[key(regionId)]

    fun selectColor(argb: Int) {
        selectedColor = argb
    }

    /** Switch difficulty; restarts at the first page of the new level. */
    fun selectLevel(newLevel: Level) {
        if (newLevel == level) return
        level = newLevel
        index = 0
    }

    /** Tap in authoring space (0..[Picture.SIZE]); fills the region hit, if any. */
    fun tapAt(x: Float, y: Float) {
        val region = picture.regionAt(x, y) ?: return
        fills[key(region.id)] = selectedColor
    }

    fun next() {
        index = (index + 1) % pictures.size
    }

    fun previous() {
        index = (index - 1 + pictures.size) % pictures.size
    }

    fun clearCurrent() {
        picture.regions.forEach { fills.remove(key(it.id)) }
    }

    private fun key(regionId: String) = "${picture.name}|$regionId"
}
