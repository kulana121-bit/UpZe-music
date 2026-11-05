package com.ravi.musicspot.logic

import com.ravi.musicspot.model.Track
import kotlinx.serialization.Serializable
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln

/** How often one song was really listened to (v2.0.5). Persisted by PlayStatsRepo. */
@Serializable
data class PlayStat(val track: Track, val plays: Int = 0, val repeats: Int = 0, val lastMs: Long = 0)

/**
 * Pure taste logic (v2.0.5), unit-tested in TasteTest: the "On repeat" Home ranking, the user's artist weights and the
 * autoplay re-rank/dedupe. No Android, no I/O.
 */
object Taste {
    const val LIKED_BONUS = 3.0
    const val HALF_LIFE_DAYS = 21.0
    private const val DAY = 86_400_000.0

    /** A play counts after this much listening, or half the song when it is shorter than twice this. */
    const val COUNT_AFTER_MS = 30_000L
    fun countThresholdMs(durationMs: Long): Long = if (durationMs in 1 until COUNT_AFTER_MS * 2) durationMs / 2 else COUNT_AFTER_MS

    /** 1.0 now, 0.5 after [HALF_LIFE_DAYS], and so on. */
    fun decay(lastMs: Long, nowMs: Long): Double = exp(-ln(2.0) * ((nowMs - lastMs).coerceAtLeast(0) / DAY) / HALF_LIFE_DAYS)

    fun score(s: PlayStat?, liked: Boolean, likedAtMs: Long, nowMs: Long): Double {
        val base = (s?.plays ?: 0) + 2.0 * (s?.repeats ?: 0) + if (liked) LIKED_BONUS else 0.0
        val last = maxOf(s?.lastMs ?: 0L, if (liked) likedAtMs else 0L)
        return base * decay(last, nowMs)
    }

    /**
     * "On repeat": songs played most, repeated, or liked. Score = plays + repeats×2 + liked bonus, decayed by recency.
     * A song needs 2+ plays, a repeat, or a like to qualify. [likedAtMs] is when likes are assumed to be fresh
     * (liked lists carry no date; the caller passes "now" so a like alone ranks just above a single play).
     */
    fun favourites(stats: Collection<PlayStat>, liked: List<Track>, nowMs: Long, likedAtMs: Long = nowMs, limit: Int = 20): List<Track> {
        val likedIds = liked.mapTo(HashSet()) { it.id }
        val byId = stats.associateBy { it.track.id }
        val ids = (byId.keys + likedIds)
        return ids.mapNotNull { id ->
            val s = byId[id]; val isLiked = id in likedIds
            if (!isLiked && (s == null || (s.plays < 2 && s.repeats == 0))) return@mapNotNull null
            val t = s?.track ?: liked.first { it.id == id }
            t to score(s, isLiked, likedAtMs, nowMs)
        }.sortedWith(compareByDescending<Pair<Track, Double>> { it.second }.thenBy { it.first.title })
            .map { it.first }.distinctBy { key(it) }.take(limit)
    }

    /** Artist → weight (decayed plays + 2× repeats, +3 per liked song). Artist names normalised. */
    fun artistWeights(stats: Collection<PlayStat>, liked: List<Track>, nowMs: Long): Map<String, Double> {
        val w = HashMap<String, Double>()
        stats.forEach { s -> artists(s.track).forEach { a -> w[a] = (w[a] ?: 0.0) + (s.plays + 2.0 * s.repeats) * decay(s.lastMs, nowMs) } }
        liked.forEach { t -> artists(t).forEach { a -> w[a] = (w[a] ?: 0.0) + LIKED_BONUS } }
        return w
    }

    /**
     * Autoplay: dedupes [candidates] against the queue, recent history and each other (same id, or same title +
     * main artist), then re-ranks: the recommender's own order is the base (1.0 → 0.0), boosted up to +0.8 for
     * artists the user plays or likes and +0.5 for a liked song. Stable for equal scores. Returns at most [n].
     */
    fun autoplayMix(
        candidates: List<Track>, queue: List<Track>, recent: List<Track>,
        artistWeight: Map<String, Double>, likedIds: Set<String>, n: Int = 10,
    ): List<Track> {
        val seenIds = HashSet<String>(); val seenKeys = HashSet<String>()
        (queue + recent).forEach { seenIds += it.id; seenKeys += key(it) }
        val unique = candidates.filter { t -> t.title.isNotBlank() && seenIds.add(t.id) && seenKeys.add(key(t)) }
        if (unique.isEmpty()) return emptyList()
        val maxW = artistWeight.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return unique.mapIndexed { i, t ->
            val base = 1.0 - i.toDouble() / unique.size
            val aw = artists(t).maxOfOrNull { artistWeight[it] ?: 0.0 } ?: 0.0
            t to (base + 0.8 * (aw / maxW) + if (t.id in likedIds) 0.5 else 0.0)
        }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    fun artists(t: Track): List<String> = t.artist.split(',', '&', '·').map { norm(it) }.filter { it.isNotBlank() }.ifEmpty { listOf(norm(t.artist)) }

    /** Same recording across sources: normalised title (brackets/"official" noise dropped) + first artist. */
    fun key(t: Track): String {
        val stripped = t.title.replace(Regex("\\(.*?\\)|\\[.*?]"), " ").substringBefore(" | ")
        val title = norm(stripped.replace(Regex("(?i)\\s-\\s*official.*$"), ""))
        return "$title|${artists(t).firstOrNull().orEmpty()}"
    }

    private fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKC).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
}

/**
 * v2.0.5 automatic replay cap. Ravi's log showed ~8 automatic replays in a loop. Now: replay 1 re-resolves (the
 * resolver moves on to the next client), replay 2 re-resolves without YouTube when YouTube refused the stream, then
 * the error stays on screen. Counted per track; reset when another track becomes current.
 */
class ReplayPolicy(private val max: Int = MAX_AUTO_REPLAYS) {
    enum class Decision { REPLAY, REPLAY_WITHOUT_YOUTUBE, STOP }
    private val counts = HashMap<String, Int>()

    @Synchronized fun onError(trackId: String, youTubeRefused: Boolean, online: Boolean = true): Decision {
        if (!online) return Decision.STOP
        val n = (counts[trackId] ?: 0) + 1
        if (n > max) return Decision.STOP
        counts[trackId] = n
        return if (n == max && youTubeRefused) Decision.REPLAY_WITHOUT_YOUTUBE else Decision.REPLAY
    }
    @Synchronized fun replays(trackId: String): Int = counts[trackId] ?: 0
    @Synchronized fun keepOnly(trackId: String?) { counts.keys.retainAll(setOfNotNull(trackId)) }

    companion object { const val MAX_AUTO_REPLAYS = 2 }
}
