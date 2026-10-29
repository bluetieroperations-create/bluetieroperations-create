package com.bluetier.kidscoloring.art

/** Difficulty tiers: fewer/bigger regions (EASY) up to many fine regions (HARD). */
enum class Level(val label: String) { EASY("Easy"), MEDIUM("Medium"), HARD("Hard") }

/** A fillable area of a picture, identified by a stable [id]. */
data class Region(val id: String, val points: List<Pt>)

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
