package com.bluetier.kidscoloring.art

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

/**
 * Procedural ring generators for mandalas and other radial, many-region designs.
 * Each function returns a list of polygons (one per fillable region) laid out
 * around a centre, so a few stacked rings make an intricate "adult colouring
 * book" page. Pure Kotlin, unit-testable.
 */
object Mandala {
    private fun polar(cx: Float, cy: Float, r: Float, a: Double): Pt =
        Pt(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())

    private const val TOP = -PI / 2 // first element points up

    /** A ring of [count] pointed petals spanning [innerR]..[outerR]. */
    fun petalRing(cx: Number, cy: Number, count: Int, innerR: Number, outerR: Number, fill: Double = 0.9): List<List<Pt>> {
        val x = cx.toFloat(); val y = cy.toFloat(); val ri = innerR.toFloat(); val ro = outerR.toFloat()
        val span = ro - ri
        val pitch = 2.0 * PI / count
        val half = pitch / 2.0 * fill
        return (0 until count).map { i ->
            val a = TOP + pitch * i
            listOf(
                polar(x, y, ri, a),
                polar(x, y, ri + span * 0.35f, a - half),
                polar(x, y, ri + span * 0.78f, a - half * 0.5),
                polar(x, y, ro, a),
                polar(x, y, ri + span * 0.78f, a + half * 0.5),
                polar(x, y, ri + span * 0.35f, a + half),
            )
        }
    }

    /** A ring of [count] triangles; apex outward by default, inward if [pointOut] is false. */
    fun triangleRing(cx: Number, cy: Number, count: Int, innerR: Number, outerR: Number, pointOut: Boolean = true, fill: Double = 0.85): List<List<Pt>> {
        val x = cx.toFloat(); val y = cy.toFloat(); val ri = innerR.toFloat(); val ro = outerR.toFloat()
        val pitch = 2.0 * PI / count
        val half = pitch / 2.0 * fill
        return (0 until count).map { i ->
            val a = TOP + pitch * i
            val baseR = if (pointOut) ri else ro
            val apexR = if (pointOut) ro else ri
            listOf(polar(x, y, baseR, a - half), polar(x, y, apexR, a), polar(x, y, baseR, a + half))
        }
    }

    /** A ring of [count] diamonds between [innerR] and [outerR]. */
    fun diamondRing(cx: Number, cy: Number, count: Int, innerR: Number, outerR: Number, fill: Double = 0.8): List<List<Pt>> {
        val x = cx.toFloat(); val y = cy.toFloat(); val ri = innerR.toFloat(); val ro = outerR.toFloat()
        val mid = (ri + ro) / 2f
        val pitch = 2.0 * PI / count
        val half = pitch / 2.0 * fill
        return (0 until count).map { i ->
            val a = TOP + pitch * i
            listOf(polar(x, y, ri, a), polar(x, y, mid, a - half), polar(x, y, ro, a), polar(x, y, mid, a + half))
        }
    }

    /** A ring of [count] annular sectors (curved "bricks") between [innerR] and [outerR]. */
    fun sectorRing(cx: Number, cy: Number, count: Int, innerR: Number, outerR: Number, gap: Double = 0.14, steps: Int = 6): List<List<Pt>> {
        val x = cx.toFloat(); val y = cy.toFloat(); val ri = innerR.toFloat(); val ro = outerR.toFloat()
        val pitch = 2.0 * PI / count
        val pad = pitch * gap / 2.0
        return (0 until count).map { i ->
            val a0 = TOP + pitch * i + pad
            val a1 = TOP + pitch * (i + 1) - pad
            val pts = ArrayList<Pt>((steps + 1) * 2)
            for (s in 0..steps) pts += polar(x, y, ro, a0 + (a1 - a0) * s / steps)       // outer arc
            for (s in 0..steps) pts += polar(x, y, ri, a1 - (a1 - a0) * s / steps)       // inner arc back
            pts
        }
    }

    /** A ring of [count] small circles (foils/beads) centred on radius [r]. */
    fun beadRing(cx: Number, cy: Number, count: Int, r: Number, beadRadius: Number, sides: Int = 16): List<List<Pt>> {
        val x = cx.toFloat(); val y = cy.toFloat(); val rr = r.toFloat()
        val pitch = 2.0 * PI / count
        return (0 until count).map { i ->
            val a = TOP + pitch * i
            Shapes.circle(x + rr * cos(a), y + rr * sin(a), beadRadius, sides)
        }
    }
}

