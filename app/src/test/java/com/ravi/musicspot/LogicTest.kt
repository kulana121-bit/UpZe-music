package com.ravi.musicspot

import com.ravi.musicspot.data.HistoryEntry
import com.ravi.musicspot.data.HistoryOps
import com.ravi.musicspot.data.PlayStatsData
import com.ravi.musicspot.data.PlayStatsOps
import com.ravi.musicspot.logic.QueueOps
import com.ravi.musicspot.logic.TimeOfDay
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.logic.RepeatMode
import com.ravi.musicspot.logic.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueOpsTest {
    private val q = listOf("a", "b", "c", "d", "e")
    private val id = q.indices.toList()

    @Test fun move() {
        assertEquals(listOf("b", "c", "a", "d", "e"), QueueOps.move(q, 0, 2))
        assertEquals(listOf("e", "a", "b", "c", "d"), QueueOps.move(q, 4, 0))
        assertEquals(q, QueueOps.move(q, 1, 1))
        assertEquals(q, QueueOps.move(q, -1, 2))
        assertEquals(q, QueueOps.move(q, 1, 9))
    }

    @Test fun currentFollowsMove() {
        // Check against the real list for every (current, from, to).
        for (cur in q.indices) for (from in q.indices) for (to in q.indices) {
            val moved = QueueOps.move(q, from, to)
            assertEquals("cur=$cur from=$from to=$to", q[cur], moved[QueueOps.currentAfterMove(cur, from, to)])
        }
    }

    @Test fun remove() {
        assertEquals(listOf("a", "c", "d", "e") to 1, QueueOps.remove(q, 2, 1))
        assertEquals(listOf("a", "b", "d", "e") to 2, QueueOps.remove(q, 2, 2)) // removing current → next takes its place
        assertEquals(listOf("a", "b", "c", "d") to 3, QueueOps.remove(q, 4, 4)) // removing last current → new last
        assertEquals(listOf("a", "b", "c", "e") to 2, QueueOps.remove(q, 2, 3))
        assertEquals(emptyList<String>() to -1, QueueOps.remove(listOf("x"), 0, 0))
        assertEquals(q to 2, QueueOps.remove(q, 2, 7))
    }

    @Test fun insertNext() {
        assertEquals(listOf("a", "b", "x", "y", "c", "d", "e"), QueueOps.insertNext(q, 1, listOf("x", "y")))
        assertEquals(listOf("x"), QueueOps.insertNext(emptyList(), -1, listOf("x")))
        assertEquals(q + "x", QueueOps.insertNext(q, 4, listOf("x")))
    }

    @Test fun nextIndex() {
        assertEquals(1, QueueOps.nextIndex(0, id, RepeatMode.OFF, false))
        assertNull(QueueOps.nextIndex(4, id, RepeatMode.OFF, false))
        assertEquals(0, QueueOps.nextIndex(4, id, RepeatMode.ALL, false))
        assertEquals(2, QueueOps.nextIndex(2, id, RepeatMode.ONE, false))
        assertEquals(3, QueueOps.nextIndex(2, id, RepeatMode.ONE, true)) // skip button leaves the repeated song
        assertEquals(0, QueueOps.nextIndex(4, id, RepeatMode.ONE, true))
        assertNull(QueueOps.nextIndex(0, emptyList(), RepeatMode.ALL, false))
        val shuffled = listOf(3, 0, 4, 1, 2)
        assertEquals(4, QueueOps.nextIndex(0, shuffled, RepeatMode.OFF, false))
        assertNull(QueueOps.nextIndex(2, shuffled, RepeatMode.OFF, false))
    }

    @Test fun previousIndex() {
        assertEquals(2, QueueOps.previousIndex(2, id, RepeatMode.OFF, 3_500)) // restart
        assertEquals(1, QueueOps.previousIndex(2, id, RepeatMode.OFF, 1_000))
        assertEquals(0, QueueOps.previousIndex(0, id, RepeatMode.OFF, 0))
        assertEquals(4, QueueOps.previousIndex(0, id, RepeatMode.ALL, 0))
    }

    @Test fun shuffleKeepsCurrentFirstAndIsPermutation() {
        repeat(50) { seed ->
            val o = QueueOps.shuffledOrder(10, 6, java.util.Random(seed.toLong()))
            assertEquals(6, o.first())
            assertEquals((0 until 10).toList(), o.sorted())
        }
        assertEquals(emptyList<Int>(), QueueOps.shuffledOrder(0, 0, java.util.Random(1)))
        assertEquals((0 until 4).toList(), QueueOps.shuffledOrder(4, -1, java.util.Random(1)).sorted())
    }

    @Test fun totalDuration() {
        assertEquals(9L, QueueOps.totalDurationMs(listOf(1, 2, 3, 4), 1))
        assertEquals(10L, QueueOps.totalDurationMs(listOf(1, 2, 3, 4), -3))
    }
}

