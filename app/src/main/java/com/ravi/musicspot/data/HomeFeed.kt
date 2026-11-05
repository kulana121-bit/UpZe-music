package com.ravi.musicspot.data

import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Shelf
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.yt.SearchFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File

/** One persisted home shelf (Shelf itself is not serialisable; it carries a lambda-free subset). */
@Serializable
data class FeedShelf(
    val title: String,
    val subtitle: String? = null,
    val tracks: List<Track> = emptyList(),
    val collections: List<Collection> = emptyList(),
) {
    fun toShelf() = Shelf(title, tracks, collections, subtitle)
    val isEmpty get() = tracks.isEmpty() && collections.isEmpty()
}

@Serializable
data class FeedMood(val title: String, val browseId: String, val params: String? = null)

/**
 * Everything Home shows from the network, gathered in parallel and cached to disk (cacheDir/home-feed.json) so the
 * next open is instant and offline-friendly. Only real data: YT Music (guest or signed in) and JioSaavn.
 */
@Serializable
data class HomeFeed(
    val atMs: Long = 0,
    val moods: List<FeedMood> = emptyList(),
    val quickPicks: List<Track> = emptyList(),
    val ytShelves: List<FeedShelf> = emptyList(),
    val ytContinuation: String? = null,
    val charts: List<FeedShelf> = emptyList(),
    val jsTrending: FeedShelf? = null,
    val newReleases: List<FeedShelf> = emptyList(),
    val jsPlaylists: FeedShelf? = null,
    val regional: List<FeedShelf> = emptyList(),
    val errors: List<String> = emptyList(),
    // v2.0.15: "For you · based on your artists" (SeedArtists) and the ids it mixed into quickPicks.
    val forYou: FeedShelf? = null,
    val seededQuickIds: List<String> = emptyList(),
) {
    val isEmpty get() = forYou == null && ytShelves.isEmpty() && charts.isEmpty() && jsTrending == null && newReleases.isEmpty() && regional.isEmpty() && quickPicks.isEmpty()
}

object HomeFeedRepo {
    private val _feed = MutableStateFlow<HomeFeed?>(null)
    val feed: StateFlow<HomeFeed?> = _feed
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val mutex = Mutex()
    @Volatile private var cacheRead = false

    private fun file() = File(Graph.app.cacheDir, "home-feed.json")

    /** Shows the cached feed immediately (IO), then refreshes when it is older than [maxAgeMs] or [force]. */
    suspend fun load(force: Boolean = false, maxAgeMs: Long = 20 * 60_000L) {
        if (!cacheRead) {
            cacheRead = true
            withContext(Dispatchers.IO) {
                runCatching { file().takeIf { it.isFile }?.readText()?.let { Http.json.decodeFromString(HomeFeed.serializer(), it) } }.getOrNull()
            }?.let { if (_feed.value == null) _feed.value = it }
        }
        val cur = _feed.value
        if (!force && cur != null && System.currentTimeMillis() - cur.atMs < maxAgeMs) return
        if (!Graph.online.value) return
        refresh()
    }

    suspend fun refresh() = mutex.withLock {
        _refreshing.value = true
        try {
            val fresh = gather()
            if (!fresh.isEmpty || _feed.value == null) {
                _feed.value = fresh
                withContext(Dispatchers.IO) { runCatching { file().writeText(Http.json.encodeToString(HomeFeed.serializer(), fresh)) } }
            }
            _error.value = if (fresh.isEmpty) fresh.errors.firstOrNull() ?: "Nothing came back from your sources" else null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _error.value = e.message ?: "Couldn't refresh"
        } finally { _refreshing.value = false }
    }

    /** Next page of YT Music home shelves (continuation), appended and cached. */
    suspend fun more(): Boolean {
        val cur = _feed.value ?: return false
        val token = cur.ytContinuation ?: return false
        val (shelves, next) = Graph.yt.homeMore(token)
        // v2.0.16: apply to the feed as it is now; a refresh/seed update that landed meanwhile was being overwritten
        // with the stale snapshot, and a second call for the same token appended the page twice.
        return mutex.withLock {
            val now = _feed.value ?: return@withLock false
            if (now.ytContinuation != token) return@withLock false
            _feed.value = now.copy(ytShelves = now.ytShelves + shelves.map { it.feed() }, ytContinuation = next)
            true
        }
    }

    // v2.0.15: seed-artist shelf + quick-pick seeding.
    const val FOR_YOU_TITLE = "For you · based on your artists"
    private const val LITTLE_HISTORY_PLAYS = 15

    private fun littleHistory(): Boolean = runCatching { Graph.playStats.data.value.stats.values.sumOf { it.plays } < LITTLE_HISTORY_PLAYS }.getOrDefault(true)

    /** Quick picks with seed songs interleaved when play history is thin; returns (picks, ids that came from seeds). */
    private fun seedQuick(base: List<Track>, seedTracks: List<Track>): Pair<List<Track>, List<String>> {
        if (seedTracks.isEmpty() || !littleHistory()) return base to emptyList()
        val seeds = seedTracks.take(12)
        val out = mutableListOf<Track>()
        val n = maxOf(seeds.size, base.size)
        for (i in 0 until n) { seeds.getOrNull(i)?.let { out += it }; base.getOrNull(i)?.let { out += it } }
        val picks = out.distinctBy { it.id }.take(24)
        val seedIds = seeds.mapTo(HashSet()) { it.id }
        return picks to picks.filter { it.id in seedIds && base.none { b -> b.id == it.id } }.map { it.id }
    }

