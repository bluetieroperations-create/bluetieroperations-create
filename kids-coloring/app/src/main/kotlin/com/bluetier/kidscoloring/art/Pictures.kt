package com.bluetier.kidscoloring.art

import com.bluetier.kidscoloring.art.Shapes.circle
import com.bluetier.kidscoloring.art.Shapes.ellipse
import com.bluetier.kidscoloring.art.Shapes.polygon
import com.bluetier.kidscoloring.art.Shapes.rect
import com.bluetier.kidscoloring.art.Shapes.star
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The built-in colouring pages, grouped by difficulty — aimed at older kids,
 * teens and adults rather than toddlers.
 *
 * Everything is original line art generated in code from circles, polygons,
 * stars and the radial [Mandala] ring generators in a 0..1000 square. The HARD
 * tier holds intricate mandalas / a rose-window plus original scenes *in the
 * style of* public-domain painters (Van Gogh, Hokusai, Monet) — not
 * reproductions of any specific artwork.
 */
object Pictures {

    val all: List<Picture> = buildList {
        // EASY — clean, simple, not babyish
        add(crystalGem()); add(crescentStars()); add(mapleLeaf()); add(lotus()); add(simpleMandala())
        // MEDIUM — detailed figures
        add(rose()); add(butterfly()); add(koi()); add(owl()); add(seashell()); add(sailboat())
        // HARD — intricate mandalas, a landscape + painterly scenes
        add(sunMandala()); add(starMandala()); add(roseWindow()); add(peacock())
        add(mountainVista()); add(starrySwirls()); add(bigWave()); add(lilyPond())
    }

    fun byLevel(level: Level): List<Picture> = all.filter { it.level == level }

    // --- builder + small helpers --------------------------------------------

    private class Build(val name: String, val level: Level, val subtitle: String? = null) {
        private val regions = ArrayList<Region>()
        fun add(id: String, pts: List<Pt>): Build { regions += Region(id, pts); return this }
        fun addAll(prefix: String, polys: List<List<Pt>>): Build {
            polys.forEachIndexed { i, p -> add("$prefix$i", p) }
            return this
        }
        fun build() = Picture(name, level, regions, subtitle)
    }

    private fun rad(deg: Double) = deg * PI / 180.0
    private fun pol(cx: Double, cy: Double, r: Double, deg: Double) =
        Pt((cx + r * cos(rad(deg))).toFloat(), (cy + r * sin(rad(deg))).toFloat())

    private fun List<List<Pt>>.rotated(cx: Number, cy: Number, deg: Number): List<List<Pt>> =
        map { Transform.rotate(it, cx, cy, deg) }

    // ================= EASY =================

    private fun crystalGem(): Picture = Build("Crystal Gem", Level.EASY).apply {
        add("girdle", circle(500, 500, 350, 8))
        addAll("bezel", Mandala.diamondRing(500, 500, 8, 150, 350))
        add("table", circle(500, 500, 150, 8))
        addAll("star", Mandala.triangleRing(500, 500, 8, 0, 150))
    }.build()

    private fun crescentStars(): Picture = Build("Crescent & Stars", Level.EASY).apply {
        add("sky", rect(0, 0, 1000, 1000))
        val outer = (0..26).map { pol(480.0, 520.0, 300.0, 55.0 + 250.0 * it / 26) }
        val inner = (0..22).map { pol(600.0, 500.0, 250.0, 305.0 - 250.0 * it / 22) }
        add("moon", outer + inner)
        add("star1", star(770, 300, 70, 30, 5))
        add("star2", star(300, 250, 55, 24, 5))
        add("star3", star(820, 620, 45, 20, 5))
        add("star4", star(250, 700, 40, 18, 5))
        add("star5", star(680, 760, 36, 16, 5))
    }.build()

    private fun mapleLeaf(): Picture = Build("Maple Leaf", Level.EASY).apply {
        add("blade", star(500, 470, 340, 175, 7))
        for (i in 0 until 7) {
            val a = -90.0 + 360.0 * i / 7
            add("vein$i", listOf(Pt(500f, 470f), pol(500.0, 470.0, 325.0, a - 4), pol(500.0, 470.0, 325.0, a + 4)))
        }
        add("stem", rect(486, 640, 28, 260))
    }.build()

