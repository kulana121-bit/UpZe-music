package com.ravi.musicspot.account

import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.TrackMatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Pulls the signed-in accounts' libraries into the local library. Progress/errors are shown in Settings. */
class SyncManager {
    data class Status(val running: Boolean = false, val message: String? = null, val error: Boolean = false, val atMs: Long = 0)

    private val _yt = MutableStateFlow(Status())
    val yt: StateFlow<Status> = _yt
    private val _spotify = MutableStateFlow(Status())
    val spotify: StateFlow<Status> = _spotify
    private var ytJob: Job? = null
    private var spJob: Job? = null

    /** YouTube Music: likes, playlists (with tracks), saved albums, subscriptions and watch history. */
    @Synchronized // v2.0.14: atomic job check+assign
    fun syncYouTube() {
        if (ytJob?.isActive == true) return
        ytJob = IoScope.launch {
            _yt.value = Status(true, "Syncing YouTube Music…")
            try {
                val yt = Graph.yt
                yt.accountInfo()?.let { a -> Graph.accounts.update(Account.Service.YOUTUBE) { it.copy(name = a.name, email = a.email, avatarUrl = a.thumbnailUrl) } }
                val liked = runCatching { yt.likedSongs() }.getOrDefault(emptyList())
                Graph.library.mergeLiked(liked)
                val playlists = runCatching { yt.libraryPlaylists() }.getOrDefault(emptyList())
                var plCount = 0
                for (p in playlists.take(50)) {
                    val page = runCatching { yt.collection(p.id.removePrefix("yt:")) }.getOrNull() ?: continue
                    Graph.library.upsertSynced(p.id, p.title, page.tracks, p.artUrl); plCount++
                }
                val albums = runCatching { yt.libraryAlbums() }.getOrDefault(emptyList())
                val subs = runCatching { yt.subscriptions() }.getOrDefault(emptyList())
                Graph.library.mergeSaved(albums + subs)
                val history = runCatching { yt.history() }.getOrDefault(emptyList())
                Graph.library.mergeHistory(history)
                _yt.value = Status(false, "${liked.size} likes · $plCount playlists · ${albums.size} albums · ${subs.size} artists · ${history.size} history", atMs = System.currentTimeMillis())
            } catch (e: Throwable) { // 1.0.7: see syncSpotify
                if (e is kotlinx.coroutines.CancellationException) throw e
                _yt.value = Status(false, "Sync failed: ${e.message ?: e.javaClass.simpleName}", true, System.currentTimeMillis())
            }
        }
    }

    /**
     * Spotify: Liked Songs and playlists are read (metadata only) and every song is matched to a playable source
     * (ISRC/title/artist/duration via [TrackMatcher]). Matches are cached so re-syncs only search new songs.
     */
    @Synchronized // v2.0.14: atomic job check+assign
    fun syncSpotify(onlyPlaylists: Set<String>? = null) {
        if (spJob?.isActive == true) return
        spJob = IoScope.launch {
            val cookie = Graph.accounts.get(Account.Service.SPOTIFY)?.secret ?: run { _spotify.value = Status(false, "Not signed in", true); return@launch }
            _spotify.value = Status(true, "Reading your Spotify library…")
            try {
                val lists = Graph.spotify.playlists(cookie).filter { onlyPlaylists == null || it.id in onlyPlaylists }
                var matched = 0; var total = 0
                for ((i, p) in lists.withIndex()) {
                    _spotify.value = Status(true, "Matching ${p.name} (${i + 1}/${lists.size})…")
                    val tracks = Graph.spotify.tracks(cookie, p.id)
                    val resolved = matchAll(tracks)
                    total += tracks.size; matched += resolved.count { it.source != SourceKind.SPOTIFY }
                    if (p.id == com.ravi.musicspot.spotify.SpotifyClient.LIKED_ID) Graph.library.mergeLiked(resolved)
                    else Graph.library.upsertSynced("spotify:${p.id}", p.name, resolved, p.imageUrl)
                }
                _spotify.value = Status(false, "${lists.size} playlists · $matched of $total songs matched to playable sources", atMs = System.currentTimeMillis())
            } catch (e: Throwable) {
                // 1.0.7: Throwable, not Exception: an Error on this IO thread (OutOfMemoryError on a 2 GB phone with a
                // big library, a regex StackOverflowError on Spotify's script) used to take the whole app down.
                if (e is kotlinx.coroutines.CancellationException) throw e
                _spotify.value = Status(false, "Sync failed: ${e.message ?: e.javaClass.simpleName}", true, System.currentTimeMillis())
            }
        }
    }

    /** Each Spotify track → its best playable match, or the Spotify metadata track (matched again at play time). */
    private suspend fun matchAll(tracks: List<Track>): List<Track> = coroutineScope {
        val gate = Semaphore(4)
        val found = mutableMapOf<String, Track>()
        // 1.0.7: songs are matched in chunks of 64 (was one coroutine per song all at once: thousands of suspended
        // jobs and their responses alive together on a 2 GB phone). Same 4-way parallelism, same results and order.
        val out = tracks.chunked(64).flatMap { chunk -> chunk.map { t ->
            async {
                Graph.library.cachedMatch(t.nativeId)?.let { return@async it }
                gate.withPermit {
                    val m = runCatching { Graph.sources.matchesFor(t, Graph.settings.value.quality) }.getOrDefault(emptyList())
                    val best = m.maxByOrNull { TrackMatcher.score(t, it) }
                    if (best != null) synchronized(found) { found[t.nativeId] = best.copy(artUrl = best.artUrl ?: t.artUrl) }
                    best ?: t
                }
            }
        }.awaitAll() }
        if (found.isNotEmpty()) Graph.library.rememberMatches(found)
        out
    }

    fun signOutSpotify() { spJob?.cancel(); Graph.spotify.signedOut(); Graph.accounts.remove(Account.Service.SPOTIFY); Graph.library.removeSyncedFrom("spotify:"); _spotify.value = Status() }
    fun signOutYouTube() { ytJob?.cancel(); Graph.accounts.remove(Account.Service.YOUTUBE); Graph.tube.cookie = null; Graph.library.removeSyncedFrom("yt:"); _yt.value = Status() }
}
