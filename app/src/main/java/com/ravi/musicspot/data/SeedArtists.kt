package com.ravi.musicspot.data

import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.yt.SearchFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update // v2.0.16
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** v2.0.15: an artist the user picked in the optional artist picker. [id] is the catalogue id ("yt:UC…"). */
@Serializable
data class SeedArtist(val id: String, val name: String, val thumbnailUrl: String? = null)

/**
 * v2.0.15: the artists the user chose to tune Home (OpenTune-style onboarding). Persisted to filesDir/seed-artists.json.
 * [load] reads on the caller's thread: call it from Dispatchers.IO (Graph.init does). A change refreshes the
 * "For you · based on your artists" shelf (debounced) via [HomeFeedRepo.refreshSeeds].
 */
object SeedArtists {
    private val _state = MutableStateFlow<List<SeedArtist>>(emptyList())
    val state: StateFlow<List<SeedArtist>> = _state
    @Volatile private var file: JsonFile<List<SeedArtist>>? = null
    private var refreshJob: Job? = null

    private fun store(): JsonFile<List<SeedArtist>> = file ?: synchronized(this) {
        file ?: JsonFile(File(Graph.app.filesDir, "seed-artists.json"), ListSerializer(SeedArtist.serializer())).also { file = it }
    }

    fun load() { store().read()?.let { _state.value = it.distinctBy { a -> a.id } } }

    /** 1.0.1: last successful [suggested] result, kept for the session (memory only). */
    @Volatile var cachedSuggestions: List<SeedArtist> = emptyList()

    fun isSelected(id: String) = _state.value.any { it.id == id }

    fun toggle(a: SeedArtist) {
        // v2.0.16: atomic read-modify-write (load() runs on IO and can race a tap).
        _state.update { cur -> if (cur.any { it.id == a.id }) cur.filter { it.id != a.id } else cur + a }
        persistAndRefresh()
    }

    fun clear() {
        if (_state.value.isEmpty()) return
        _state.value = emptyList()
        persistAndRefresh()
    }

    private fun persistAndRefresh() {
        store().save { _state.value }
        synchronized(this) {
            refreshJob?.cancel()
            // Debounced so tapping through several artists triggers one Home update.
            refreshJob = IoScope.launch { delay(1_500); runCatching { HomeFeedRepo.refreshSeeds() } }
        }
    }

    // ---- catalogue helpers for the picker -----------------------------------------------------------------------
    private fun Collection.toSeed() = SeedArtist(id, title, artUrl)

    private val popularQueries = listOf(
        "Arijit Singh", "Taylor Swift", "Shreya Ghoshal", "The Weeknd", "Diljit Dosanjh", "AP Dhillon",
        "Pritam", "Ed Sheeran", "Badshah", "Sidhu Moose Wala", "Billie Eilish", "Atif Aslam",
    )

    /** ~[limit] popular artists: YT Music charts' artist cards (India) first, then artist searches. Throws when all fail. */
    suspend fun suggested(limit: Int = 24): List<SeedArtist> = supervisorScope {
        var lastError: Exception? = null
        suspend fun <T> safe(block: suspend () -> T): T? = try { withTimeoutOrNull(15_000) { block() } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { lastError = e; null }
        val chart = async {
            safe { Graph.yt.charts("IN") }.orEmpty().flatMap { it.collections }.filter { it.kind == Collection.Kind.ARTIST }.map { it.toSeed() }
        }
        val searched = popularQueries.map { q ->
            async { safe { Graph.yt.search(q, SearchFilter.ARTISTS).collections }.orEmpty().filter { it.kind == Collection.Kind.ARTIST }.take(2).map { it.toSeed() } }
        }
        val fromSearch = searched.awaitAll()
        // First hit of each query (the artist itself) before the "similar" second hits.
        val ordered = chart.await() + fromSearch.mapNotNull { it.firstOrNull() } + fromSearch.mapNotNull { it.getOrNull(1) }
        val out = ordered.filter { it.name.isNotBlank() }.distinctBy { it.id }.take(limit)
        if (out.isEmpty() && lastError != null) throw lastError!!
        out
    }

    suspend fun search(query: String, limit: Int = 30): List<SeedArtist> =
        Graph.yt.search(query, SearchFilter.ARTISTS).collections.filter { it.kind == Collection.Kind.ARTIST && it.title.isNotBlank() }
            .map { it.toSeed() }.distinctBy { it.id }.take(limit)

    /**
     * Songs from [seeds] for Home: each artist's top songs (YT Music artist page, falling back to "<artist> songs"
     * search, then JioSaavn), round-robin mixed, lightly shuffled and deduped by id and title+artist.
     */
    suspend fun songsFor(seeds: List<SeedArtist>, perArtist: Int = 8, limit: Int = 40): List<Track> = supervisorScope {
        val s = Graph.settings.value
        val yt = SourceKind.YOUTUBE in s.enabledSources
        val js = SourceKind.JIOSAAVN in s.enabledSources
        suspend fun <T> safe(block: suspend () -> T): T? = try { withTimeoutOrNull(15_000) { block() } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val lists = seeds.take(10).map { a ->
            async {
                val browseId = a.id.substringAfter("yt:")
                var tracks = if (yt && a.id.startsWith("yt:") && browseId.startsWith("UC")) safe { Graph.yt.artist(browseId).topTracks }.orEmpty() else emptyList()
                if (tracks.isEmpty() && yt) tracks = safe { Graph.yt.search("${a.name} songs", SearchFilter.SONGS).tracks }.orEmpty()
                if (tracks.isEmpty() && js) tracks = safe { Graph.jiosaavn.search("${a.name} songs", perArtist * 2) }.orEmpty()
                tracks.take(perArtist * 2).shuffled().take(perArtist)
            }
        }.awaitAll()
        val mixed = mutableListOf<Track>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.shuffled().forEach { l -> l.getOrNull(i)?.let { mixed += it } }
        val ids = HashSet<String>(); val keys = HashSet<String>()
        mixed.filter { it.title.isNotBlank() && ids.add(it.id) && keys.add(com.ravi.musicspot.logic.Taste.key(it)) }.take(limit)
    }
}