    private fun lotus(): Picture = Build("Lotus", Level.EASY).apply {
        addAll("outer", Mandala.petalRing(500, 520, 8, 150, 430))
        addAll("mid", Mandala.petalRing(500, 520, 8, 80, 250, fill = 0.8).rotated(500, 520, 22.5))
        add("center", circle(500, 520, 85))
        addAll("seeds", Mandala.beadRing(500, 520, 7, 40, 13))
    }.build()

    private fun simpleMandala(): Picture = Build("Simple Mandala", Level.EASY, "mandala").apply {
        addAll("ring3", Mandala.petalRing(500, 500, 12, 250, 360))
        addAll("ring2", Mandala.triangleRing(500, 500, 12, 165, 250))
        addAll("ring1", Mandala.petalRing(500, 500, 6, 70, 165))
        add("core", circle(500, 500, 68))
        addAll("beads", Mandala.beadRing(500, 500, 6, 34, 13))
    }.build()

    // ================= MEDIUM =================

    private fun rose(): Picture = Build("Rose", Level.MEDIUM).apply {
        addAll("petals1", Mandala.petalRing(500, 500, 10, 170, 340))
        addAll("petals2", Mandala.petalRing(500, 500, 8, 100, 210).rotated(500, 500, 18))
        addAll("petals3", Mandala.petalRing(500, 500, 6, 45, 130).rotated(500, 500, 36))
        addAll("petals4", Mandala.petalRing(500, 500, 5, 0, 70).rotated(500, 500, 54))
        add("bud", circle(500, 500, 38))
    }.build()

    private fun butterfly(): Picture = Build("Butterfly", Level.MEDIUM).apply {
        add("antL", polygon(486 to 250, 492 to 250, 430 to 170))
        add("antR", polygon(508 to 250, 514 to 250, 570 to 170))
        add("wingUL", ellipse(350, 420, 175, 145))
        add("wingUR", ellipse(650, 420, 175, 145))
        add("wingLL", ellipse(390, 660, 140, 120))
        add("wingLR", ellipse(610, 660, 140, 120))
        add("bandUL", ellipse(350, 360, 120, 55)); add("bandUR", ellipse(650, 360, 120, 55))
        add("spotUL1", circle(330, 430, 46)); add("spotUL2", circle(400, 500, 26))
        add("spotUR1", circle(670, 430, 46)); add("spotUR2", circle(600, 500, 26))
        add("spotLL1", circle(380, 660, 40)); add("spotLL2", circle(420, 720, 20))
        add("spotLR1", circle(620, 660, 40)); add("spotLR2", circle(580, 720, 20))
        add("body", ellipse(500, 500, 30, 215))
        add("head", circle(500, 295, 44))
        for (i in 0 until 4) add("seg$i", ellipse(500, 360 + i * 85, 28, 20))
    }.build()

    private fun koi(): Picture = Build("Koi", Level.MEDIUM).apply {
        add("pond", rect(0, 0, 1000, 1000))
        add("tail", polygon(720 to 500, 900 to 380, 870 to 500, 900 to 620))
        add("finTop", polygon(430 to 350, 560 to 360, 470 to 460))
        add("finBot", polygon(430 to 650, 560 to 640, 470 to 540))
        add("finPec", polygon(360 to 540, 300 to 650, 440 to 600))
        add("body", ellipse(470, 500, 270, 165))
        var id = 0
        for (col in 0 until 5) for (row in 0 until 4) {
            val x = 330 + col * 90
            val y = 430 + row * 50 + (col % 2) * 25
            add("scale${id++}", circle(x, y, 26))
        }
        add("head", ellipse(300, 500, 120, 140))
        add("eye", circle(275, 460, 24))
        add("barbel1", polygon(230 to 520, 180 to 560, 238 to 545))
        add("barbel2", polygon(238 to 560, 190 to 620, 248 to 585))
    }.build()

