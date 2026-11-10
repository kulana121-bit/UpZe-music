package com.ravi.musicspot

import com.ravi.musicspot.lyrics.LrcParser
import com.ravi.musicspot.lyrics.instrumentalGaps
import com.ravi.musicspot.lyrics.introGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.2: "• • •" waiting dots appear in intros and beat breaks, never between ordinary lines. */
class LyricGapsTest {
    @Test fun introAndMarkedBreak() {
        val lines = LrcParser.parse("[00:12.00]first line\n[00:15.00]second line\n[00:18.00]\n[00:30.00]after the drop\n[00:33.00]next")
        assertEquals(12_000L, lines.introGap()?.endMs)
        val g = lines.instrumentalGaps().single()
        assertEquals(1, g.afterIndex); assertTrue(g.startMs <= 18_000L); assertEquals(30_000L, g.endMs)
    }

    @Test fun noDotsBetweenCloseLines() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:04.00]b\n[00:07.50]c\n[00:10.00]d")
        assertTrue(lines.instrumentalGaps().isEmpty()); assertEquals(null, lines.introGap())
    }

    @Test fun wordSyncedGapFromLastWord() {
        val lines = LrcParser.parse("[00:01.00]<00:01.00>hey <00:01.50>there<00:02.00>\n[00:09.00]<00:09.00>back<00:09.50>")
        val g = lines.instrumentalGaps().single()
        assertEquals(0, g.afterIndex); assertEquals(9_000L, g.endMs)
    }

    @Test fun unmarkedLongBreakIsEstimated() {
        val lines = LrcParser.parse("[00:01.00]short\n[00:25.00]much later")
        assertEquals(listOf(0), lines.instrumentalGaps().map { it.afterIndex })
    }
}
