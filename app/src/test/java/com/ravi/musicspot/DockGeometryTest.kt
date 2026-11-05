package com.ravi.musicspot

import com.ravi.musicspot.ui.chrome.dockFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.0.6 dock morph: the mini player and the dock never overlap at any frame, and the end states are exact. */
class DockGeometryTest {
    private val widths = listOf(280f, 296f, 320f, 336f, 360f, 392f, 411f, 480f, 560f, 576f)
    private val eps = 0.01f

    private fun noOverlap(m: Float, h: Float, w: Float) {
        val f = dockFrame(m, h, w, hasMini = true, tabs = 4, hideDrop = 120f)
        // Rectangles in a y-up space: [x, x+w] × [y, y+h].
        val mx0 = f.miniX; val mx1 = f.miniX + f.miniW; val my0 = f.miniY; val my1 = f.miniY + 64f
        val dx0 = f.dockX; val dx1 = f.dockX + f.dockW; val dy0 = f.dockY; val dy1 = f.dockY + f.dockH
        val separate = mx1 <= dx0 + eps || dx1 <= mx0 + eps || my1 <= dy0 + eps || dy1 <= my0 + eps
        assertTrue("overlap at m=$m h=$h w=$w: mini=[$mx0,$mx1]x[$my0,$my1] dock=[$dx0,$dx1]x[$dy0,$dy1]", separate)
        // Everything stays inside the chrome's width.
        assertTrue(mx0 >= -eps && mx1 <= w + eps && dx0 >= -eps && dx1 <= w + eps)
    }

    @Test fun neverOverlapsWhileMorphing() {
        for (w in widths) for (i in 0..1000) noOverlap(i / 1000f, 0f, w)
    }

    @Test fun neverOverlapsWhileHiding() {
        for (w in widths) for (i in 0..1000) noOverlap(0f, i / 1000f, w)
    }

    @Test fun searchNeverOverlapsDockWhenShown() {
        for (w in widths) for (has in listOf(true, false)) for (i in 0..1000) {
            val f = dockFrame(i / 1000f, 0f, w, has, 4, 120f)
            if (f.searchAlpha < 0.05f) continue
            val sx0 = f.searchX; val sx1 = f.searchX + f.searchSize
            val dx1 = f.dockX + f.dockW
            assertTrue("search over dock at m=${i / 1000f} w=$w mini=$has", dx1 <= sx0 + 2f || !has)
            assertTrue(sx1 <= w + eps)
        }
    }

    @Test fun endStates() {
        val w = 336f
        val e = dockFrame(0f, 0f, w, true, 4, 120f)
        // Expanded: dock beside the 68 dp search button with an 8 dp gap.
        assertEquals(w - 76f, e.dockW, eps); assertEquals(68f, e.dockH, eps); assertEquals(0f, e.dockY, eps)
        assertEquals(w - 68f, e.searchX, eps); assertEquals(68f, e.searchSize, eps); assertEquals(1f, e.searchAlpha, eps)
        assertEquals(w, e.miniW, eps); assertEquals(76f, e.miniY, eps); assertEquals(140f, e.boxH, eps)
        val m = dockFrame(1f, 0f, w, true, 4, 120f)
        assertEquals(56f, m.dockW, eps); assertEquals(56f, m.dockH, eps); assertEquals(w - 56f, m.dockX, eps)
        assertEquals(4f, m.dockY, eps)
        assertEquals(0f, m.miniY, eps); assertEquals(w - 56f - 8f, m.miniW, eps)
        assertEquals(0f, m.searchAlpha, eps)
        // No mini player: split capsule (icons pill + search orb) centred.
        val solo = dockFrame(1f, 0f, w, false, 4, 120f)
        assertEquals(4 * 44f + 12f, solo.dockW, eps)
        assertEquals((w - (solo.dockW + 8f + 56f)) / 2f, solo.dockX, eps)
        assertEquals(solo.dockX + solo.dockW + 8f, solo.searchX, eps)
        // Hide: everything slides fully below, mini player sits where the dock was.
        val hid = dockFrame(0f, 1f, w, true, 4, 120f)
        assertEquals(0f, hid.miniY, eps); assertEquals(-120f, hid.dockY, eps); assertEquals(0f, hid.dockAlpha, eps)
    }

    @Test fun progressOutsideRangeIsClamped() {
        val f = dockFrame(1.2f, -0.1f, 336f, true, 5, 120f)
        assertEquals(dockFrame(1f, 0f, 336f, true, 5, 120f), f)
    }
}
