package com.ravi.musicspot.source

import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import com.ravi.musicspot.core.PlaybackLog

/**
 * Picks where a track actually plays from: downloads first, then the ordered plan from [SourceRouter]
 * (user priority or best quality), with failover to the next candidate when one fails.
 */
class SourceManager(
    val sources: Map<SourceKind, MusicSource>,
    private val priority: () -> List<SourceKind>,
    private val enabled: () -> Set<SourceKind>,
    private val premium: () -> Boolean,
    private val downloaded: (String) -> Resolved?,
    /** HTTP status of a tiny ranged GET of a fallback stream (null = not probed). Diagnostics + early 4xx detection. */
    private val streamProbe: (suspend (Resolved) -> Int?)? = null,
) {
    /** Bounded LRU of cross-source matches (never grows without limit; fixes OpenTune B3-style leaks). */
    private val matchCache: MutableMap<String, List<Track>> = Collections.synchronizedMap(object : LinkedHashMap<String, List<Track>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Track>>?) = size > 300
    })

    fun source(kind: SourceKind) = sources[kind]
    fun enabledKinds(): Set<SourceKind> = enabled().filter { sources[it]?.available() == true }.toSet() +
        (if (sources[SourceKind.LOCAL]?.available() == true) setOf(SourceKind.LOCAL) else emptySet())

    /** Same-recording matches on other sources (only where they could improve on or replace the original). */
    suspend fun matchesFor(t: Track, quality: Quality): List<Track> {
        val kinds = SourceRouter.sourcesToSearch(t, priority(), enabledKinds(), quality, premium())
        if (kinds.isEmpty()) return emptyList()
        val key = t.id + "|" + kinds.joinToString(",")
        matchCache[key]?.let { return it }
        val q = "${t.title} ${t.artist.substringBefore(',')}"
        val found = coroutineScope {
            kinds.map { k ->
                async {
                    try { withTimeoutOrNull(4_000) { sources[k]?.search(q, 8) }.orEmpty() }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }
            }.awaitAll().mapNotNull { TrackMatcher.best(t, it) }
        }
        matchCache[key] = found
        return found
    }

    /**
     * Resolve with failover. [onFailure] gets each candidate that failed (shown in the player as a source switch).
     * [exclude] drops sources for this track (playback excludes YouTube after repeated 403s). When YouTube failed or
     * is excluded, every other enabled source is searched via [TrackMatcher] (lenient) regardless of priority
     * (JioSaavn first), and the result carries `fallbackFrom = YOUTUBE`.
     *
     * v2.0.4: YouTube gets its own budget ([YT_BUDGET_MS]); the fallback then gets a fresh one (search
     * [FALLBACK_SEARCH_MS] + resolve [FALLBACK_RESOLVE_MS] per candidate), so a slow YouTube walk can no longer eat
     * the fallback's time. Every step is written to [PlaybackLog] and the per-source summary is thrown as the error.
     */
    suspend fun resolve(
        t: Track, quality: Quality, metered: Boolean,
        exclude: Set<SourceKind> = emptySet(),
        onFailure: (Track, Throwable) -> Unit = { _, _ -> },
    ): Resolved = coroutineScope {
        downloaded(t.id)?.let { return@coroutineScope it }
        val usable = enabledKinds() - exclude
        PlaybackLog.log("Sources: enabled=${usable.joinToString { it.label }}${if (exclude.isNotEmpty()) " excluded=${exclude.joinToString { it.label }}" else ""} (metered=$metered)")
        val matches = if (t.source.playable && quality == Quality.HIGH && t.source in usable) emptyList() else
            runCatching { matchesFor(t, quality) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        val plan = SourceRouter.plan(t, matches, priority(), usable, quality, premium())
        val ytOut = SourceKind.YOUTUBE in exclude && (t.source == SourceKind.YOUTUBE || plan.isEmpty())
        if (ytOut) { PlaybackLog.step("YouTube", "excluded after repeated refusals"); PlaybackLog.log("YouTube excluded for this track") }
        if (plan.isEmpty() && !ytOut) throw SourceUnavailableException(
            if (t.source == SourceKind.SPOTIFY) "No playable match for this Spotify song on your enabled sources" else "${t.source.label} is turned off in Settings → Sources")
        PlaybackLog.log("Plan: ${plan.joinToString { it.source.label }}")
        var last: Throwable? = null
        var ytFailed = ytOut
        val tried = mutableSetOf<SourceKind>()
        val fbKinds = fallbackKinds(t, usable, tried + plan.filter { it.source != SourceKind.YOUTUBE }.map { it.source }.toSet())
        if (SourceKind.SOUNDCLOUD in usable) PlaybackLog.log("Fallback: SoundCloud left out of the automatic fallback (NewPipe client id discovery is unreliable)")
        if (SourceKind.JIOSAAVN !in usable) { PlaybackLog.step("JioSaavn", "turned off in Settings"); PlaybackLog.log("Fallback: JioSaavn is disabled in Settings → Sources") }
        // v2.0.5 race: the fallback search starts together with YouTube, so its answer is ready when YouTube is slow or fails.
        var fallback: Deferred<List<Track>>? = null
        fun startFallback(kinds: List<SourceKind>): Deferred<List<Track>> = fallback ?: async { fallbackSearch(t, kinds) }.also { fallback = it }
        for (cand in plan) {
            downloaded(cand.id)?.let { return@coroutineScope it }
            val src = sources[cand.source] ?: continue
            tried += cand.source
            val budget = if (cand.source == SourceKind.YOUTUBE) YT_BUDGET_MS else OTHER_BUDGET_MS
            try {
                val r = (if (cand.source == SourceKind.YOUTUBE && fbKinds.isNotEmpty()) {
                    val fb = startFallback(fbKinds)
                    raceYouTube(cand, src, quality, metered, budget, fb)
                } else withTimeoutOrNull(budget) { src.resolve(cand, quality, metered) })
                    ?: run { PlaybackLog.step(cand.source.label, "timed out"); throw SourceUnavailableException("${cand.source.label} timed out after ${budget / 1000} s") }
                PlaybackLog.log("${r.info.source.label}: resolved (${r.info.codec ?: "?"})")
                fallback?.cancel()
                return@coroutineScope r
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                last = e; onFailure(cand, e)
                PlaybackLog.log("${cand.source.label} failed: ${e.javaClass.simpleName}: ${e.message}")
                if (cand.source != SourceKind.YOUTUBE) PlaybackLog.step(cand.source.label, e.message?.take(60) ?: "failed")
                if (cand.source == SourceKind.YOUTUBE) ytFailed = true
            }
        }
        if (ytFailed) {
            // YouTube exhausted: every other enabled source, priority notwithstanding, with a fresh time budget.
            val kinds = fallbackKinds(t, usable, tried)
            val found = (fallback ?: if (kinds.isNotEmpty()) startFallback(kinds) else null)?.await().orEmpty().filter { it.source !in tried || it.source == SourceKind.JIOSAAVN }
            for (cand in found) {
                try { return@coroutineScope resolveFallback(cand, quality, metered) }
                catch (e: CancellationException) { throw e } catch (e: Exception) {
                    last = e; onFailure(cand, e)
                    PlaybackLog.log("Fallback ${cand.source.label} failed: ${e.javaClass.simpleName}: ${e.message}")
                    PlaybackLog.step(cand.source.label, e.message?.take(60) ?: "stream failed")
                }
            }
            if (last == null) last = SourceUnavailableException("YouTube can't play this track and no other enabled source has it")
        }
        fallback?.cancel()
        val summary = PlaybackLog.attempt()?.summary().orEmpty()
        val err = last ?: SourceUnavailableException("No source could play this track")
        if (summary.isNotBlank()) throw SourceUnavailableException(summary, err)
        throw err
    }

    private fun fallbackKinds(t: Track, usable: Set<SourceKind>, tried: Set<SourceKind>): List<SourceKind> =
        (listOf(SourceKind.JIOSAAVN) + priority() + sources.keys).distinct()
            .filter { it != SourceKind.YOUTUBE && it in usable && it !in tried && it in FALLBACK_SEARCHABLE && it != t.source }

    /**
     * YouTube against the head-start fallback: YouTube alone for [RACE_AFTER_MS]; after that, a fallback match that
     * resolves first wins (YouTube is cancelled), otherwise YouTube keeps its full [budget]. A YouTube failure is
     * rethrown so the caller's failover logs it and uses the fallback.
     */
    private suspend fun raceYouTube(cand: Track, src: MusicSource, quality: Quality, metered: Boolean, budget: Long, fb: Deferred<List<Track>>): Resolved? = coroutineScope {
        val started = System.currentTimeMillis()
        val yt = async { withTimeoutOrNull(budget) { src.resolve(cand, quality, metered) } }
        withTimeoutOrNull(RACE_AFTER_MS) { yt.join() }
        if (yt.isCompleted) return@coroutineScope yt.await()
        // YouTube is slow: resolve the fallback match (if any) while YouTube keeps going; first stream wins.
        val alt = async {
            val found = try { fb.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            for (c in found) {
                try { return@async resolveFallback(c, quality, metered) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            null
        }
        while (true) {
            if (yt.isCompleted) {
                val r = try { yt.await() } catch (e: CancellationException) { throw e } catch (e: Exception) { alt.cancel(); throw e }
                if (r != null) { alt.cancel(); return@coroutineScope r }
                alt.cancel(); return@coroutineScope null
            }
            if (alt.isCompleted) {
                val r = alt.await()
                if (r != null) {
                    yt.cancel()
                    PlaybackLog.log("Race: YouTube had no stream after ${(System.currentTimeMillis() - started) / 1000} s; ${r.info.source.label} was ready first")
                    PlaybackLog.step("YouTube", "slower than ${r.info.source.label}")
                    return@coroutineScope r
                }
                return@coroutineScope yt.await()
            }
            kotlinx.coroutines.delay(50)
        }
        null
    }

    /**
     * Searches [kinds] in parallel, each with every query in parallel, within [FALLBACK_SEARCH_MS]; returns the best
     * lenient match per source (JioSaavn first). Logs results, matches and timeouts.
     */
    private suspend fun fallbackSearch(t: Track, kinds: List<SourceKind>): List<Track> = coroutineScope {
        val queries = TrackMatcher.searchQueries(t)
        PlaybackLog.log("Fallback: searching ${kinds.joinToString { it.label }.ifEmpty { "(no other enabled source)" }} for ${queries.joinToString(" | ") { "\"$it\"" }}")
        kinds.map { k ->
            async {
                val t0 = System.currentTimeMillis()
                try {
                    withTimeoutOrNull(FALLBACK_SEARCH_MS) {
                        val results = queries.map { q -> async { try { sources[k]?.search(q, 10).orEmpty() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            PlaybackLog.log("Fallback ${k.label}: \"$q\" failed ${e.javaClass.simpleName}: ${e.message}"); emptyList() } } }.awaitAll()
                        val seen = results.sumOf { it.size }
                        val hit = results.firstNotNullOfOrNull { TrackMatcher.best(t, it, TrackMatcher.LENIENT_THRESHOLD, lenient = true) }
                        PlaybackLog.log("Fallback ${k.label}: $seen results in ${System.currentTimeMillis() - t0} ms, ${hit?.let { "match \"${it.title}\" by ${it.artist} (score ${"%.2f".format(TrackMatcher.score(t, it, lenient = true))})" } ?: "no match"}")
                        if (hit == null) PlaybackLog.step(k.label, if (seen == 0) "no results" else "no match")
                        listOfNotNull(hit)
                    } ?: run { PlaybackLog.log("Fallback ${k.label}: search timed out after ${FALLBACK_SEARCH_MS / 1000} s"); PlaybackLog.step(k.label, "search timed out"); emptyList() }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    PlaybackLog.log("Fallback ${k.label}: search failed ${e.javaClass.simpleName}: ${e.message}"); PlaybackLog.step(k.label, "search failed"); emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun resolveFallback(cand: Track, quality: Quality, metered: Boolean): Resolved {
        downloaded(cand.id)?.let { return it.copy(fallbackFrom = SourceKind.YOUTUBE) }
        val src = sources[cand.source] ?: throw SourceUnavailableException("${cand.source.label} unavailable")
        val r = withTimeoutOrNull(FALLBACK_RESOLVE_MS) { src.resolve(cand, quality, metered) }
            ?: throw SourceUnavailableException("${cand.source.label} timed out")
        val code = runCatching { streamProbe?.invoke(r) }.getOrNull()
        PlaybackLog.log("Fallback ${cand.source.label}: stream ${code?.let { "HTTP $it" } ?: "not probed"} ${r.uri}")
        if (code != null && code >= 400) throw SourceUnavailableException("${cand.source.label} stream HTTP $code")
        return r.copy(fallbackFrom = SourceKind.YOUTUBE)
    }

    companion object {
        const val YT_BUDGET_MS = 22_000L
        const val OTHER_BUDGET_MS = 15_000L
        /** v2.0.5: was 9 s (JioSaavn "search timed out" on Ravi's mobile network). */
        const val FALLBACK_SEARCH_MS = 20_000L
        /** YouTube runs alone this long before a ready fallback stream may win the race. */
        const val RACE_AFTER_MS = 6_000L
        const val FALLBACK_RESOLVE_MS = 12_000L
        private val FALLBACK_SEARCHABLE = setOf(SourceKind.JIOSAAVN, SourceKind.AUDIUS, SourceKind.JAMENDO, SourceKind.SUBSONIC, SourceKind.JELLYFIN, SourceKind.LOCAL, SourceKind.ARCHIVE)
    }
}