    private fun owl(): Picture = Build("Owl", Level.MEDIUM).apply {
        add("branch", rect(120, 840, 760, 40))
        add("body", ellipse(500, 520, 250, 330))
        add("wingL", ellipse(320, 560, 90, 220))
        add("wingR", ellipse(680, 560, 90, 220))
        var id = 0
        for (row in 0 until 4) for (col in 0 until 5) {
            val x = 400 + col * 50 + (row % 2) * 25
            val y = 520 + row * 70
            add("feather${id++}", ellipse(x, y, 28, 20))
        }
        add("faceDisk", ellipse(500, 330, 230, 180))
        add("eyeOuterL", circle(410, 330, 80)); add("eyeL", circle(410, 330, 40)); add("pupilL", circle(410, 330, 18))
        add("eyeOuterR", circle(590, 330, 80)); add("eyeR", circle(590, 330, 40)); add("pupilR", circle(590, 330, 18))
        add("beak", polygon(475 to 350, 525 to 350, 500 to 420))
        add("tuftL", polygon(330 to 230, 420 to 200, 410 to 300))
        add("tuftR", polygon(670 to 230, 580 to 200, 590 to 300))
        add("footL", polygon(430 to 840, 410 to 800, 470 to 800))
        add("footR", polygon(570 to 840, 530 to 800, 590 to 800))
    }.build()

    private fun seashell(): Picture = Build("Seashell", Level.MEDIUM).apply {
        val hx = 500.0; val hy = 800.0; val r = 560.0
        val arc = (0..24).map { pol(hx, hy, r, 200.0 + 140.0 * it / 24) }
        add("shell", listOf(Pt(hx.toFloat(), hy.toFloat())) + arc)
        for (i in 0 until 9) {
            val a0 = 200.0 + 140.0 * i / 9 + 3
            val a1 = 200.0 + 140.0 * (i + 1) / 9 - 3
            add("rib$i", listOf(Pt(hx.toFloat(), hy.toFloat()), pol(hx, hy, r, a0), pol(hx, hy, r, a1)))
        }
        add("earL", polygon(430 to 800, 470 to 760, 500 to 800))
        add("earR", polygon(570 to 800, 530 to 760, 500 to 800))
    }.build()

    private fun sailboat(): Picture = Build("Sailing", Level.MEDIUM).apply {
        add("sky", rect(0, 0, 1000, 720))
        add("sun", circle(180, 170, 80))
        add("cloud1", ellipse(720, 170, 110, 48))
        add("water", rect(0, 720, 1000, 280))
        add("wave1", ellipse(250, 820, 150, 30))
        add("wave2", ellipse(700, 870, 170, 32))
        add("hull", polygon(300 to 700, 700 to 700, 640 to 800, 360 to 800))
        add("mast", rect(492, 320, 16, 390))
        add("sailL", polygon(480 to 330, 480 to 660, 280 to 660))
        add("sailR", polygon(520 to 360, 520 to 660, 700 to 660))
        add("flag", polygon(508 to 320, 600 to 345, 508 to 370))
    }.build()

    // ================= HARD: mandalas =================

    private fun sunMandala(): Picture = Build("Sun Mandala", Level.HARD, "mandala").apply {
        add("bg", circle(500, 500, 470, 48))
        addAll("rays", Mandala.triangleRing(500, 500, 24, 400, 470))
        addAll("ring5", Mandala.petalRing(500, 500, 24, 320, 400))
        addAll("ring4", Mandala.sectorRing(500, 500, 16, 250, 320))
        addAll("ring3", Mandala.petalRing(500, 500, 16, 165, 250).rotated(500, 500, 11.25))
        addAll("ring2", Mandala.diamondRing(500, 500, 12, 95, 165))
        addAll("ring1", Mandala.petalRing(500, 500, 8, 35, 95))
        add("core", circle(500, 500, 34))
    }.build()

    private fun starMandala(): Picture = Build("Star Mandala", Level.HARD, "mandala").apply {
        add("bg", rect(0, 0, 1000, 1000))
        addAll("outer", Mandala.triangleRing(500, 500, 16, 380, 470))
        addAll("beads", Mandala.beadRing(500, 500, 16, 340, 26))
        addAll("ring3", Mandala.petalRing(500, 500, 12, 240, 340))
        addAll("ring2", Mandala.triangleRing(500, 500, 12, 160, 240, pointOut = false))
        add("star", star(500, 500, 160, 70, 6))
        addAll("ring1", Mandala.diamondRing(500, 500, 6, 60, 130).rotated(500, 500, 30))
        add("core", circle(500, 500, 55))
        addAll("coreBeads", Mandala.beadRing(500, 500, 6, 28, 12))
    }.build()