    private fun forYouShelf(tracks: List<Track>) = tracks.takeIf { it.isNotEmpty() }?.let { FeedShelf(FOR_YOU_TITLE, "Songs from artists you picked", it.take(30)) }

    /** v2.0.15: recompute only the seed shelf (and seeded quick picks) after the artist selection changes. */
    suspend fun refreshSeeds() {
        // Before the cache is read, load() will run a full gather that already includes seeds.
        if (!cacheRead) return
        val seeds = SeedArtists.state.value
        val tracks = if (seeds.isEmpty() || !Graph.online.value) emptyList() else SeedArtists.songsFor(seeds)
        if (seeds.isNotEmpty() && tracks.isEmpty()) return // offline / failed: keep what Home shows
        mutex.withLock {
            val cur = _feed.value ?: return
            val removed = cur.seededQuickIds.toHashSet()
            val base = cur.quickPicks.filter { it.id !in removed }
            val (quick, ids) = seedQuick(base, tracks)
            val next = cur.copy(forYou = forYouShelf(tracks), quickPicks = quick, seededQuickIds = ids)
            _feed.value = next
            withContext(Dispatchers.IO) { runCatching { file().writeText(Http.json.encodeToString(HomeFeed.serializer(), next)) } }
        }
    }

    // v2.0.16: dedupe by id so keyed rows built from a shelf never see duplicate keys.
    private fun Shelf.feed() = FeedShelf(title, subtitle, tracks.distinctBy { it.id }.take(30), collections.distinctBy { it.id }.take(30))

    private suspend fun gather(): HomeFeed = supervisorScope {
        val s = Graph.settings.value
        val yt = SourceKind.YOUTUBE in s.enabledSources
        val js = SourceKind.JIOSAAVN in s.enabledSources
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        suspend fun <T> safe(label: String, block: suspend () -> T): T? = try {
            withTimeoutOrNull(20_000) { block() } ?: run { errors += "$label timed out"; null }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { errors += "$label: ${e.message?.take(80)}"; null }

        val home = async { if (yt) safe("YouTube Music home") { Graph.yt.home() } else null }
        val moods = async { if (yt) safe("Moods") { Graph.yt.moods() } else null }
        val charts = async { if (yt) safe("Charts") { Graph.yt.charts("IN") } else null }
        val newRel = async { if (yt) safe("New releases") { Graph.yt.newReleases() } else null }
        val launch = async { if (js) safe("JioSaavn") { Graph.jiosaavn.launch() } else null }
        // v2.0.15: songs from the artists picked in the artist picker (none picked → no shelf, feed unchanged).
        val seedList = SeedArtists.state.value
        val seedSongs = async { if (seedList.isNotEmpty()) safe("Your artists") { SeedArtists.songsFor(seedList) }.orEmpty() else emptyList() }
        // Regional shelves (Panipat, Haryana): Hindi / Punjabi / Haryanvi hits from YT Music search, JioSaavn as fallback.
        val regionalQ = listOf("Hindi hits" to "latest hindi hit songs", "Punjabi hits" to "latest punjabi hit songs", "Haryanvi hits" to "latest haryanvi songs")
        val regional = regionalQ.map { (title, q) ->
            async {
                val tracks = (if (yt) safe(title) { Graph.yt.search(q, SearchFilter.SONGS).tracks } else null).orEmpty().ifEmpty {
                    if (js) safe("$title (JioSaavn)") { Graph.jiosaavn.search(q.removePrefix("latest "), 20) }.orEmpty() else emptyList()
                }
                FeedShelf(title, if (yt) "YouTube Music" else "JioSaavn", tracks.distinctBy { it.id }.take(20))
            }
        }

        val (ytShelves, cont) = home.await() ?: (emptyList<Shelf>() to null)
        // YT Music's own "Quick picks" shelf when it has one (signed-in), else a mix of the first song shelves.
        val qpShelf = ytShelves.firstOrNull { it.title.contains("quick picks", true) && it.tracks.isNotEmpty() }
        val l = launch.await()
        val quick = (qpShelf?.tracks ?: (ytShelves.flatMap { it.tracks.take(8) } + l?.trending?.tracks.orEmpty()))
            .distinctBy { it.id }.take(24)
        val seedTracks = seedSongs.await()
        val (seededQuick, seededIds) = seedQuick(quick, seedTracks)
        val chartShelves = charts.await().orEmpty()
        HomeFeed(
            atMs = System.currentTimeMillis(),
            moods = moods.await().orEmpty().flatMap { it.moods }.distinctBy { it.title }.take(24).map { FeedMood(it.title, it.browseId, it.params) },
            quickPicks = seededQuick,
            ytShelves = ytShelves.filter { it !== qpShelf }.map { it.feed() },
            ytContinuation = cont,
            charts = chartShelves.map { it.feed() }.map { if (it.title.contains("India", true)) it else it.copy(subtitle = it.subtitle ?: "India") },
            jsTrending = l?.trending?.feed()?.takeIf { !it.isEmpty },
            newReleases = (newRel.await().orEmpty().take(2).map { it.feed() } + listOfNotNull(l?.newReleases?.feed()?.takeIf { !it.isEmpty }?.copy(title = "New on JioSaavn"))),
            jsPlaylists = l?.playlists?.feed()?.takeIf { !it.isEmpty },
            regional = regional.map { it.await() }.filter { !it.isEmpty },
            errors = errors.toList(),
            forYou = forYouShelf(seedTracks),
            seededQuickIds = seededIds,
        )
    }
}
