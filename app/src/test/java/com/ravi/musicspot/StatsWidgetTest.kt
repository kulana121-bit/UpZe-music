package com.ravi.musicspot

import com.ravi.musicspot.data.PlayStatsData
import com.ravi.musicspot.logic.ListenStats
import com.ravi.musicspot.logic.PlayStat
import com.ravi.musicspot.logic.Wrapped
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.widget.StatsCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Pack 5 Part 2: the stats widget shows exactly what Wrapped.summarize says for this week (no maths of its own). */
class StatsWidgetTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_800_000_000_000L
    private val today = ListenStats.epochDay(now, utc)
    private fun t(id: String, artist: String) = Track(id, "Song $id", artist)

    @Test fun numbersComeFromWrappedSummarize() {
        val a = t("a", "Arijit Singh"); val b = t("b", "AP Dhillon")
        val d = PlayStatsData(
            stats = mapOf("a" to PlayStat(a, 7), "b" to PlayStat(b, 2)),
            days = mapOf(today to mapOf("a" to 3), today - 1 to mapOf("a" to 2, "b" to 2), today - 2 to mapOf("a" to 1), today - 9 to mapOf("b" to 40)),
        )
        val s = Wrapped.summarize(d, Wrapped.Range.WEEK, now, utc)
        val m = StatsCard.from(d, now, utc)
        assertFalse(m.empty)
        assertEquals(s.totalPlays, m.plays)
        assertEquals(s.currentStreak, m.streak)
        assertEquals("${s.topSongs.first().track.title} · ${s.topSongs.first().track.artist}", m.topSong)
        // And the fixture's actual values (the 40 plays nine days ago are outside the week).
        assertEquals(8, m.plays); assertEquals(3, m.streak); assertEquals("Song a · Arijit Singh", m.topSong)
    }

    @Test fun newUserGetsTheFriendlyLineNotNumbers() {
        val m = StatsCard.from(PlayStatsData(), now, utc)
        assertTrue(m.empty)
        assertEquals(0, m.plays); assertEquals(null, m.topSong)
        assertTrue(StatsCard.EMPTY_LINE.startsWith("Play something"))
    }
}