    private fun roseWindow(): Picture = Build("Rose Window", Level.HARD, "stained-glass style").apply {
        add("frame", circle(500, 500, 480, 40))
        addAll("petals", Mandala.petalRing(500, 500, 12, 330, 470))
        addAll("foils", Mandala.beadRing(500, 500, 12, 300, 55))
        addAll("sectors", Mandala.sectorRing(500, 500, 12, 190, 290, gap = 0.2))
        addAll("inner", Mandala.triangleRing(500, 500, 12, 90, 190))
        add("hub", circle(500, 500, 90, 12))
        addAll("hubFoils", Mandala.beadRing(500, 500, 6, 50, 22))
        add("center", circle(500, 500, 30))
    }.build()

    private fun peacock(): Picture = Build("Peacock", Level.HARD, "ornamental tail").apply {
        add("sky", rect(0, 0, 1000, 1000))
        val baseX = 500.0; val baseY = 770.0; val len = 560.0
        for (i in 0 until 11) {
            val ang = -78.0 + 156.0 * i / 10
            val midR = len * 0.5
            val tipX = baseX + len * sin(rad(ang)); val tipY = baseY - len * cos(rad(ang))
            val midX = baseX + midR * sin(rad(ang)); val midY = baseY - midR * cos(rad(ang))
            add("stalk$i", Transform.rotate(ellipse(midX, midY, 22, midR), midX, midY, ang))
            add("eyeOuter$i", circle(tipX, tipY, 52))
            add("eyeMid$i", circle(tipX, tipY, 34))
            add("eyeIn$i", circle(tipX, tipY, 16))
        }
        add("body", ellipse(500, 790, 90, 120))
        add("neck", polygon(470 to 720, 530 to 720, 545 to 560, 455 to 560))
        add("head", circle(500, 540, 55))
        add("beak", polygon(500 to 560, 440 to 575, 500 to 590))
        addAll("crest", Mandala.beadRing(500, 480, 3, 30, 9))
        add("eyeP", circle(515, 528, 14))
    }.build()

    private fun mountainVista(): Picture = Build("Mountain Vista", Level.HARD, "landscape").apply {
        // Layered sky for a graded colour.
        add("skyTop", rect(0, 0, 1000, 240))
        add("skyMid", rect(0, 240, 1000, 220))
        add("sun", circle(500, 170, 90))
        add("cloud1", ellipse(230, 150, 120, 45)); add("cloud2", ellipse(305, 172, 80, 34))
        add("cloud3", ellipse(770, 120, 110, 42)); add("cloud4", ellipse(695, 140, 70, 30))
        // Far range.
        add("mtnBack", polygon(0 to 500, 180 to 300, 360 to 460, 560 to 260, 760 to 440, 1000 to 300, 1000 to 560, 0 to 560))
        add("snowBack1", polygon(142 to 338, 218 to 338, 180 to 300))
        add("snowBack2", polygon(522 to 300, 598 to 300, 560 to 260))
        // Near range.
        add("mtnFront", polygon(0 to 620, 250 to 400, 480 to 600, 700 to 380, 1000 to 580, 1000 to 690, 0 to 690))
        add("snowFront1", polygon(210 to 440, 290 to 440, 250 to 400))
        add("snowFront2", polygon(662 to 420, 738 to 420, 700 to 380))
        // Lake + reflections.
        add("lake", rect(0, 670, 1000, 200))
        for (i in 0 until 4) add("ripple$i", ellipse(250 + (i % 2) * 450, 710 + i * 40, 150, 14))
        // Shore + conifers.
        add("ground", rect(0, 860, 1000, 140))
        val trees = listOf(150 to 860, 300 to 870, 840 to 860, 700 to 875)
        trees.forEachIndexed { i, (x, baseY) ->
            add("trunk$i", rect(x - 12, baseY - 40, 24, 60))
            add("pineA$i", polygon((x - 70) to (baseY - 30), (x + 70) to (baseY - 30), x to (baseY - 150)))
            add("pineB$i", polygon((x - 58) to (baseY - 90), (x + 58) to (baseY - 90), x to (baseY - 195)))
            add("pineC$i", polygon((x - 44) to (baseY - 150), (x + 44) to (baseY - 150), x to (baseY - 235)))
        }
    }.build()

