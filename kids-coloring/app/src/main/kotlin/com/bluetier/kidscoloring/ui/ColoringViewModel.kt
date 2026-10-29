package com.bluetier.kidscoloring.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.bluetier.kidscoloring.art.Picture
import com.bluetier.kidscoloring.art.Pictures

/**
 * Holds which picture is open, the colour each region has been filled with, and
 * the currently selected crayon. Fills are kept per picture, so flipping between
 * pages doesn't lose a child's work.
 */
class ColoringViewModel : ViewModel() {

    val pictures: List<Picture> = Pictures.all

    var index by mutableIntStateOf(0)
        private set

    var selectedColor by mutableIntStateOf(Palette.colors.first())
        private set

    // Keyed "pictureIndex|regionId" -> ARGB colour.
    private val fills = mutableStateMapOf<String, Int>()

    val picture: Picture get() = pictures[index]

    fun fillOf(regionId: String): Int? = fills[key(regionId)]

    fun selectColor(argb: Int) {
        selectedColor = argb
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

    private fun key(regionId: String) = "$index|$regionId"
}
