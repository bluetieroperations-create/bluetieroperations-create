package com.bluetier.kidscoloring.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    private val square = Shapes.rect(100, 100, 200, 200) // 100..300 square

    @Test fun pointInsidePolygon() {
        assertTrue(HitTest.contains(square, 200f, 200f))
    }

    @Test fun pointOutsidePolygon() {
        assertFalse(HitTest.contains(square, 50f, 50f))
        assertFalse(HitTest.contains(square, 350f, 200f))
    }

    @Test fun degeneratePolygonIsNeverHit() {
        assertFalse(HitTest.contains(listOf(Pt(0f, 0f), Pt(1f, 1f)), 0.5f, 0.5f))
    }

    @Test fun circleCentreIsInsideEdgeIsNear() {
        val c = Shapes.circle(500, 500, 100)
        assertTrue(HitTest.contains(c, 500f, 500f))
        assertFalse(HitTest.contains(c, 500f, 50f)) // well outside
    }

    @Test fun ellipseHasExpectedVertexCount() {
        assertEquals(32, Shapes.ellipse(0, 0, 10, 20).size)
        assertEquals(8, Shapes.circle(0, 0, 5, sides = 8).size)
    }

    @Test fun starHasTwiceThePointsVertices() {
        assertEquals(10, Shapes.star(0, 0, 100, 40, points = 5).size)
    }

    @Test fun frontmostRegionWinsOnOverlap() {
        val body = Region("body", Shapes.circle(500, 500, 200))
        val eye = Region("eye", Shapes.circle(560, 460, 30))
        val picture = Picture("test", Level.EASY, listOf(body, eye)) // eye drawn on top
        assertEquals("eye", picture.regionAt(560f, 460f)?.id)
        assertEquals("body", picture.regionAt(450f, 540f)?.id)
    }

    @Test fun tapOnEmptyAreaHitsNothing() {
        val picture = Picture("test", Level.EASY, listOf(Region("dot", Shapes.circle(500, 500, 50))))
        assertNull(picture.regionAt(50f, 50f))
    }

    @Test fun everyBuiltInPictureHasRegionsAndUniqueIds() {
        assertTrue(Pictures.all.isNotEmpty())
        Pictures.all.forEach { picture ->
            assertTrue("${picture.name} has regions", picture.regions.isNotEmpty())
            val ids = picture.regions.map { it.id }
            assertEquals("${picture.name} ids unique", ids.size, ids.toSet().size)
            picture.regions.forEach { assertTrue(it.points.size >= 3) }
        }
    }

    @Test fun pictureNamesAreGloballyUnique() {
        // The view model keys saved fills by picture name, so names must be unique.
        val names = Pictures.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun everyLevelHasPictures() {
        Level.values().forEach { level ->
            val forLevel = Pictures.byLevel(level)
            assertTrue("$level has pictures", forLevel.isNotEmpty())
            forLevel.forEach { assertEquals(level, it.level) }
        }
        // byLevel partitions `all` with nothing lost or duplicated.
        val recombined = Level.values().sumOf { Pictures.byLevel(it).size }
        assertEquals(Pictures.all.size, recombined)
    }

    @Test fun hardPicturesCarryAStyleSubtitle() {
        Pictures.byLevel(Level.HARD).forEach {
            assertTrue("${it.name} has a subtitle", !it.subtitle.isNullOrBlank())
        }
    }
}
