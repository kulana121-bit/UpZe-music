package com.ravi.musicspot

import com.ravi.musicspot.data.HistoryEntry
import com.ravi.musicspot.data.HomePersonal
import com.ravi.musicspot.logic.PlayStat
import com.ravi.musicspot.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pack 5: the personalised Home's pure helpers (greeting, Jump back in, Your top artists). */
class HomePersonalTest {
    private val now = 1_800_000_000_000L
    private fun t(id: String, artist: String = "Artist $id") = Track(id, id, artist, artistId = "UC$artist")

    @Test fun greetingFollowsTheClock() {
        assertEquals("Good morning", HomePersonal.greeting(5))
        assertEquals("Good afternoon", HomePersonal.greeting(12))
        assertEquals("Good evening", HomePersonal.greeting(21))
        assertEquals("Late night vibes", HomePersonal.greeting(23))
        assertEquals("Late night vibes", HomePersonal.greeting(4))
    }

    @Test fun jumpBackInSkipsTheSpotlightSongAndHidesForANewListener() {
        assertTrue(HomePersonal.jumpBackIn(emptyList(), null, now).isEmpty())
        assertTrue(HomePersonal.jumpBackIn(listOf(HistoryEntry(t("a"), now)), null, now).isEmpty())
        val h = listOf(HistoryEntry(t("a"), now - 60_000), HistoryEntry(t("b"), now - 3 * 3_600_000L, plays = 4), HistoryEntry(t("c"), now - 30 * 3_600_000L))
        val j = HomePersonal.jumpBackIn(h, skipId = "a", nowMs = now)
        assertEquals(listOf("b", "c"), j.map { it.entry.track.id })
        assertEquals(listOf("3 h ago", "Yesterday"), j.map { it.ago })
    }

    @Test fun agoLabels() {
        assertEquals("Just now", HomePersonal.ago(now, now))
        assertEquals("12 min ago", HomePersonal.ago(now - 12 * 60_000L, now))
        assertEquals("4 days ago", HomePersonal.ago(now - 4 * 86_400_000L, now))
        assertEquals("1 week ago", HomePersonal.ago(now - 8 * 86_400_000L, now))
        assertEquals("Earlier", HomePersonal.ago(now - 90 * 86_400_000L, now))
    }

    @Test fun topArtistsNeedEnoughDataAndRankByPlays() {
        val few = listOf(PlayStat(t("a", "X"), 5, lastMs = now), PlayStat(t("b", "Y"), 5, lastMs = now))
        assertTrue(HomePersonal.topArtists(few, now).isEmpty()) // only two artists qualify → row hidden
        val stats = listOf(
            PlayStat(t("a", "X"), 3, lastMs = now), PlayStat(t("a2", "X, Z"), 2, lastMs = now),
            PlayStat(t("b", "Y"), 4, repeats = 1, lastMs = now), PlayStat(t("c", "W"), 3, lastMs = now),
            PlayStat(t("d", "V"), 2, lastMs = now), // below 3 plays → left out
        )
        val r = HomePersonal.topArtists(stats, now)
        assertEquals(listOf("Y", "X", "W"), r.map { it.name }) // Y: 4 + 2×1 = 6 > X: 5 > W: 3
        assertEquals(5, r[1].plays)
        assertEquals(listOf("a", "a2"), r[1].tracks.map { it.id })
    }
}
