package com.bluetier.kidscoloring.art

import com.bluetier.kidscoloring.art.Shapes.circle
import com.bluetier.kidscoloring.art.Shapes.ellipse
import com.bluetier.kidscoloring.art.Shapes.polygon
import com.bluetier.kidscoloring.art.Shapes.rect
import com.bluetier.kidscoloring.art.Shapes.star
import com.bluetier.kidscoloring.art.Transform.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The built-in colouring pages, grouped by difficulty.
 *
 * Every shape is drawn from scratch out of plain circles, rectangles, triangles
 * and stars in a 0..1000 square — original line art. The HARD pages are original
 * scenes *in the style of* public-domain painters (Van Gogh, Hokusai, Monet);
 * they are not reproductions of any specific artwork.
 */
object Pictures {

    val all: List<Picture> = buildList {
        // EASY
        add(sun()); add(flower()); add(balloon()); add(star()); add(fish())
        // MEDIUM
        add(houseScene()); add(sailboat()); add(butterfly()); add(car()); add(rocket()); add(cupcake())
        // HARD (style-inspired, original compositions)
        add(starrySwirls()); add(bigWave()); add(sunflowers()); add(lilyPond())
    }

    fun byLevel(level: Level): List<Picture> = all.filter { it.level == level }

    // --- small builder helper ------------------------------------------------

    private class Build(val name: String, val level: Level, val subtitle: String? = null) {
        private val regions = ArrayList<Region>()
        fun add(id: String, pts: List<Pt>): Build { regions += Region(id, pts); return this }
        fun build() = Picture(name, level, regions, subtitle)
    }

    // ================= EASY =================

    private fun sun(): Picture = Build("Sun", Level.EASY).apply {
        val count = 10
        for (i in 0 until count) {
            val a = 2.0 * PI * i / count
            val spread = PI / count * 0.8
            fun p(ang: Double, r: Float) = Pt(500f + (r * cos(ang)).toFloat(), 500f + (r * sin(ang)).toFloat())
            add("ray$i", listOf(p(a - spread, 230f), p(a + spread, 230f), p(a, 460f)))
        }
        add("face", circle(500, 500, 230))
    }.build()

    private fun flower(): Picture = Build("Flower", Level.EASY).apply {
        add("stem", rect(488, 500, 24, 380))
        add("leafL", ellipse(400, 700, 90, 45))
        add("leafR", ellipse(600, 740, 90, 45))
        val petals = 6
        for (i in 0 until petals) {
            val a = 2.0 * PI * i / petals - PI / 2
            add("petal$i", ellipse(500 + 150 * cos(a), 400 + 150 * sin(a), 95, 70))
        }
        add("center", circle(500, 400, 95))
    }.build()

    private fun balloon(): Picture = Build("Balloon", Level.EASY).apply {
        add("string", polygon(495 to 640, 505 to 640, 540 to 900, 520 to 900))
        add("knot", polygon(470 to 630, 530 to 630, 500 to 700))
        add("balloon", ellipse(500, 360, 230, 280))
    }.build()

    private fun star(): Picture =
        Build("Star", Level.EASY).apply { add("star", star(500, 520, 360, 150, 5)) }.build()

    private fun fish(): Picture = Build("Fish", Level.EASY).apply {
        add("tail", polygon(690 to 500, 860 to 380, 860 to 620))
        add("finTop", polygon(430 to 360, 580 to 360, 480 to 470))
        add("finBottom", polygon(430 to 640, 580 to 640, 480 to 530))
        add("body", ellipse(470, 500, 250, 170))
        add("eye", circle(360, 450, 30))
    }.build()

    // ================= MEDIUM =================

