package com.bluetier.kidscoloring.art

/** A fillable area of a picture, identified by a stable [id]. */
data class Region(val id: String, val points: List<Pt>)

/** One colouring page: a name and its fillable regions, drawn back-to-front
 *  (first region is the backmost; later regions sit on top). */
data class Picture(val name: String, val regions: List<Region>) {

    /**
     * The region tapped at ([x],[y]) in authoring space, or null if the tap
     * missed every region. Front-most wins: regions are tested top (last) first,
     * so a small detail like an eye beats the body behind it.
     */
    fun regionAt(x: Float, y: Float): Region? =
        regions.lastOrNull { HitTest.contains(it.points, x, y) }

    companion object {
        /** Authoring space is a 0..[SIZE] square. */
        const val SIZE = 1000f
    }
}