class SleepTimerTest {
    @Test fun durationCountsDownAndExpires() {
        val s = SleepTimer(); s.startDuration(10 * 60_000, 1_000)
        assertEquals(10 * 60_000L, s.remainingMs(1_000))
        assertEquals(5 * 60_000L, s.remainingMs(1_000 + 5 * 60_000))
        assertEquals(0.5f, s.progress(1_000 + 5 * 60_000), 1e-4f)
        assertFalse(s.isExpired(1_000 + 10 * 60_000 - 1))
        assertTrue(s.isExpired(1_000 + 10 * 60_000))
        assertEquals(0L, s.remainingMs(10_000_000))
        assertTrue(s.shouldPause(1_000 + 10 * 60_000, trackRemainingMs = 120_000, isLastInQueue = false, finishSong = false))
    }

    @Test fun finishSongWaitsForTrackEnd() {
        val s = SleepTimer(); s.startDuration(60_000, 0)
        assertFalse(s.shouldPause(61_000, trackRemainingMs = 30_000, isLastInQueue = false, finishSong = true))
        assertTrue(s.shouldPause(91_000, trackRemainingMs = 400, isLastInQueue = false, finishSong = true))
    }

    @Test fun endOfTrackAndQueue() {
        val s = SleepTimer(); s.startEndOfTrack()
        assertFalse(s.shouldPause(0, 5_000, false, false))
        assertTrue(s.shouldPause(0, SleepTimer.END_GRACE_MS, false, false))
        s.startEndOfQueue()
        assertFalse(s.shouldPause(0, 100, isLastInQueue = false, finishSong = false))
        assertTrue(s.shouldPause(0, 100, isLastInQueue = true, finishSong = false))
    }

    @Test fun adjustNeverBelowOneMinute() {
        val s = SleepTimer(); s.startDuration(5 * 60_000, 0)
        s.adjust(-10 * 60_000, 0)
        assertEquals(60_000L, s.remainingMs(0))
        s.adjust(+15 * 60_000, 0)
        assertEquals(16 * 60_000L, s.remainingMs(0))
        assertEquals(16 * 60_000L, s.totalMs)
        assertEquals(0f, s.progress(0), 1e-6f)
    }

    @Test fun fadeAndCancel() {
        val s = SleepTimer(); s.startDuration(60_000, 0)
        assertEquals(1f, s.fadeFactor(0, 10_000), 0f)
        assertEquals(0.5f, s.fadeFactor(55_000, 10_000), 1e-4f)
        assertEquals(0f, s.fadeFactor(60_000, 10_000), 0f)
        s.cancel()
        assertEquals(SleepTimer.Mode.OFF, s.mode)
        assertFalse(s.shouldPause(1_000_000, 0, true, false))
        assertEquals(1f, s.fadeFactor(0, 10_000), 0f)
    }

    @Test fun adjustIgnoredWhenNotDuration() {
        val s = SleepTimer(); s.startEndOfTrack(); s.adjust(60_000, 0)
        assertEquals(0L, s.remainingMs(0))
    }

    @Test fun format() {
        assertEquals("05:00", SleepTimer.format(300_000))
        assertEquals("00:01", SleepTimer.format(1))
        assertEquals("1:00:00", SleepTimer.format(3_600_000))
    }

    @Test(expected = IllegalArgumentException::class) fun zeroDurationRejected() { SleepTimer().startDuration(0, 0) }
}

/**
 * 1.0.6 History duplicate fix: History holds each song once (upsert to the top), while PlayStats still counts every
 * repeat play for Taste / AiMix / Listen stats.
 */
class HistoryDedupTest {
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private val base = 1_800_000_000_000L
    private val song = Track("yt:dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", durationMs = 213_000)
    private val other = Track("js:AbC123", "Kesariya", "Arijit Singh", durationMs = 268_000)