/**
 * Organic scenery generators for hand-drawn-style landscape pages: wavy hills,
 * jagged ranges, puffy clouds, soft tree canopies, conifers, bushes, winding
 * bands (rivers/paths), rounded rocks and grass. Curves are sampled as many
 * points so fills read as flowing line art, not hard polygons. Pure Kotlin.
 */
object Scenery {
    private fun pt(x: Double, y: Double) = Pt(x.toFloat(), y.toFloat())

    /** Full-width ground/hill band: wavy top edge at [topY] (±[amp], [waves] humps), filled to [bottom]. */
    fun hill(topY: Number, amp: Number, waves: Double, phase: Double = 0.0, bottom: Number = 1000, width: Number = 1000, steps: Int = 96): List<Pt> {
        val w = width.toDouble(); val ty = topY.toDouble(); val a = amp.toDouble(); val bot = bottom.toDouble()
        val pts = ArrayList<Pt>(steps + 3)
        for (i in 0..steps) pts += pt(w * i / steps, ty + a * sin(2 * PI * waves * i / steps + phase))
        pts += pt(w, bot); pts += pt(0.0, bot)
        return pts
    }

    /** Jagged mountain range: [count] peaks up to [height], valleys at [baseY], filled to [bottom]. */
    fun mountains(baseY: Number, count: Int, height: Number, width: Number = 1000, bottom: Number = 1000, rough: Double = 0.62): List<Pt> {
        val w = width.toDouble(); val by = baseY.toDouble(); val h = height.toDouble(); val bot = bottom.toDouble()
        val pts = ArrayList<Pt>()
        pts += pt(0.0, by)
        for (i in 0 until count) {
            val x0 = w * i / count; val x1 = w * (i + 1) / count
            val peakH = h * (if (i % 2 == 0) 1.0 else rough)
            pts += pt(x0 + (x1 - x0) * 0.28, by - peakH * 0.55)
            pts += pt((x0 + x1) / 2, by - peakH)
            pts += pt(x0 + (x1 - x0) * 0.72, by - peakH * 0.5)
            pts += pt(x1, by)
        }
        pts += pt(w, bot); pts += pt(0.0, bot)
        return pts
    }

    /** Snow cap sitting on a peak at (cx, peakY). */
    fun snowCap(cx: Number, peakY: Number, halfW: Number, drop: Number): List<Pt> {
        val x = cx.toDouble(); val y = peakY.toDouble(); val hw = halfW.toDouble(); val d = drop.toDouble()
        return listOf(pt(x, y), pt(x + hw, y + d), pt(x + hw * 0.4, y + d * 0.6), pt(x, y + d), pt(x - hw * 0.4, y + d * 0.6), pt(x - hw, y + d))
    }

    /** A puffy cloud (scalloped oval). */
    fun cloud(cx: Number, cy: Number, rx: Number, ry: Number, bumps: Int = 5): List<Pt> {
        val x = cx.toDouble(); val y = cy.toDouble(); val rX = rx.toDouble(); val rY = ry.toDouble()
        val steps = bumps * 8
        return (0 until steps).map { i ->
            val a = 2 * PI * i / steps
            val m = 1.0 + 0.16 * cos(bumps * a)
            pt(x + rX * m * cos(a), y + rY * m * sin(a))
        }
    }

    /** A soft round tree canopy (leafy scalloped blob). */
    fun canopy(cx: Number, cy: Number, rx: Number, ry: Number, bumps: Int = 11): List<Pt> {
        val x = cx.toDouble(); val y = cy.toDouble(); val rX = rx.toDouble(); val rY = ry.toDouble()
        val steps = bumps * 6
        return (0 until steps).map { i ->
            val a = 2 * PI * i / steps
            val m = 1.0 + 0.14 * cos(bumps * a)
            pt(x + rX * m * cos(a), y + rY * m * sin(a))
        }
    }

    /** A conifer/pine silhouette with [tiers] zigzag branches, apex up. */
    fun pine(cx: Number, baseY: Number, width: Number, height: Number, tiers: Int = 4): List<Pt> {
        val x = cx.toDouble(); val by = baseY.toDouble(); val w = width.toDouble(); val h = height.toDouble()
        val right = ArrayList<Pt>(); val left = ArrayList<Pt>()
        right += pt(x, by - h)
        for (t in 1..tiers) {
            val frac = t.toDouble() / tiers
            val y = by - h + h * frac
            val hw = w * frac
            right += pt(x + hw, y)
            if (t < tiers) right += pt(x + hw * 0.5, y + h / tiers * 0.08)
            left += pt(x - hw, y)
            if (t < tiers) left += pt(x - hw * 0.5, y + h / tiers * 0.08)
        }
        right += pt(x + w * 0.12, by); left += pt(x - w * 0.12, by)
        return right + left.reversed()
    }

