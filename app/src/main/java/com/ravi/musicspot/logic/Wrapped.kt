package com.ravi.musicspot.logic

import com.ravi.musicspot.data.PlayStatsData
import com.ravi.musicspot.model.Track
import java.util.TimeZone

/**
 * Pack 5 (Nayi Jeet) "Wrapped anytime": one pure aggregation over [PlayStatsData] for the Your stats screen.
 * No Android, no I/O, no new tracking: it only reads what PlayStatsRepo.count already records for every source
 * (per-track plays/repeats, the per-day play log and the time-of-day buckets). Unit-tested in WrappedStatsTest.
 */
object Wrapped {
    enum class Range(val label: String, val days: Int?) { WEEK("Week", 7), MONTH("Month", 30), YEAR("Year", 365), ALL("All time", null) }

    data class SongCount(val track: Track, val plays: Int)
    data class ArtistCount(val name: String, val plays: Int, val artUrl: String?)
    /** [repeats] is the song's lifetime repeat count (the play log has no per-day repeats). */
    data class Repeated(val track: Track, val repeats: Int)

    data class Summary(
        val range: Range,
        val totalPlays: Int = 0,
        /** Days in the range with at least one counted play. */
        val listeningDays: Int = 0,
        /** Consecutive days with plays ending today (or yesterday, while today has none yet). Independent of [range]. */
        val currentStreak: Int = 0,
        val topSongs: List<SongCount> = emptyList(),
        val topArtists: List<ArtistCount> = emptyList(),
        val mostRepeated: Repeated? = null,
        /** The part of the day with the most plays of the songs played in the range; null without bucket data. */
        val favouritePart: TimeOfDay.Part? = null,
        /** Share (0..1) of [favouritePart] among the estimated plays per part; 0 when [favouritePart] is null. */
        val favouriteShare: Double = 0.0,
    ) {
        val isEmpty: Boolean get() = totalPlays == 0
    }

    const val TOP_N = 5

    fun summarize(d: PlayStatsData, range: Range, nowMs: Long, tz: TimeZone = TimeZone.getDefault(), limit: Int = TOP_N): Summary =
        summarize(d, range, ListenStats.epochDay(nowMs, tz), limit)

    /**
     * The whole Wrapped summary for [range], ending on [today] (local epoch day).
     *  - Week / Month / Year: plays from the per-day log for days today-N+1 .. today (future days ignored).
     *  - All time: the lifetime per-track counter, plus day-log plays of songs it no longer holds (as ListenStats).
     *  - Top songs: plays desc, then title, then id. Top artists: main artist (ListenStats.mainArtist), plays desc, name.
     *  - Most repeated: among songs played in the range, the highest lifetime repeat count (> 0); ties → more plays in range.
     *  - Favourite time of day: each song's time-of-day buckets, scaled to its plays in the range, summed; ties → earlier part.
     */
    fun summarize(d: PlayStatsData, range: Range, today: Long, limit: Int = TOP_N): Summary {
        val streak = streak(d.days, today)
        val counts = HashMap<String, Int>()
        val days: Map<Long, Map<String, Int>> = if (range.days == null) d.days else {
            val from = today - range.days + 1
            d.days.filterKeys { it in from..today }
        }
        if (range.days == null) {
            d.stats.forEach { (k, v) -> if (v.plays > 0) counts[k] = v.plays }
            d.days.values.forEach { m -> m.forEach { (k, n) -> if (k !in d.stats && n > 0) counts[k] = (counts[k] ?: 0) + n } }
        } else {
            days.values.forEach { m -> m.forEach { (k, n) -> if (n > 0) counts[k] = (counts[k] ?: 0) + n } }
        }
        val listeningDays = days.count { (_, m) -> m.values.any { it > 0 } }

        val track = HashMap<String, Track>(d.tracks)
        d.stats.forEach { (k, v) -> track[k] = v.track }
        val songs = counts.mapNotNull { (k, n) -> track[k]?.let { SongCount(it, n) } }
            .sortedWith(compareByDescending<SongCount> { it.plays }.thenBy { it.track.title.lowercase() }.thenBy { it.track.id })
        val total = songs.sumOf { it.plays }
        if (total == 0) return Summary(range, currentStreak = streak)

        val artists = songs.groupBy { ListenStats.mainArtist(it.track.artist) }
            .map { (name, rows) -> ArtistCount(name, rows.sumOf { it.plays }, rows.first().track.artUrl) } // rows keep song order: first = top song
            .sortedWith(compareByDescending<ArtistCount> { it.plays }.thenBy { it.name.lowercase() })

        val repeated = songs.mapNotNull { s -> d.stats[s.track.id]?.takeIf { it.repeats > 0 }?.let { Triple(s, it.repeats, s.plays) } }
            .sortedWith(compareByDescending<Triple<SongCount, Int, Int>> { it.second }.thenByDescending { it.third }.thenBy { it.first.track.title.lowercase() })
            .firstOrNull()?.let { Repeated(it.first.track, it.second) }

        val parts = DoubleArray(TimeOfDay.PARTS)
        songs.forEach { s ->
            val b = d.dayparts[s.track.id] ?: return@forEach
            val sum = b.take(TimeOfDay.PARTS).sumOf { it.coerceAtLeast(0) }
            if (sum <= 0) return@forEach
            val scale = s.plays.toDouble() / sum
            for (i in 0 until TimeOfDay.PARTS) parts[i] += (b.getOrNull(i) ?: 0).coerceAtLeast(0) * scale
        }
        val partTotal = parts.sum()
        var best = -1
        for (i in 0 until TimeOfDay.PARTS) if (parts[i] > 0 && (best < 0 || parts[i] > parts[best])) best = i

        return Summary(
            range = range,
            totalPlays = total,
            listeningDays = listeningDays,
            currentStreak = streak,
            topSongs = songs.take(limit),
            topArtists = artists.take(limit),
            mostRepeated = repeated,
            favouritePart = if (best >= 0) TimeOfDay.Part.entries[best] else null,
            favouriteShare = if (best >= 0 && partTotal > 0) parts[best] / partTotal else 0.0,
        )
    }

    /**
     * Consecutive days with at least one play, counting back from [today]. A streak that reached yesterday is still
     * current while today has no plays yet (the day isn't over). 0 when neither today nor yesterday has plays.
     */
    fun streak(days: Map<Long, Map<String, Int>>, today: Long): Int {
        fun played(day: Long) = days[day]?.values?.any { it > 0 } == true
        var day = if (played(today)) today else today - 1
        var n = 0
        while (played(day)) { n++; day-- }
        return n
    }
}