    @Test fun sameTrackPlayedThreeTimesIsOneHistoryEntryWithLatestTimeAndThreePlays() {
        var history = emptyList<HistoryEntry>()
        var stats = PlayStatsData()
        val times = listOf(base, base + 5 * 60_000L, base + 10 * 60_000L)
        times.forEachIndexed { i, at ->
            history = HistoryOps.upsert(history, song, at, "YouTube Music", max = 1000)
            stats = PlayStatsOps.count(stats, song, repeat = i > 0, nowMs = at, tz = utc)
        }
        assertEquals(1, history.size)
        assertEquals(song.id, history[0].track.id)
        assertEquals(times.last(), history[0].atMs)
        assertEquals(3, history[0].plays)
        assertEquals(3, stats.stats.getValue(song.id).plays)
        assertEquals(2, stats.stats.getValue(song.id).repeats)
        assertEquals(times.last(), stats.stats.getValue(song.id).lastMs)
        assertEquals(3, stats.days.values.sumOf { it[song.id] ?: 0 })
    }

    @Test fun replayedSongMovesToTopAndOthersKeepOrder() {
        var h = emptyList<HistoryEntry>()
        h = HistoryOps.upsert(h, song, base, null, 1000)
        h = HistoryOps.upsert(h, other, base + 60_000, null, 1000)
        assertEquals(listOf(other.id, song.id), h.map { it.track.id })
        h = HistoryOps.upsert(h, song, base + 120_000, null, 1000)
        assertEquals(listOf(song.id, other.id), h.map { it.track.id })
        assertEquals(base + 120_000, h[0].atMs)
        assertTrue(HistoryOps.isClean(h))
    }

    @Test fun replayOfTopSongInsideWindowIsTheSamePlay() {
        val h1 = HistoryOps.upsert(emptyList(), song, base, null, 1000)
        val h2 = HistoryOps.upsert(h1, song, base + 10_000, null, 1000)
        assertTrue(h1 === h2)
    }

    @Test fun existingDuplicatesAreCleanedOnLoadKeepingNewest() {
        val dirty = listOf(
            HistoryEntry(song, base + 3_000_000, "YouTube Music"),
            HistoryEntry(other, base + 2_000_000),
            HistoryEntry(song, base + 1_000_000),
            HistoryEntry(other, base + 4_000_000),
            HistoryEntry(song, base),
        )
        assertFalse(HistoryOps.isClean(dirty))
        val clean = HistoryOps.dedupe(dirty)
        assertEquals(listOf(other.id, song.id), clean.map { it.track.id })
        assertEquals(base + 4_000_000, clean[0].atMs)
        assertEquals(base + 3_000_000, clean[1].atMs)
        assertEquals(2, clean[0].plays)
        assertEquals(3, clean[1].plays)
        assertTrue(HistoryOps.isClean(clean))
    }

    @Test fun historyIsBounded() {
        var h = emptyList<HistoryEntry>()
        repeat(10) { i -> h = HistoryOps.upsert(h, Track("yt:$i", "S$i", "A"), base + i * 60_000L, null, max = 5) }
        assertEquals(5, h.size)
        assertEquals("yt:9", h[0].track.id)
    }

    @Test fun removeDropsTheSong() {
        val h = HistoryOps.dedupe(listOf(HistoryEntry(song, base), HistoryEntry(other, base + 1)))
        assertEquals(listOf(other.id), HistoryOps.remove(h, song.id).map { it.track.id })
        assertTrue(HistoryOps.remove(h, "yt:nothing") === h)
    }

    @Test fun mergeKeepsKnownRowsAndAddsNewOnes() {
        val h = listOf(HistoryEntry(song, base))
        val m = HistoryOps.merge(h, listOf(song, other), base + 10_000_000, "YouTube Music history", 1000)
        assertEquals(2, m.size)
        assertEquals(1, m.count { it.track.id == song.id })
        assertTrue(HistoryOps.isClean(m))
    }

    @Test fun seedingFromDedupedHistoryKeepsEveryPlay() {
        val h = HistoryOps.dedupe(listOf(HistoryEntry(song, base), HistoryEntry(song, base + 1), HistoryEntry(song, base + 2)))
        val d = PlayStatsOps.seed(PlayStatsData(), h, utc)
        assertEquals(3, d.days.values.sumOf { it[song.id] ?: 0 })
        assertTrue(d.seeded)
    }
}

/** 1.0.6 on-device time-of-day mix. */
class TimeOfDayTest {
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private val day0 = 1_799_971_200_000L // a UTC midnight

