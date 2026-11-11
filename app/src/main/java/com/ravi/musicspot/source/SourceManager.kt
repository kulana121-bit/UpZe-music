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
        // 1.0.6: plan entries a hedge already tried (and saw fail) are not tried a second time.
        val hedgeFailed = HashSet<String>()
        for ((ci, cand) in plan.withIndex()) {
            downloaded(cand.id)?.let { return@coroutineScope it }
            if (cand.id in hedgeFailed) continue
            val src = sources[cand.source] ?: continue
            tried += cand.source
            val budget = if (cand.source == SourceKind.YOUTUBE) YT_BUDGET_MS else OTHER_BUDGET_MS
            try {
                val r = (if (cand.source == SourceKind.YOUTUBE && fbKinds.isNotEmpty()) {
                    val fb = startFallback(fbKinds)
                    raceYouTube(cand, src, quality, metered, budget, fb)
                } else {
                    // 1.0.6 hedged request: a slow non-YouTube source gets the next plan entry asked in parallel.
                    val backup = plan.drop(ci + 1).firstOrNull { it.id !in hedgeFailed && it.source != cand.source && it.source != SourceKind.YOUTUBE && sources[it.source] != null }
                    hedged(cand, src, backup, quality, metered, budget, hedgeFailed)
                })
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

    /**
     * 1.0.7 lossless upgrade: [t] is playing from a lossy stream ([playing]); look for the same recording on the
     * enabled lossless sources (in the user's priority order) and return a stream only when it really is lossless.
     * Searches run together (6 s each); candidates are resolved one by one (12 s each). Null when nothing better.
     */
    suspend fun losslessUpgrade(t: Track, playing: com.ravi.musicspot.model.StreamInfo, metered: Boolean): Resolved? = coroutineScope {
        if (QualityEstimate.isLosslessStream(playing)) return@coroutineScope null
        val usable = enabledKinds()
        val prio = priority()
        val kinds = QualityEstimate.LOSSLESS_SOURCES.filter { it in usable && sources[it] != null }
            .sortedBy { prio.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        if (kinds.isEmpty()) return@coroutineScope null
        val lenient = t.source == SourceKind.YOUTUBE
        val queries = TrackMatcher.searchQueries(t)
        val found = kinds.map { k ->
            async {
                try {
                    withTimeoutOrNull(6_000) {
                        var hit: Track? = null
                        for (q in queries) {
                            val res = sources[k]?.search(q, 8).orEmpty()
                            hit = TrackMatcher.best(t, res, if (lenient) TrackMatcher.LENIENT_THRESHOLD else TrackMatcher.THRESHOLD, lenient)
                            if (hit != null) break
                        }
                        hit
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }
        }.awaitAll().filterNotNull().filter { it.id != t.id }
        for (cand in found) {
            val src = sources[cand.source] ?: continue
            val r = try { withTimeoutOrNull(12_000) { src.resolve(cand, Quality.LOSSLESS, metered) } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { PlaybackLog.log("Upgrade: ${cand.source.label} failed: ${e.message}"); null }
            if (r != null && QualityEstimate.isLosslessStream(r.info)) return@coroutineScope r
            if (r != null) PlaybackLog.log("Upgrade: ${cand.source.label} answered ${r.info.label}, not lossless")
        }
        null
    }

    private fun fallbackKinds(t: Track, usable: Set<SourceKind>, tried: Set<SourceKind>): List<SourceKind> =
        (listOf(SourceKind.JIOSAAVN) + priority() + sources.keys).distinct()
            .filter { it != SourceKind.YOUTUBE && it in usable && it !in tried && it in FALLBACK_SEARCHABLE && it != t.source }

    /**
     * YouTube against the head-start fallback: YouTube alone for [HEDGE_AFTER_MS]; after that, a fallback match that
     * resolves first wins (YouTube is cancelled), otherwise YouTube keeps its full [budget]. A YouTube failure is
     * rethrown so the caller's failover logs it and uses the fallback.
     */
    private suspend fun raceYouTube(cand: Track, src: MusicSource, quality: Quality, metered: Boolean, budget: Long, fb: Deferred<List<Track>>): Resolved? = coroutineScope {
        val started = System.currentTimeMillis()
        val yt = async { withTimeoutOrNull(budget) { src.resolve(cand, quality, metered) } }
        withTimeoutOrNull(HEDGE_AFTER_MS) { yt.join() } // 1.0.6: hedge after 1.5 s (was 6 s)
        if (yt.isCompleted) return@coroutineScope yt.await()
        // YouTube is slow: resolve the fallback match (if any) while YouTube keeps going; first stream wins.
        val alt = async {
            val found = try { fb.await() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                PlaybackLog.log("Race: fallback search for ${cand.title} failed: ${e.javaClass.simpleName}: ${e.message}") // 1.0.10
                emptyList()
            }
            for (c in found) {
                try { return@async resolveFallback(c, quality, metered) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    // 1.0.10: was an empty catch; say which fallback candidate failed and why.
                    PlaybackLog.log("Race: fallback ${c.source.label} \"${c.title}\" failed to resolve: ${e.javaClass.simpleName}: ${e.message}")
                }
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
     * 1.0.6 hedged resolve for a non-YouTube candidate: [cand] alone for [HEDGE_AFTER_MS]; if it has no stream by then,
     * [backup] (the next plan entry) is resolved in parallel and the first stream wins (the other is cancelled). A
     * primary failure waits for the backup; a backup failure is remembered in [hedgeFailed] so the plan walk doesn't
     * ask it again. Without a backup this is exactly the pre-1.0.6 single resolve.
     */
    private suspend fun hedged(cand: Track, src: MusicSource, backup: Track?, quality: Quality, metered: Boolean, budget: Long, hedgeFailed: MutableSet<String>): Resolved? = coroutineScope {
        val bsrc = backup?.let { sources[it.source] }
        if (backup == null || bsrc == null) return@coroutineScope withTimeoutOrNull(budget) { src.resolve(cand, quality, metered) }
        val started = System.currentTimeMillis()
        val main = async { withTimeoutOrNull(budget) { src.resolve(cand, quality, metered) } }
        withTimeoutOrNull(HEDGE_AFTER_MS) { main.join() }
        if (main.isCompleted) return@coroutineScope main.await()
        PlaybackLog.log("Hedge: ${cand.source.label} has no stream after ${HEDGE_AFTER_MS} ms; asking ${backup.source.label} in parallel")
        val alt = async {
            try { downloaded(backup.id) ?: withTimeoutOrNull(if (backup.source == SourceKind.YOUTUBE) YT_BUDGET_MS else OTHER_BUDGET_MS) { bsrc.resolve(backup, quality, metered) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) {
                PlaybackLog.log("Hedge ${backup.source.label} failed: ${e.javaClass.simpleName}: ${e.message}"); null
            }
        }
        while (true) {
            if (main.isCompleted) {
                val r = try { main.await() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    // Primary failed: the backup may still deliver.
                    val b = alt.await()
                    if (b != null) { PlaybackLog.log("Hedge: ${backup.source.label} delivered after ${cand.source.label} failed"); return@coroutineScope b }
                    hedgeFailed += backup.id
                    throw e
                }
                if (r != null) { alt.cancel(); return@coroutineScope r }
                val b = alt.await() // primary timed out: the backup's answer (or null) decides
                if (b == null) hedgeFailed += backup.id
                return@coroutineScope b
            }
            if (alt.isCompleted) {
                val b = alt.await()
                if (b != null) {
                    main.cancel()
                    PlaybackLog.log("Hedge: ${b.info.source.label} was ready first (${System.currentTimeMillis() - started} ms); ${cand.source.label} cancelled")
                    return@coroutineScope b
                }
                hedgeFailed += backup.id
                return@coroutineScope main.await()
            }
            kotlinx.coroutines.delay(25)
        }
        @Suppress("UNREACHABLE_CODE") null
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
        /**
         * 1.0.6 hedged requests: the primary source runs alone this long; after that the fallback (YouTube's
         * head-start search, or the next plan entry for other sources) is resolved in parallel and the first stream
         * wins, so a slow source no longer delays the start. Was 6 s for YouTube only.
         */
        const val HEDGE_AFTER_MS = 1_500L
        /** Kept for callers/tests that read the old name. */
        const val RACE_AFTER_MS = HEDGE_AFTER_MS
        const val FALLBACK_RESOLVE_MS = 12_000L
        private val FALLBACK_SEARCHABLE = setOf(SourceKind.JIOSAAVN, SourceKind.NETEASE, SourceKind.MONOCHROME, SourceKind.QOBUZ, SourceKind.AUDIUS, SourceKind.JAMENDO, SourceKind.SUBSONIC, SourceKind.JELLYFIN, SourceKind.LOCAL, SourceKind.ARCHIVE)
    }
}
