package com.bluetier.kidscoloring.art

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A point in the pictures' authoring space, a 0..1000 square (x right, y down).
 * Kept free of Android/Compose types so the geometry and hit-testing are plain
 * Kotlin and unit-testable.
 */
data class Pt(val x: Float, val y: Float)

/** Shape builders. Curves are approximated as many-sided polygons, which keeps
 *  both drawing and hit-testing to a single representation: a list of points. */
object Shapes {

    fun polygon(vararg pts: Pair<Number, Number>): List<Pt> =
        pts.map { Pt(it.first.toFloat(), it.second.toFloat()) }

    fun rect(x: Number, y: Number, w: Number, h: Number): List<Pt> {
        val x0 = x.toFloat(); val y0 = y.toFloat(); val x1 = x0 + w.toFloat(); val y1 = y0 + h.toFloat()
        return listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1))
    }

    fun ellipse(cx: Number, cy: Number, rx: Number, ry: Number, sides: Int = 32): List<Pt> {
        val cX = cx.toFloat(); val cY = cy.toFloat(); val rX = rx.toFloat(); val rY = ry.toFloat()
        return (0 until sides).map { i ->
            val a = 2.0 * PI * i / sides
            Pt(cX + (rX * cos(a)).toFloat(), cY + (rY * sin(a)).toFloat())
        }
    }

    fun circle(cx: Number, cy: Number, r: Number, sides: Int = 32): List<Pt> =
        ellipse(cx, cy, r, r, sides)

    /** [points]-pointed star centred at (cx,cy). */
    fun star(cx: Number, cy: Number, outer: Number, inner: Number, points: Int = 5): List<Pt> {
        val cX = cx.toFloat(); val cY = cy.toFloat(); val o = outer.toFloat(); val inr = inner.toFloat()
        val result = ArrayList<Pt>(points * 2)
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) o else inr
            val a = PI * i / points - PI / 2 // first spike points up
            result += Pt(cX + (r * cos(a)).toFloat(), cY + (r * sin(a)).toFloat())
        }
        return result
    }
}

/** Point-list transforms used to compose richer scenes. */
object Transform {
    /** Rotates [points] by [degrees] (clockwise, screen coords) around (cx,cy). */
    fun rotate(points: List<Pt>, cx: Number, cy: Number, degrees: Number): List<Pt> {
        val cX = cx.toFloat(); val cY = cy.toFloat()
        val rad = degrees.toFloat() * (PI.toFloat() / 180f)
        val cosA = cos(rad.toDouble()).toFloat(); val sinA = sin(rad.toDouble()).toFloat()
        return points.map { p ->
            val dx = p.x - cX; val dy = p.y - cY
            Pt(cX + dx * cosA - dy * sinA, cY + dx * sinA + dy * cosA)
        }
    }

    fun translate(points: List<Pt>, dx: Number, dy: Number): List<Pt> {
        val mx = dx.toFloat(); val my = dy.toFloat()
        return points.map { Pt(it.x + mx, it.y + my) }
    }
}

/** Even-odd ray-casting point-in-polygon test. Pure; the heart of tap-to-fill. */
object HitTest {
    fun contains(polygon: List<Pt>, x: Float, y: Float): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if (((a.y > y) != (b.y > y)) &&
                (x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x)
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