    @Test fun partsOfTheDay() {
        assertEquals(TimeOfDay.Part.NIGHT, TimeOfDay.partOfHour(4))
        assertEquals(TimeOfDay.Part.MORNING, TimeOfDay.partOfHour(5))
        assertEquals(TimeOfDay.Part.AFTERNOON, TimeOfDay.partOfHour(12))
        assertEquals(TimeOfDay.Part.EVENING, TimeOfDay.partOfHour(17))
        assertEquals(TimeOfDay.Part.NIGHT, TimeOfDay.partOfHour(22))
        assertEquals(TimeOfDay.Part.MORNING, TimeOfDay.partAt(day0 + 8 * 3_600_000L, utc))
    }

    @Test fun countsLandInTheirPartAndAffinityFollows() {
        val morning = Track("yt:m", "Wake Up", "Sunrise")
        val night = Track("yt:n", "Slow Rain", "Lofi Kid")
        var d = PlayStatsData()
        repeat(4) { d = PlayStatsOps.count(d, morning, false, day0 + 8 * 3_600_000L + it * 86_400_000L, utc) }
        repeat(4) { d = PlayStatsOps.count(d, night, false, day0 + 23 * 3_600_000L + it * 86_400_000L, utc) }
        assertEquals(listOf(4, 0, 0, 0), d.dayparts.getValue(morning.id))
        assertEquals(listOf(0, 0, 0, 4), d.dayparts.getValue(night.id))
        assertTrue(TimeOfDay.affinity(d.dayparts[morning.id], TimeOfDay.Part.MORNING) > 1.0)
        assertTrue(TimeOfDay.affinity(d.dayparts[morning.id], TimeOfDay.Part.NIGHT) < 1.0)
        assertEquals(1.0, TimeOfDay.affinity(null, TimeOfDay.Part.NIGHT), 0.0)
        val now = day0 + 5 * 86_400_000L
        assertEquals(listOf(morning.id), TimeOfDay.picksFor(d.stats.values, d.dayparts, TimeOfDay.Part.MORNING, now).map { it.id })
        assertEquals(listOf(night.id), TimeOfDay.picksFor(d.stats.values, d.dayparts, TimeOfDay.Part.NIGHT, now).map { it.id })
    }
}

/** 1.0.6 equal-power crossfade and the 150 ms play/pause ramp. */
class FadesTest {
    @Test fun equalPowerKeepsConstantPower() {
        for (i in 0..20) {
            val t = i / 20f
            val p = com.ravi.musicspot.logic.Fades.rise(t).let { it * it } + com.ravi.musicspot.logic.Fades.fall(t).let { it * it }
            assertEquals(1f, p, 1e-5f)
        }
    }

    @Test fun crossfadeShape() {
        val f = com.ravi.musicspot.logic.Fades
        assertEquals(1f, f.crossfadeGain(60_000, 120_000, 180_000, 6_000), 0f)  // middle of the song
        assertEquals(1f, f.crossfadeGain(0, 10_000, 10_000, 6_000), 0f)         // too short to fade
        assertEquals(1f, f.crossfadeGain(60_000, 120_000, 180_000, 0), 0f)      // crossfade off
        assertEquals(0f, f.crossfadeGain(179_999, 0, 180_000, 6_000), 1e-4f)    // the very end is silent
        assertEquals(f.rise(0.5f), f.crossfadeGain(100_000, 3_000, 180_000, 6_000), 1e-5f)
        assertEquals(f.FADE_IN_FLOOR, f.crossfadeGain(0, 180_000, 180_000, 6_000), 1e-6f)
        assertTrue(f.inCrossfade(1_000, 170_000, 180_000, 6_000))
        assertFalse(f.inCrossfade(60_000, 120_000, 180_000, 6_000))
    }

    @Test fun rampsStartWhereTheyAreAndEndAtTarget() {
        val f = com.ravi.musicspot.logic.Fades
        assertEquals(0f, f.rampUp(0f, 0), 0f)
        assertEquals(1f, f.rampUp(0f, f.RAMP_MS), 1e-6f)
        assertEquals(0.4f, f.rampUp(0.4f, 0), 1e-6f)
        assertEquals(1f, f.rampDown(1f, 0), 1e-6f)
        assertEquals(0f, f.rampDown(1f, f.RAMP_MS), 1e-6f)
        var last = 2f
        for (ms in 0..f.RAMP_MS step 10) { val g = f.rampDown(1f, ms); assertTrue(g <= last); last = g }
    }
}

