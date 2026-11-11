package com.ravi.musicspot

import com.ravi.musicspot.data.PlayStatsData
import com.ravi.musicspot.logic.PlayStat
import com.ravi.musicspot.logic.TimeOfDay
import com.ravi.musicspot.logic.Wrapped
import com.ravi.musicspot.logic.Wrapped.Range
import com.ravi.musicspot.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pack 5: the Wrapped-anytime aggregation (pure, over PlayStatsData). */
class WrappedStatsTest {
    private val today = 20_000L
    private fun t(id: String, artist: String = "Artist $id", title: String = id) = Track(id, title, artist, durationMs = 200_000)
    private fun data(
        days: Map<Long, Map<String, Int>>,
        stats: List<PlayStat> = emptyList(),
        tracks: List<Track> = emptyList(),
        dayparts: Map<String, List<Int>> = emptyMap(),
    ) = PlayStatsData(stats = stats.associateBy { it.track.id }, days = days, tracks = tracks.associateBy { it.id }, dayparts = dayparts)

    // ---- range -----------------------------------------------------------------------------------------------------
    @Test fun playsInsideTheRangeCountAndOutsideDoNot() {
        val a = t("a"); val b = t("b")
        val d = data(
            days = mapOf(
                today to mapOf("a" to 2),
                today - 6 to mapOf("b" to 3),     // first day of the week window
                today - 7 to mapOf("a" to 10),    // just outside the week, inside the month
                today - 40 to mapOf("b" to 100),  // outside the month, inside the year
                today + 1 to mapOf("a" to 50),    // a future day (clock change) never counts in a range
            ),
            tracks = listOf(a, b),
        )
        assertEquals(5, Wrapped.summarize(d, Range.WEEK, today).totalPlays)
        assertEquals(15, Wrapped.summarize(d, Range.MONTH, today).totalPlays)
        assertEquals(115, Wrapped.summarize(d, Range.YEAR, today).totalPlays)
        val week = Wrapped.summarize(d, Range.WEEK, today)
        assertEquals(2, week.listeningDays)
        assertEquals(listOf("b" to 3, "a" to 2), week.topSongs.map { it.track.id to it.plays })
    }

    @Test fun allTimeUsesLifetimeCounterPlusDayLogOfDroppedSongs() {
        val a = t("a"); val gone = t("gone")
        val d = data(
            days = mapOf(today to mapOf("a" to 1, "gone" to 2)),
            stats = listOf(PlayStat(a, plays = 9, repeats = 0, lastMs = 0)), // lifetime > day log (seeded install)
            tracks = listOf(gone),
        )
        val all = Wrapped.summarize(d, Range.ALL, today)
        assertEquals(11, all.totalPlays)
        assertEquals(listOf("a" to 9, "gone" to 2), all.topSongs.map { it.track.id to it.plays })
    }

    // ---- top N -----------------------------------------------------------------------------------------------------
    @Test fun topFiveSongsAndArtistsAreOrderedAndCapped() {
        val ts = listOf(
            t("s1", "Arijit Singh"), t("s2", "Arijit Singh, Shreya Ghoshal"), t("s3", "AP Dhillon"),
            t("s4", "Karan Aujla"), t("s5", "Diljit"), t("s6", "Masoom Sharma"), t("s7", "B Praak"),
        )
        val plays = mapOf("s1" to 4, "s2" to 6, "s3" to 9, "s4" to 2, "s5" to 1, "s6" to 3, "s7" to 3)
        val d = data(days = mapOf(today to plays), tracks = ts)
        val w = Wrapped.summarize(d, Range.WEEK, today)
        // 9, 6, 4, then the 3-3 tie broken by title (s6 < s7), cut at five.
        assertEquals(listOf("s3", "s2", "s1", "s6", "s7"), w.topSongs.map { it.track.id })
        assertEquals(5, w.topSongs.size)
        // Arijit Singh = 4 + 6 (main artist of "A, B"), then AP Dhillon 9 is higher: 10 > 9.
        assertEquals(listOf("Arijit Singh" to 10, "AP Dhillon" to 9), w.topArtists.take(2).map { it.name to it.plays })
        assertEquals(5, w.topArtists.size)
        // B Praak and Masoom Sharma tie on 3 → name order.
        assertEquals(listOf("B Praak", "Masoom Sharma"), w.topArtists.drop(2).take(2).map { it.name })
    }

