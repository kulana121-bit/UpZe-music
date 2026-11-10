package com.ravi.musicspot

import com.ravi.musicspot.lyrics.LyricLine
import com.ravi.musicspot.lyrics.LyricWord
import com.ravi.musicspot.ui.player.LetterTiming
import com.ravi.musicspot.ui.player.SmoothClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.8: frame-accurate lyrics clock and letter-by-letter timing. */
class Lyrics108Test {
    private val ms = 1_000_000L

    @Test fun clockRunsBetweenSamples() {
        val c = SmoothClock()
        c.snap(10_000, 0)
        assertEquals(10_100L, c.at(100 * ms))
        assertEquals(10_240L, c.at(240 * ms))
    }

    @Test fun clockSnapsOnSeekAndNeverStepsBackOnSmallDrift() {
        val c = SmoothClock()
        c.snap(10_000, 0)
        c.at(250 * ms) // 10_250 shown
        c.fold(10_200, 250 * ms) // player says 50 ms behind: eased in, no backward step
        assertTrue(c.at(260 * ms) >= 10_250L)
        c.fold(60_000, 300 * ms) // a seek
        assertEquals(60_000L, c.at(300 * ms))
    }

    @Test fun lettersShareTheirWordsTime() {
        val line = LyricLine(1_000, 2_000, "Hi you", listOf(LyricWord("Hi ", 1_000, 1_200), LyricWord("you", 1_200, 1_500)), true)
        val t = LetterTiming.of(line)
        assertEquals("Hi you", t.text)
        assertEquals(1_000L, t.start[0]); assertEquals(1_100L, t.end[0]) // H
        assertEquals(1_100L, t.start[1]); assertEquals(1_200L, t.end[1]) // i
        assertEquals(1_200L, t.start[3]); assertEquals(1_300L, t.end[3]) // y
        assertEquals(0.5f, t.progress(4, 1_350), 1e-4f)                 // o half sung
        assertEquals(1f, t.progress(2, 1_200), 0f)                       // the space is lit with the i
        assertEquals(0f, t.progress(5, 1_350), 0f)                       // u not yet
    }
}
