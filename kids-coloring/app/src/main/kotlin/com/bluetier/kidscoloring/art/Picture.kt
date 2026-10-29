package com.bluetier.kidscoloring.art

/**
 * Page groups. EASY→HARD are difficulty tiers (fewer/bigger regions up to many
 * fine ones); BIOMES is a set of detailed organic landscape scenes.
 */
enum class Level(val label: String) { BIOMES("Biomes"), EASY("Easy"), MEDIUM("Medium"), HARD("Hard") }

/** A fillable area of a picture, identified by a stable [id]. */
data class Region(val id: String, val points: List<Pt>)

/**
 * A decorative line overlay drawn on top of the fills (open polyline, not
 * closed, not fillable). [weight] scales its thinness relative to the base
 * detail-line width — used for bark, veins, ripples, branch tiers, etc.
 */
data class Stroke(val points: List<Pt>, val weight: Float = 1f)

/**
 * One colouring page: a name, a difficulty [level], an optional [subtitle]
 * (e.g. "in the style of Van Gogh"), and its fillable regions drawn back-to-front
 * (first region is backmost; later regions sit on top).
 */
data class Picture(
    val name: String,
    val level: Level,
    val regions: List<Region>,
    val subtitle: String? = null,
    val details: List<Stroke> = emptyList(),
) {
    /**
     * The region tapped at ([x],[y]) in authoring space, or null if the tap
     * missed every region. Front-most wins: regions are tested top (last) first.
     */
    fun regionAt(x: Float, y: Float): Region? =
        regions.lastOrNull { HitTest.contains(it.points, x, y) }

    companion object {
        /** Authoring space is a 0..[SIZE] square. */
        const val SIZE = 1000f
    }
}