    // ================= HARD: painterly scenes (style-inspired, original) =================

    private fun starrySwirls(): Picture = Build("Starry Swirls", Level.HARD, "in the style of Van Gogh").apply {
        add("sky", rect(0, 0, 1000, 760))
        add("swirl1a", Transform.rotate(ellipse(360, 300, 230, 90), 360, 300, 20))
        add("swirl1b", Transform.rotate(ellipse(360, 300, 150, 55), 360, 300, 20))
        add("swirl2a", Transform.rotate(ellipse(700, 240, 180, 70), 700, 240, -25))
        add("swirl2b", Transform.rotate(ellipse(700, 240, 110, 42), 700, 240, -25))
        add("moonGlow", circle(800, 170, 110))
        add("moon", circle(810, 160, 70))
        val starPos = listOf(160 to 160, 300 to 110, 520 to 150, 150 to 420, 560 to 380, 870 to 430)
        starPos.forEachIndexed { i, (x, y) ->
            add("starGlow$i", circle(x, y, 40))
            add("star$i", star(x, y, 34, 13, 4))
        }
        add("hillBack", ellipse(300, 820, 520, 180))
        add("hillFront", ellipse(760, 880, 460, 200))
        for (i in 0 until 4) {
            val x = 300 + i * 120
            add("house${i}wall", rect(x, 740, 80, 90))
            add("house${i}roof", polygon(x to 740, (x + 80) to 740, (x + 40) to 700))
            add("house${i}win", rect(x + 28, 765, 24, 28))
        }
        add("cypress", polygon(150 to 850, 210 to 850, 185 to 540))
        add("cypressTop", ellipse(185, 560, 55, 120))
    }.build()

    private fun bigWave(): Picture = Build("The Big Wave", Level.HARD, "Japanese wave-print style").apply {
        add("sky", rect(0, 0, 1000, 620))
        add("sun", circle(760, 180, 85))
        add("mountain", polygon(360 to 560, 640 to 560, 500 to 380))
        add("mountainSnow", polygon(455 to 440, 545 to 440, 500 to 380))
        add("sea", rect(0, 560, 1000, 440))
        add("waveBody", polygon(40 to 620, 180 to 420, 420 to 360, 620 to 460, 560 to 640, 300 to 700, 80 to 700))
        add("waveCurl", polygon(420 to 360, 520 to 300, 560 to 420, 480 to 470))
        for (i in 0 until 6) add("foam$i", circle(150 + i * 70, 400 + (i % 3) * 40, 34))
        add("wave2", polygon(620 to 640, 760 to 540, 920 to 600, 880 to 700, 660 to 720))
        add("boat1", polygon(260 to 630, 420 to 630, 390 to 690, 290 to 690))
        add("boat2", polygon(700 to 660, 840 to 660, 815 to 710, 725 to 710))
    }.build()

    private fun lilyPond(): Picture = Build("Lily Pond", Level.HARD, "in the style of Monet").apply {
        add("water", rect(0, 0, 1000, 1000))
        for (i in 0 until 5) add("ripple$i", ellipse(200 + (i % 3) * 300, 150 + i * 160, 180, 26))
        add("bridge", polygon(120 to 430, 880 to 430, 880 to 470, 120 to 470))
        add("bridgeArch", polygon(120 to 470, 880 to 470, 760 to 560, 240 to 560))
        add("postL", rect(150, 430, 26, 150))
        add("postR", rect(824, 430, 26, 150))
        val pads = listOf(230 to 720, 430 to 800, 650 to 740, 780 to 850, 340 to 880, 560 to 900)
        pads.forEachIndexed { i, (x, y) ->
            add("pad$i", ellipse(x, y, 95, 55))
            add("lily$i", star(x, y - 6, 38, 16, 6))
        }
        add("reed1", rect(90, 560, 16, 200))
        add("reed2", rect(905, 580, 16, 200))
    }.build()
}
