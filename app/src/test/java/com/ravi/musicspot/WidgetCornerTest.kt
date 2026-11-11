package com.ravi.musicspot

import android.appwidget.AppWidgetManager
import android.graphics.Color
import android.os.Bundle
import com.ravi.musicspot.widget.WidgetCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pack 5 Part 2 corner fix: the card bitmap is rendered at the widget's own size (aspect kept, so fitXY never crops)
 * with transparent corners and an opaque body. Android 10 (API 29), like the owner's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class WidgetCornerTest {
    private val density = 2.75f

    private fun assertRoundedCard(wDp: Int, hDp: Int) {
        val wPx = Math.round(wDp * density); val hPx = Math.round(hDp * density)
        val b = WidgetCard.render(wPx, hPx, density)
        val w = b.width; val h = b.height
        assertTrue("longest side capped", maxOf(w, h) <= WidgetCard.MAX_PX)
        // Same aspect as the widget (±1 px of rounding), so fitXY scales evenly and the corners land on the corners.
        assertEquals(wPx.toDouble() / hPx, w.toDouble() / h, 0.02 * (wPx.toDouble() / hPx))
        listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1, 1 to 1, w - 2 to h - 2).forEach { (x, y) ->
            assertEquals("corner ($x,$y) of ${wDp}x${hDp}dp must be transparent", 0, Color.alpha(b.getPixel(x, y)))
        }
        assertEquals("centre must be opaque", 255, Color.alpha(b.getPixel(w / 2, h / 2)))
        // Straight edges (middle of each side) are covered: the card fills the bitmap edge to edge.
        assertTrue(Color.alpha(b.getPixel(w / 2, 0)) >= 128)
        assertTrue(Color.alpha(b.getPixel(0, h / 2)) >= 128)
    }

    @Test fun wideWidgetHasRoundedCorners() = assertRoundedCard(320, 92)      // 4×2 now playing / 4×1 strip
    @Test fun tallWidgetHasRoundedCorners() = assertRoundedCard(160, 220)     // a tall resize
    @Test fun oneRowWidgetHasRoundedCorners() = assertRoundedCard(250, 40)    // 4×1 at its minimum height

    @Test fun pixelBufferIsTransparentOutsideTheRadiusOnly() {
        val w = 200; val h = 80
        val px = WidgetCard.pixels(w, h, radius = 20f, edge = 1f, c1 = WidgetCard.DEFAULT_C1, c2 = WidgetCard.DEFAULT_C2)
        assertEquals(0, px[0] ushr 24)                       // top-left corner
        assertEquals(0, px[w * h - 1] ushr 24)               // bottom-right corner
        assertEquals(255, px[(h / 2) * w + w / 2] ushr 24)   // centre
        assertEquals(255, px[(h / 2) * w + 30] ushr 24)      // inside, away from the corners
    }

    @Test fun sizeComesFromTheWidgetOptions() {
        val o = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 360)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 120)
        }
        assertEquals(300 to 120, WidgetCard.sizeDp(o, portrait = true, fallbackWdp = 1, fallbackHdp = 1))
        assertEquals(360 to 90, WidgetCard.sizeDp(o, portrait = false, fallbackWdp = 1, fallbackHdp = 1))
        assertEquals(250 to 92, WidgetCard.sizeDp(Bundle(), portrait = true, fallbackWdp = 250, fallbackHdp = 92))
    }
}