/** 1.0.7: glass grade per device tier (Settings value unchanged; lite devices render Water instead of a sampled blur). */
class GlassGradeTest {
    private fun g(s: com.ravi.musicspot.data.GlassStyle, low: Boolean, lite: Boolean) = com.ravi.musicspot.ui.glass.effectiveGlass(s, low, lite)

    @org.junit.Test fun capableDeviceKeepsTheChosenGrade() {
        for (s in com.ravi.musicspot.data.GlassStyle.entries) org.junit.Assert.assertEquals(s, g(s, low = false, lite = false))
    }

    @org.junit.Test fun liteDeviceSwapsBlurGradesForWater() {
        org.junit.Assert.assertEquals(com.ravi.musicspot.data.GlassStyle.WATER, g(com.ravi.musicspot.data.GlassStyle.LIQUID, low = false, lite = true))
        org.junit.Assert.assertEquals(com.ravi.musicspot.data.GlassStyle.WATER, g(com.ravi.musicspot.data.GlassStyle.FROSTED, low = false, lite = true))
        org.junit.Assert.assertEquals(com.ravi.musicspot.data.GlassStyle.WATER, g(com.ravi.musicspot.data.GlassStyle.WATER, low = false, lite = true))
        org.junit.Assert.assertEquals(com.ravi.musicspot.data.GlassStyle.SOLID, g(com.ravi.musicspot.data.GlassStyle.SOLID, low = false, lite = true))
    }

    @org.junit.Test fun lowEndIsAlwaysSolid() {
        for (s in com.ravi.musicspot.data.GlassStyle.entries) org.junit.Assert.assertEquals(com.ravi.musicspot.data.GlassStyle.SOLID, g(s, low = true, lite = true))
    }
}

/** 1.0.7: a Spotify import must never store an id twice (duplicate Lazy keys crashed Liked Songs / playlists). */
class LibraryCleanTest {
    private fun t(id: String, title: String = id) = Track(id, title, "A")

    @Test fun mergeKeepsExistingOrderAndAddsOnlyNewIdsOnce() {
        val out = com.ravi.musicspot.data.LibraryClean.mergeTracks(listOf(t("a"), t("b")), listOf(t("b", "other"), t("c"), t("c"), t("a")))
        assertEquals(listOf("a", "b", "c"), out.map { it.id })
        assertEquals("b", out[1].title) // the copy already in the library wins
    }

    @Test fun cleanRemovesRepeatsInLikedAndInsidePlaylists() {
        val d = com.ravi.musicspot.data.LibraryData(
            liked = listOf(t("x"), t("y"), t("x")),
            playlists = listOf(com.ravi.musicspot.data.LocalPlaylist("p1", "P", listOf(t("s"), t("s"), t("u")), origin = "spotify:1")),
        )
        val c = com.ravi.musicspot.data.LibraryClean.clean(d)
        assertEquals(listOf("x", "y"), c.liked.map { it.id })
        assertEquals(listOf("s", "u"), c.playlists.single().tracks.map { it.id })
        assertTrue(com.ravi.musicspot.data.LibraryClean.isClean(c))
    }

    @Test fun cleanLibraryIsReturnedAsIs() {
        val d = com.ravi.musicspot.data.LibraryData(liked = listOf(t("a"), t("b")))
        assertTrue(com.ravi.musicspot.data.LibraryClean.clean(d) === d)
    }
}

/** 1.0.7: source order, lyrics preference, lossless upgrade rules and the updater's same-build check. */
class OrderAndUpgradeTest {
    @Test fun lossless_first_jiosaavn_second_last_youtube_last() {
        val p = com.ravi.musicspot.data.AppSettings.DEFAULT_PRIORITY
        assertEquals(com.ravi.musicspot.model.SourceKind.YOUTUBE, p.last())
        assertEquals(com.ravi.musicspot.model.SourceKind.JIOSAAVN, p[p.size - 2])
        val lossless = com.ravi.musicspot.source.QualityEstimate.LOSSLESS_SOURCES
        assertTrue(p.take(lossless.size).toSet() == lossless.toSet())
    }