    @Test fun mostRepeatedAndFavouriteTimeOfDayComeFromSongsInRange() {
        val a = t("a"); val b = t("b"); val c = t("c")
        val d = data(
            days = mapOf(today to mapOf("a" to 2, "b" to 2), today - 100 to mapOf("c" to 5)),
            stats = listOf(PlayStat(a, 10, repeats = 1), PlayStat(b, 8, repeats = 4), PlayStat(c, 5, repeats = 9)),
            dayparts = mapOf("a" to listOf(0, 0, 10, 0), "b" to listOf(0, 0, 0, 8), "c" to listOf(5, 0, 0, 0)),
        )
        val week = Wrapped.summarize(d, Range.WEEK, today)
        assertEquals("b", week.mostRepeated?.track?.id) // c repeats more but wasn't played this week
        assertEquals(4, week.mostRepeated?.repeats)
        // a's and b's buckets scaled to 2 plays each → evening 2.0 vs late night 2.0: the earlier part wins the tie.
        assertEquals(TimeOfDay.Part.EVENING, week.favouritePart)
        assertEquals(0.5, week.favouriteShare, 1e-9)
        val year = Wrapped.summarize(d, Range.YEAR, today)
        assertEquals("c", year.mostRepeated?.track?.id)
        assertEquals(TimeOfDay.Part.MORNING, year.favouritePart) // c: 5 morning plays beat a 2 / b 2
    }

    @Test fun noRepeatsAndNoBucketsHideThoseCards() {
        val a = t("a")
        val d = data(days = mapOf(today to mapOf("a" to 3)), stats = listOf(PlayStat(a, 3, repeats = 0)))
        val w = Wrapped.summarize(d, Range.WEEK, today)
        assertEquals(3, w.totalPlays)
        assertNull(w.mostRepeated)
        assertNull(w.favouritePart)
    }

    // ---- streak ----------------------------------------------------------------------------------------------------
    @Test fun streakCountsConsecutiveDaysUpToToday() {
        val days = (0L..4L).associate { today - it to mapOf("a" to 1) }
        assertEquals(5, Wrapped.streak(days, today))
    }

    @Test fun brokenStreakOnlyCountsTheDaysAfterTheGap() {
        val days = mapOf(today to mapOf("a" to 1), today - 1 to mapOf("a" to 1), /* gap at today-2 */ today - 3 to mapOf("a" to 1), today - 4 to mapOf("a" to 1))
        assertEquals(2, Wrapped.streak(days, today))
        // A day present with zero plays is a gap too.
        assertEquals(1, Wrapped.streak(mapOf(today to mapOf("a" to 1), today - 1 to mapOf("a" to 0), today - 2 to mapOf("a" to 1)), today))
    }

    @Test fun streakIsStillCurrentUntilTodayEndsAndZeroAfterAMissedDay() {
        val upToYesterday = mapOf(today - 1 to mapOf("a" to 1), today - 2 to mapOf("a" to 1))
        assertEquals(2, Wrapped.streak(upToYesterday, today))
        assertEquals(0, Wrapped.streak(mapOf(today - 2 to mapOf("a" to 1)), today))
        val d = data(days = upToYesterday, tracks = listOf(t("a")))
        assertEquals(2, Wrapped.summarize(d, Range.WEEK, today).currentStreak)
    }

    // ---- empty -----------------------------------------------------------------------------------------------------
    @Test fun emptyDataGivesAnEmptyResultForEveryRange() {
        Range.entries.forEach { r ->
            val s = Wrapped.summarize(PlayStatsData(), r, today)
            assertTrue(s.isEmpty)
            assertEquals(Wrapped.Summary(r), s)
        }
    }

    @Test fun rangeWithNoPlaysIsEmptyButKeepsTheStreak() {
        val d = data(days = mapOf(today - 200 to mapOf("a" to 4)), tracks = listOf(t("a")))
        val w = Wrapped.summarize(d, Range.WEEK, today)
        assertTrue(w.isEmpty)
        assertTrue(w.topSongs.isEmpty() && w.topArtists.isEmpty())
        assertEquals(0, w.listeningDays)
        assertEquals(4, Wrapped.summarize(d, Range.YEAR, today).totalPlays)
    }
}
