package com.ravi.musicspot.data

import com.ravi.musicspot.model.Track
import kotlinx.serialization.Serializable

/**
 * One song in the user-facing History list.
 *
 * 1.0.6: History holds each song once. [atMs] is the latest time it was played and [plays] how many times it was
 * started from History's point of view (a song that becomes current). Old files have no [plays] field and read as 1.
 * Real listening counts (30 s rule, repeats, minutes) live in PlayStats, which still counts every repeat play.
 */
@Serializable
data class HistoryEntry(val track: Track, val atMs: Long, val source: String? = null, val plays: Int = 1)

/**
 * 1.0.6 History deduplication (pure, unit-tested in LogicTest/HistoryTest). No Android, no I/O.
 *
 * Bug fixed: playing the same song again inserted a second History row instead of moving the existing one to the top.
 * [upsert] removes the old row with the same canonical key and puts the updated row at index 0; [dedupe] cleans a
 * list that already contains duplicates (older installs), keeping the newest row per song.
 */
object HistoryOps {
    /** A replay of the top song within this window (a rebuffer, a seek back to 0, a source switch) is not a new play. */
    const val SAME_PLAY_WINDOW_MS = 30_000L

    /**
     * Canonical key of a song in History. Track ids are already "<source prefix>:<native id>" (e.g. "yt:dQw4w9WgXcQ"),
     * so the id alone identifies the source + source id. Native ids are case-sensitive (YouTube), so only surrounding
     * whitespace is normalised.
     */
    fun key(t: Track): String = t.id.trim()

    /**
     * The history after [t] was played at [atMs]: any older row of the same song is removed, and the new row is
     * inserted at index 0 with the new timestamp, the newest track metadata and plays + 1. Bounded to [max] rows.
     * A replay of the top song inside [SAME_PLAY_WINDOW_MS] returns [history] unchanged (same instance).
     */
    fun upsert(history: List<HistoryEntry>, t: Track, atMs: Long, source: String?, max: Int): List<HistoryEntry> {
        val k = key(t)
        val top = history.firstOrNull()
        if (top != null && key(top.track) == k && atMs - top.atMs in 0 until SAME_PLAY_WINDOW_MS) return history
        var prev: HistoryEntry? = null
        for (h in history) if (key(h.track) == k) { prev = h; break }
        val entry = HistoryEntry(t, atMs, source ?: prev?.source, (prev?.plays ?: 0) + 1)
        val cap = max.coerceAtLeast(1)
        val out = ArrayList<HistoryEntry>(minOf(history.size + 1, cap))
        out.add(entry)
        for (h in history) {
            if (out.size >= cap) break
            if (key(h.track) != k) out.add(h)
        }
        return out
    }

    /**
     * One row per song, newest first (stable for equal timestamps). The kept row is the most recent one; the plays of
     * the removed duplicates are added to it, so nothing that was counted is lost. Bounded to [max] rows.
     */
    fun dedupe(history: List<HistoryEntry>, max: Int = Int.MAX_VALUE): List<HistoryEntry> {
        if (history.isEmpty()) return history
        val sorted = history.sortedByDescending { it.atMs } // stable: equal timestamps keep their order
        val index = HashMap<String, Int>(sorted.size * 2)
        val out = ArrayList<HistoryEntry>(minOf(sorted.size, max.coerceAtLeast(1)))
        for (h in sorted) {
            val k = key(h.track)
            val at = index[k]
            if (at != null) {
                val kept = out[at]
                out[at] = kept.copy(plays = kept.plays + h.plays.coerceAtLeast(1), source = kept.source ?: h.source)
            } else if (out.size < max) {
                index[k] = out.size
                out.add(h.copy(plays = h.plays.coerceAtLeast(1)))
            }
        }
        return out
    }

    /** True when [history] holds each song once and is ordered newest first (no clean-up needed). */
    fun isClean(history: List<HistoryEntry>): Boolean {
        val seen = HashSet<String>(history.size * 2)
        var last = Long.MAX_VALUE
        for (h in history) {
            if (!seen.add(key(h.track)) || h.atMs > last || h.plays < 1) return false
            last = h.atMs
        }
        return true
    }

    /** Removes [trackId]'s row (History holds each song once). Same instance when the song isn't there. */
    fun remove(history: List<HistoryEntry>, trackId: String): List<HistoryEntry> {
        val k = trackId.trim()
        if (history.none { key(it.track) == k }) return history
        return history.filterNot { key(it.track) == k }
    }

    /**
     * Adds imported songs (YouTube Music history) that History doesn't know yet, with synthetic times an hour apart
     * below [nowMs], then returns the clean, newest-first list. Songs already in History keep their own row.
     */
    fun merge(history: List<HistoryEntry>, imported: List<Track>, nowMs: Long, source: String, max: Int): List<HistoryEntry> {
        val known = history.mapTo(HashSet()) { key(it.track) }
        val added = imported.filter { known.add(key(it)) }.mapIndexed { i, t -> HistoryEntry(t, nowMs - 3_600_000L * (i + 1), source) }
        return dedupe(history + added, max)
    }
}