    @Test fun word_beats_line_beats_plain_in_any_order() {
        val word = com.ravi.musicspot.lyrics.Lyrics.Synced(listOf(com.ravi.musicspot.lyrics.LyricLine(0, 1000, "a b", emptyList(), wordSynced = true)), "W")
        val line = com.ravi.musicspot.lyrics.Lyrics.Synced(listOf(com.ravi.musicspot.lyrics.LyricLine(0, 1000, "a b", emptyList(), wordSynced = false)), "L")
        val plain = com.ravi.musicspot.lyrics.Lyrics.Plain("a b", "P")
        val R = com.ravi.musicspot.lyrics.LyricsRank
        assertTrue(R.best(listOf(plain, line, null, word)) === word)
        assertTrue(R.best(listOf(plain, null, line)) === line)
        assertTrue(R.best(listOf(null, plain)) === plain)
        assertNull(R.best(listOf(null, null)))
    }

    @Test fun lossless_detection_and_cache_bypass() {
        val Q = com.ravi.musicspot.source.QualityEstimate
        assertTrue(Q.isLosslessStream(com.ravi.musicspot.model.StreamInfo(com.ravi.musicspot.model.SourceKind.QOBUZ, "FLAC", 1411)))
        assertFalse(Q.isLosslessStream(com.ravi.musicspot.model.StreamInfo(com.ravi.musicspot.model.SourceKind.YOUTUBE, "opus", 160)))
        assertFalse(Q.isLosslessStream(com.ravi.musicspot.model.StreamInfo(com.ravi.musicspot.model.SourceKind.JIOSAAVN, "AAC", 320)))
        val lossy = com.ravi.musicspot.model.StreamInfo(com.ravi.musicspot.model.SourceKind.YOUTUBE, "opus", 160)
        val U = com.ravi.musicspot.source.LosslessUpgrade
        assertTrue(U.bypassCache(lossy, online = true, upgradeWanted = true))
        assertFalse(U.bypassCache(lossy, online = false, upgradeWanted = true))
        assertFalse(U.bypassCache(lossy, online = true, upgradeWanted = false))
        assertFalse(U.bypassCache(com.ravi.musicspot.model.StreamInfo(com.ravi.musicspot.model.SourceKind.MONOCHROME, "FLAC", 1000), online = true, upgradeWanted = true))
    }

    @Test fun updater_recognises_the_installed_build() {
        val rel = com.ravi.musicspot.update.UpdateLogic.Release("v1.01", "1.01", "UpZe v1.01", "", "https://x/a.apk", "a.apk", 7_148_003, null, "ab".repeat(32))
        val U = com.ravi.musicspot.update.UpdateLogic
        assertTrue(U.isNewer("1.01", "v1")) // the tag alone says "newer" (the old bug)
        assertTrue(U.isInstalledBuild(rel, "AB".repeat(32), 1))
        assertFalse(U.isInstalledBuild(rel, "cd".repeat(32), 7_148_003))
        assertTrue(U.isInstalledBuild(rel.copy(apkSha256 = null), null, 7_148_003))
        assertFalse(U.isUpgrade(31, 31)); assertTrue(U.isUpgrade(32, 31)); assertTrue(U.isUpgrade(null, 31))
    }
}

/** 1.0.7: dynamic colour picks the cover's big colourful colour, not a speck, and keeps it readable. */
class ArtThemeTest {
    private val A = com.ravi.musicspot.logic.ArtTheme
    @Test fun big_colourful_beats_tiny_vibrant_speck_and_grey() {
        val blue = 0xFF1F5FD0.toInt(); val pinkSpeck = 0xFFFF2DA0.toInt(); val grey = 0xFF808080.toInt()
        val p = A.pick(listOf(blue to 5000, pinkSpeck to 30, grey to 6000))!!
        assertEquals(blue, p.accent); assertEquals(grey, p.base); assertTrue(p.vivid)
    }
    @Test fun greyscale_cover_stays_neutral() {
        val p = A.pick(listOf(0xFF202020.toInt() to 900, 0xFFB0B0B0.toInt() to 800))!!
        assertFalse(p.vivid)
        val t = A.tones(p, dark = true, pureBlack = false)
        assertTrue(A.chroma(t.bg) < 0.05f)
    }
    @Test fun surfaces_keep_the_art_hue_and_text_reads() {
        val red = 0xFFC0302A.toInt()
        val p = A.pick(listOf(red to 1000))!!
        for (dark in listOf(true, false)) {
            val t = A.tones(p, dark, false)
            val h = com.ravi.musicspot.logic.Contrast.toHsl(t.s2)
            assertTrue(h[0] < 20f || h[0] > 340f) // still red-family
            assertTrue(h[1] >= 0.15f)              // not flattened to grey
        }
        assertNull(A.pick(emptyList()))
    }
}
