package com.ravi.musicspot

import com.ravi.musicspot.logic.DeviceTier
import com.ravi.musicspot.ui.motion.depthEase
import com.ravi.musicspot.ui.motion.flatBandHalf
import com.ravi.musicspot.ui.player.Pane
import com.ravi.musicspot.ui.player.inPlayer
import com.ravi.musicspot.ui.player.isFull
import com.ravi.musicspot.ui.player.isSwipeUp
import com.ravi.musicspot.ui.player.shouldDismissPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.1: full-screen Queue/Lyrics pane rules, depth focus band, lite-effects tier. */
class UpZe101Test {
    @Test fun fullPanesAreQueueAndLyricsOnly() {
        assertTrue(Pane.QUEUE.isFull()); assertTrue(Pane.LYRICS.isFull())
        assertFalse(Pane.RELATED.isFull()); assertFalse(Pane.CREDITS.isFull()); assertFalse((null as Pane?).isFull())
        assertNull(Pane.QUEUE.inPlayer()); assertNull(Pane.LYRICS.inPlayer())
        assertEquals(Pane.RELATED, Pane.RELATED.inPlayer()); assertEquals(Pane.CREDITS, Pane.CREDITS.inPlayer())
    }

    @Test fun swipeUpNeedsDistanceOrFling() {
        val dist = 150f; val fling = 2400f
        assertTrue(isSwipeUp(-200f, 0f, dist, fling))          // long drag up
        assertTrue(isSwipeUp(-50f, -3000f, dist, fling))       // short fast flick up
        assertFalse(isSwipeUp(-20f, -3000f, dist, fling))      // a twitch is not a swipe
        assertFalse(isSwipeUp(-100f, -500f, dist, fling))      // slow and short
        assertFalse(isSwipeUp(300f, 3000f, dist, fling))       // downward = collapse, never the pane
    }

    @Test fun paneDismissByDistanceOrFling() {
        assertTrue(shouldDismissPane(300f, 1000f, 0f, 3000f))
        assertFalse(shouldDismissPane(100f, 1000f, 0f, 3000f))
        assertTrue(shouldDismissPane(40f, 1000f, 3500f, 3000f))
        assertFalse(shouldDismissPane(0f, 1000f, 3500f, 3000f))
    }

    @Test fun depthBandKeepsCentreRowsFlat() {
        // Queue on an 8" tablet: 68 dp rows (~136 px), half viewport ~700 px. 6.5 rows stay flat around the centre.
        val band = flatBandHalf(6.5f, 136f, 700f)
        for (row in 0..3) assertEquals("row $row from centre", 0f, depthEase(row * 136f / 700f, 0f, band), 0f)
        assertTrue(depthEase(1f, 0f, band) > 0.99f)            // the edge still gets the full 3D falloff
        // Without a band the curve is the original one (lyrics drum unchanged).
        assertEquals(0f, depthEase(0f, 0f, 0f), 0f)
        assertEquals(0.5f, depthEase(0.5f, 0f, 0f), 1e-6f)
        assertEquals(0f, flatBandHalf(0f, 136f, 700f), 0f)
        assertTrue(flatBandHalf(50f, 136f, 700f) <= 0.92f)     // never flattens the whole list
    }

    @Test fun liteTier() {
        assertTrue(DeviceTier.isLite(64, 4_000_000_000L))
        assertTrue(DeviceTier.isLite(256, 1_000_000_000L))
        assertFalse(DeviceTier.isLite(192, 2_000_000_000L))    // 2 GB Android 10 tablet keeps full effects
        assertFalse(DeviceTier.isLite(0, 0L))                  // unknown never counts
    }
}