    /** A low bush (wide scalloped blob) sitting on [baseY]. */
    fun bush(cx: Number, baseY: Number, rx: Number, ry: Number, bumps: Int = 7): List<Pt> {
        val x = cx.toDouble(); val by = baseY.toDouble(); val rX = rx.toDouble(); val rY = ry.toDouble()
        val steps = bumps * 6
        val pts = ArrayList<Pt>()
        for (i in 0..steps) {
            val a = PI + PI * i / steps
            val m = 1.0 + 0.18 * cos(bumps * a)
            pts += pt(x + rX * m * cos(a), by + rY * m * sin(a))
        }
        pts += pt(x + rX, by); pts += pt(x - rX, by)
        return pts
    }

    /** A winding band (river/path) of constant [width] along a centre line. */
    fun band(center: List<Pt>, width: Number): List<Pt> {
        val half = width.toDouble() / 2
        val left = ArrayList<Pt>(); val right = ArrayList<Pt>()
        for (i in center.indices) {
            val prev = center[if (i == 0) 0 else i - 1]
            val next = center[if (i == center.size - 1) center.size - 1 else i + 1]
            val dx = (next.x - prev.x).toDouble(); val dy = (next.y - prev.y).toDouble()
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4)
            val nx = -dy / len; val ny = dx / len
            left += pt(center[i].x + nx * half, center[i].y + ny * half)
            right += pt(center[i].x - nx * half, center[i].y - ny * half)
        }
        return left + right.reversed()
    }

    /** A rounded rock/boulder resting on [baseY]. */
    fun rock(cx: Number, baseY: Number, rx: Number, ry: Number): List<Pt> {
        val x = cx.toDouble(); val by = baseY.toDouble(); val rX = rx.toDouble(); val rY = ry.toDouble()
        val steps = 20
        val pts = ArrayList<Pt>()
        for (i in 0..steps) {
            val a = PI + PI * i / steps
            val m = 1.0 + 0.05 * cos(3 * a)
            pts += pt(x + rX * m * cos(a), by + rY * m * sin(a))
        }
        pts += pt(x + rX, by); pts += pt(x - rX, by)
        return pts
    }

    /** A vertical capsule (rounded bar) from [topY] to [botY], radius [r] — cactus limbs, reeds, posts. */
    fun vbar(cx: Number, topY: Number, botY: Number, r: Number): List<Pt> {
        val x = cx.toDouble(); val ty = topY.toDouble(); val by = botY.toDouble(); val rr = r.toDouble()
        val n = 10; val pts = ArrayList<Pt>()
        for (i in 0..n) { val a = PI + PI * i / n; pts += pt(x + rr * cos(a), ty + rr * sin(a)) } // top cap
        pts += pt(x + rr, by)
        for (i in 0..n) { val a = PI * i / n; pts += pt(x + rr * cos(a), by + rr * sin(a)) }       // bottom cap
        pts += pt(x - rr, ty)
        return pts
    }

    /** A pointed leaf / frond of length [len] and width [wid], centred at (cx,cy), rotated [deg]. */
    fun leaf(cx: Number, cy: Number, len: Number, wid: Number, deg: Number): List<Pt> {
        val x = cx.toFloat(); val y = cy.toFloat(); val l = len.toDouble(); val wd = wid.toDouble()
        val n = 12; val pts = ArrayList<Pt>()
        for (i in 0..n) { val t = -1.0 + 2.0 * i / n; pts += Pt((x + l / 2 * t).toFloat(), (y + wd / 2 * (1 - t * t)).toFloat()) }
        for (i in 0..n) { val t = 1.0 - 2.0 * i / n; pts += Pt((x + l / 2 * t).toFloat(), (y - wd / 2 * (1 - t * t)).toFloat()) }
        return Transform.rotate(pts, x, y, deg)
    }

    /** A row of [count] grass tufts between [x0] and [x1] at [baseY], each [h] tall. */
    fun grass(x0: Number, x1: Number, baseY: Number, count: Int, h: Number): List<List<Pt>> {
        val a = x0.toDouble(); val b = x1.toDouble(); val by = baseY.toDouble(); val hh = h.toDouble()
        return (0 until count).map { i ->
            val cx = a + (b - a) * (i + 0.5) / count
            listOf(pt(cx - hh * 0.35, by), pt(cx - hh * 0.12, by - hh), pt(cx, by - hh * 0.5),
                pt(cx + hh * 0.12, by - hh), pt(cx + hh * 0.35, by))
        }
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