    private fun houseScene(): Picture = Build("Cozy House", Level.MEDIUM).apply {
        add("sky", rect(0, 0, 1000, 700))
        add("ground", rect(0, 700, 1000, 300))
        add("sun", circle(140, 150, 90))
        add("cloud1", ellipse(760, 160, 120, 55))
        add("cloud2", ellipse(640, 200, 80, 40))
        add("treeTrunk", rect(170, 560, 45, 180))
        add("treeTop", circle(192, 500, 110))
        add("wall", rect(360, 520, 360, 240))
        add("roof", polygon(330 to 520, 750 to 520, 540 to 360))
        add("chimney", rect(640, 400, 50, 90))
        add("door", rect(500, 620, 90, 140))
        add("knob", circle(575, 695, 10))
        add("windowL", rect(400, 560, 80, 80))
        add("windowR", rect(600, 560, 80, 80))
        add("path", polygon(520 to 760, 570 to 760, 640 to 900, 450 to 900))
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

    private fun butterfly(): Picture = Build("Butterfly", Level.MEDIUM).apply {
        add("antL", polygon(486 to 250, 492 to 250, 430 to 180))
        add("antR", polygon(508 to 250, 514 to 250, 570 to 180))
        add("wingUL", ellipse(360, 420, 160, 130))
        add("wingUR", ellipse(640, 420, 160, 130))
        add("wingLL", ellipse(395, 650, 130, 110))
        add("wingLR", ellipse(605, 650, 130, 110))
        add("spotUL1", circle(340, 400, 42)); add("spotUL2", circle(400, 470, 26))
        add("spotUR1", circle(660, 400, 42)); add("spotUR2", circle(600, 470, 26))
        add("spotLL", circle(395, 650, 34))
        add("spotLR", circle(605, 650, 34))
        add("body", ellipse(500, 500, 30, 210))
        add("head", circle(500, 300, 42))
    }.build()

    private fun car(): Picture = Build("Little Car", Level.MEDIUM).apply {
        add("ground", rect(0, 720, 1000, 280))
        add("body", polygon(170 to 620, 830 to 620, 830 to 520, 700 to 520, 620 to 420, 380 to 420, 300 to 520, 170 to 520))
        add("windowF", polygon(400 to 510, 500 to 510, 500 to 440, 455 to 440))
        add("windowB", polygon(520 to 510, 610 to 510, 575 to 440, 520 to 440))
        add("door", polygon(505 to 620, 505 to 520, 690 to 520, 700 to 530, 700 to 620))
        add("headlight", circle(810, 545, 22))
        add("wheelL", circle(330, 640, 70)); add("hubL", circle(330, 640, 32))
        add("wheelR", circle(680, 640, 70)); add("hubR", circle(680, 640, 32))
    }.build()

    private fun rocket(): Picture = Build("Rocket", Level.MEDIUM).apply {
        for (i in 0 until 5) {
            val s = star(150 + i * 170, 140 + (i % 2) * 90, 26, 11, 5)
            add("bgstar$i", s)
        }
        add("flameOuter", polygon(430 to 720, 570 to 720, 500 to 900))
        add("flameInner", polygon(465 to 720, 535 to 720, 500 to 830))
        add("finL", polygon(360 to 560, 430 to 560, 430 to 720))
        add("finR", polygon(640 to 560, 570 to 560, 570 to 720))
        add("body", polygon(430 to 300, 570 to 300, 570 to 720, 430 to 720))
        add("nose", polygon(430 to 300, 570 to 300, 500 to 150))
        add("window", circle(500, 430, 55))
        add("stripe", rect(430, 560, 140, 40))
    }.build()

    private fun cupcake(): Picture = Build("Cupcake", Level.MEDIUM).apply {
        add("plate", ellipse(500, 880, 300, 50))
        add("wrapper", polygon(360 to 560, 640 to 560, 600 to 820, 400 to 820))
        for (i in 0 until 5) add("fold$i", polygon((392 + i * 44) to 560, (404 + i * 44) to 560, (400 + i * 44) to 820, (388 + i * 44) to 820))
        add("frost1", ellipse(400, 520, 110, 90))
        add("frost2", ellipse(600, 520, 110, 90))
        add("frost3", ellipse(500, 440, 140, 110))
        add("cherryStem", rect(495, 300, 10, 70))
        add("cherry", circle(500, 290, 42))
        for (i in 0 until 6) add("sprinkle$i", circle(380 + i * 50, 500 + (i % 3) * 25, 12))
    }.build()

    // ================= HARD (style-inspired, original) =================

    private fun starrySwirls(): Picture = Build("Starry Swirls", Level.HARD, "in the style of Van Gogh").apply {
        add("sky", rect(0, 0, 1000, 760))
        // Swirling wind: rotated thin ellipses.
        add("swirl1a", rotate(ellipse(360, 300, 230, 90), 360, 300, 20))
        add("swirl1b", rotate(ellipse(360, 300, 150, 55), 360, 300, 20))
        add("swirl2a", rotate(ellipse(700, 240, 180, 70), 700, 240, -25))
        add("swirl2b", rotate(ellipse(700, 240, 110, 42), 700, 240, -25))
        // Moon + glow.
        add("moonGlow", circle(800, 170, 110))
        add("moon", circle(810, 160, 70))
        // Stars with glow.
        val starPos = listOf(160 to 160, 300 to 110, 520 to 150, 150 to 420, 560 to 380, 870 to 430)
        starPos.forEachIndexed { i, (x, y) ->
            add("starGlow$i", circle(x, y, 40))
            add("star$i", star(x, y, 34, 13, 4))
        }
        // Hills.
        add("hillBack", ellipse(300, 820, 520, 180))
        add("hillFront", ellipse(760, 880, 460, 200))
        // Village houses.
        for (i in 0 until 4) {
            val x = 300 + i * 120
            add("house${i}wall", rect(x, 740, 80, 90))
            add("house${i}roof", polygon(x to 740, (x + 80) to 740, (x + 40) to 700))
            add("house${i}win", rect(x + 28, 765, 24, 28))
        }
        // Cypress tree.
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

    private fun sunflowers(): Picture = Build("Sunflowers", Level.HARD, "in the style of Van Gogh").apply {
        add("background", rect(0, 0, 1000, 720))
        add("table", rect(0, 720, 1000, 280))
        add("vase", polygon(390 to 640, 610 to 640, 640 to 860, 360 to 860))
        add("vaseRim", rect(380, 620, 240, 30))
        val flowers = listOf(Triple(360, 360, 1), Triple(640, 380, 2), Triple(500, 230, 3))
        flowers.forEach { (cx, cy, n) ->
            // stem down to the vase
            add("stem$n", polygon((cx - 10) to cy, (cx + 10) to cy, (500 + 10) to 660, (500 - 10) to 660))
            for (i in 0 until 10) {
                val a = 2.0 * PI * i / 10
                add("petal$n$i", ellipse(cx + 95 * cos(a), cy + 95 * sin(a), 48, 30))
            }
            add("disc$n", circle(cx, cy, 70))
        }
        add("leafL", ellipse(330, 580, 110, 45))
        add("leafR", ellipse(690, 600, 110, 45))
    }.build()

    private fun lilyPond(): Picture = Build("Lily Pond", Level.HARD, "in the style of Monet").apply {
        add("water", rect(0, 0, 1000, 1000))
        for (i in 0 until 5) add("ripple$i", ellipse(200 + (i % 3) * 300, 150 + i * 160, 180, 26))
        // Arched bridge.
        add("bridge", polygon(120 to 430, 880 to 430, 880 to 470, 120 to 470))
        add("bridgeArch", polygon(120 to 470, 880 to 470, 760 to 560, 240 to 560))
        add("postL", rect(150, 430, 26, 150))
        add("postR", rect(824, 430, 26, 150))
        // Lily pads + flowers.
        val pads = listOf(230 to 720, 430 to 800, 650 to 740, 780 to 850, 340 to 880, 560 to 900)
        pads.forEachIndexed { i, (x, y) ->
            add("pad$i", ellipse(x, y, 95, 55))
            add("lily$i", star(x, y - 6, 38, 16, 6))
        }
        add("reed1", rect(90, 560, 16, 200))
        add("reed2", rect(905, 580, 16, 200))
    }.build()
}
