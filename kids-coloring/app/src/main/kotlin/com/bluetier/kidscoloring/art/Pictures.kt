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
 * The built-in colouring pages. Every shape here is drawn from scratch out of
 * plain circles, rectangles, triangles and stars in a 0..1000 square — simple,
 * original line art for young children, no third-party or branded images.
 */
object Pictures {

    val all: List<Picture> = listOf(
        sun(), flower(), balloon(), house(), fish(), sailboat(), star(), butterfly(),
    )

    private fun region(id: String, pts: List<Pt>) = Region(id, pts)

    private fun sun(): Picture {
        val regions = ArrayList<Region>()
        // Rays first (behind), then the face on top.
        val count = 10
        for (i in 0 until count) {
            val a = 2.0 * PI * i / count
            val spread = PI / count * 0.8
            val inner = 230f
            val outer = 460f
            fun p(ang: Double, r: Float) = Pt(500f + (r * cos(ang)).toFloat(), 500f + (r * sin(ang)).toFloat())
            regions += region("ray$i", listOf(p(a - spread, inner), p(a + spread, inner), p(a, outer)))
        }
        regions += region("face", circle(500, 500, 230))
        return Picture("Sun", regions)
    }

    private fun flower(): Picture {
        val regions = ArrayList<Region>()
        regions += region("stem", rect(488, 500, 24, 380))
        regions += region("leafL", ellipse(400, 700, 90, 45))
        regions += region("leafR", ellipse(600, 740, 90, 45))
        val petals = 6
        for (i in 0 until petals) {
            val a = 2.0 * PI * i / petals - PI / 2
            val cx = 500 + 150 * cos(a)
            val cy = 400 + 150 * sin(a)
            regions += region("petal$i", ellipse(cx, cy, 95, 70))
        }
        regions += region("center", circle(500, 400, 95))
        return Picture("Flower", regions)
    }

    private fun balloon(): Picture {
        return Picture(
            "Balloon",
            listOf(
                region("string", polygon(495 to 640, 505 to 640, 540 to 900, 520 to 900)),
                region("knot", polygon(470 to 630, 530 to 630, 500 to 700)),
                region("balloon", ellipse(500, 360, 230, 280)),
            ),
        )
    }

    private fun house(): Picture {
        return Picture(
            "House",
            listOf(
                region("wall", rect(300, 480, 400, 350)),
                region("roof", polygon(260 to 480, 740 to 480, 500 to 240)),
                region("door", rect(450, 650, 100, 180)),
                region("windowL", rect(350, 540, 95, 95)),
                region("windowR", rect(555, 540, 95, 95)),
            ),
        )
    }

    private fun fish(): Picture {
        return Picture(
            "Fish",
            listOf(
                region("tail", polygon(690 to 500, 860 to 380, 860 to 620)),
                region("finTop", polygon(430 to 360, 580 to 360, 480 to 470)),
                region("finBottom", polygon(430 to 640, 580 to 640, 480 to 530)),
                region("body", ellipse(470, 500, 250, 170)),
                region("eye", circle(360, 450, 30)),
            ),
        )
    }

    private fun sailboat(): Picture {
        return Picture(
            "Sailboat",
            listOf(
                region("water", rect(100, 730, 800, 150)),
                region("hull", polygon(300 to 700, 700 to 700, 640 to 800, 360 to 800)),
                region("mast", rect(492, 320, 16, 390)),
                region("sailL", polygon(480 to 330, 480 to 660, 280 to 660)),
                region("sailR", polygon(520 to 360, 520 to 660, 700 to 660)),
            ),
        )
    }

    private fun star(): Picture =
        Picture("Star", listOf(region("star", star(500, 520, 360, 150, 5))))

    private fun butterfly(): Picture {
        return Picture(
            "Butterfly",
            listOf(
                region("wingUL", ellipse(370, 420, 150, 120)),
                region("wingUR", ellipse(630, 420, 150, 120)),
                region("wingLL", ellipse(400, 640, 120, 100)),
                region("wingLR", ellipse(600, 640, 120, 100)),
                region("body", ellipse(500, 520, 32, 200)),
                region("head", circle(500, 320, 44)),
            ),
        )
    }
}
