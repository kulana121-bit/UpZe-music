package com.ravi.musicspot.data

import com.ravi.musicspot.logic.ListenStats
import com.ravi.musicspot.logic.PlayStat
import com.ravi.musicspot.logic.TimeOfDay
import com.ravi.musicspot.model.Track
import kotlinx.serialization.Serializable
import java.util.TimeZone

@Serializable
data class PlayStatsData(
    val stats: Map<String, PlayStat> = emptyMap(),
    /** 1.0.2 Listen stats: plays per day (epoch day → track id → plays), for the 1 week … 1 year views. */
    val days: Map<Long, Map<String, Int>> = emptyMap(),
    /** Songs seen in [days] that dropped out of [stats] keep their details here. */
    val tracks: Map<String, Track> = emptyMap(),
    /** Set once [days] was seeded from listening history (older installs). */
    val seeded: Boolean = false,
    /**
     * 1.0.6 time-of-day mix: track id → counted plays per part of the day ([TimeOfDay.Part] order: morning, afternoon,
     * evening, late night). Kept only for songs in [stats]. Older files have none and start empty.
     */
    val dayparts: Map<String, List<Int>> = emptyMap(),
)

/**
 * 1.0.6: the play-count update as a pure function (was inline in PlayStatsRepo.count), so it is unit-tested without
 * Android or files. Every call is one counted play: repeat plays of the same song always increase plays (and
 * repeats when [repeat]), the per-day log and the time-of-day buckets. History deduplication never touches this.
 */
object PlayStatsOps {
    const val MAX = 2000
    const val MAX_DAYS = 800

    fun count(d: PlayStatsData, t: Track, repeat: Boolean, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): PlayStatsData {
        val cur = d.stats[t.id]
        val next = PlayStat(t, (cur?.plays ?: 0) + 1, (cur?.repeats ?: 0) + if (repeat) 1 else 0, nowMs)
        var map = d.stats + (t.id to next)
        if (map.size > MAX) map = map.entries.sortedByDescending { it.value.lastMs }.take(MAX).associate { it.key to it.value }
        val day = ListenStats.epochDay(nowMs, tz)
        val today = d.days[day].orEmpty()
        var days = d.days + (day to (today + (t.id to (today[t.id] ?: 0) + 1)))
        if (days.size > MAX_DAYS) days = days.toSortedMap().entries.toList().takeLast(MAX_DAYS).associate { it.key to it.value }
        val keep = days.values.flatMapTo(HashSet()) { it.keys }
        val part = TimeOfDay.partAt(nowMs, tz)
        var dayparts = d.dayparts + (t.id to TimeOfDay.increment(d.dayparts[t.id], part))
        if (dayparts.size > map.size) dayparts = dayparts.filterKeys { it in map }
        return d.copy(stats = map, days = days, tracks = (d.tracks + (t.id to t)).filterKeys { it in keep }, dayparts = dayparts)
    }

    /**
     * 1.0.2/1.0.6: older installs have no per-day log; seed it once from the listening history. Since 1.0.6 History
     * holds each song once with its [HistoryEntry.plays], so a song played three times still seeds three plays.
     */
    fun seed(d: PlayStatsData, history: List<HistoryEntry>, tz: TimeZone = TimeZone.getDefault()): PlayStatsData {
        if (d.seeded) return d
        if (d.days.isNotEmpty()) return d.copy(seeded = true)
        val days = HashMap<Long, MutableMap<String, Int>>()
        history.forEach { h ->
            val m = days.getOrPut(ListenStats.epochDay(h.atMs, tz)) { HashMap() }
            m[h.track.id] = (m[h.track.id] ?: 0) + h.plays.coerceAtLeast(1)
        }
        return d.copy(days = days, tracks = d.tracks + history.associate { it.track.id to it.track }, seeded = true)
    }
}
