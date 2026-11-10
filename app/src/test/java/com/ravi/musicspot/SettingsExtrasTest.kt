package com.ravi.musicspot

import com.ravi.musicspot.ai.AiMix
import com.ravi.musicspot.data.PlayStatsData
import com.ravi.musicspot.logic.ListenStats
import com.ravi.musicspot.logic.PlayStat
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.SponsorBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SettingsExtrasTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private fun t(id: String, artist: String, album: String? = null) = Track(id, "Song $id", artist, album, durationMs = 180_000)

    @Test fun periodsRankByPlaysInRange() {
        val now = 20_000 * day + 3_600_000
        val today = ListenStats.epochDay(now, utc)
        val a = t("a", "Arijit Singh, Shreya Ghoshal", "Album X"); val b = t("b", "Diljit Dosanjh"); val c = t("c", "Arijit Singh", "Album X")
        val d = PlayStatsData(
            stats = mapOf("a" to PlayStat(a, 50), "b" to PlayStat(b, 3), "c" to PlayStat(c, 1)),
            days = mapOf(today to mapOf("b" to 3), today - 2 to mapOf("a" to 2), today - 20 to mapOf("a" to 10, "c" to 1), today - 200 to mapOf("a" to 30)),
        )
        val week = ListenStats.report(d, ListenStats.Period.WEEK, now, utc)
        assertEquals(listOf("b", "a"), week.songs.map { it.track.id })
        assertEquals(5, week.plays)
        assertEquals(7, week.chart.size); assertEquals(3, week.chart.last())
        val three = ListenStats.report(d, ListenStats.Period.THREE_WEEKS, now, utc)
        assertEquals("Arijit Singh", three.artists.first().name)   // a (12) + c (1) under the main artist
        assertEquals(13, three.artists.first().plays)
        assertEquals("Album X", three.albums.first().name)
        val year = ListenStats.report(d, ListenStats.Period.YEAR, now, utc)
        assertEquals(12, year.chart.size); assertEquals(46, year.plays)
        val all = ListenStats.report(d, ListenStats.Period.ALL, now, utc)
        assertEquals(54, all.plays) // lifetime counters
        assertEquals(4, all.activeDays)
    }

    @Test fun mainArtist() {
        assertEquals("A", ListenStats.mainArtist("A feat. B"))
        assertEquals("A", ListenStats.mainArtist("A & B"))
    }

    @Test fun aiReplyParsing() {
        val r = "Sure! ```json\n[{\"title\":\"Kesariya\",\"artist\":\"Arijit Singh\"},{\"title\":\"kesariya\",\"artist\":\"arijit singh\"},{\"title\":\"\",\"artist\":\"x\"},{\"title\":\"Lover\",\"artist\":\"Diljit Dosanjh\"}]\n```"
        val p = AiMix.parsePicks(r)
        assertEquals(listOf("Kesariya", "Lover"), p.map { it.title })
        assertTrue(AiMix.parsePicks("no json here").isEmpty())
    }

    @Test fun sponsorSegments() {
        val body = """[{"videoID":"other","segments":[]},{"videoID":"vid1","segments":[
            {"category":"music_offtopic","actionType":"skip","segment":[0.0,12.5]},
            {"category":"sponsor","actionType":"mute","segment":[30,40]},
            {"category":"outro","actionType":"skip","segment":[170.0,180.0]}]}]"""
        val s = SponsorBlock.parse(body, "vid1")
        assertEquals(2, s.size)
        assertEquals(12_500L, SponsorBlock.skipAt(s, 3_000, 180_000)?.endMs)
        assertNull(SponsorBlock.skipAt(s, 12_200, 180_000)) // inside the last 0.6 s: let it run out
        assertNull(SponsorBlock.skipAt(s, 50_000, 180_000))
    }
}
