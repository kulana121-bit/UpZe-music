package com.ravi.musicspot.data

import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList(),
    /** "local", "spotify:<id>" (synced import), "yt:<browseId>" (YT Music playlist), "link:<url>" (shared-link import). */
    val origin: String = "local",
    val artUrl: String? = null,
    val syncedAtMs: Long = 0,
) {
    val isSynced: Boolean get() = origin.startsWith("spotify:") || origin.startsWith("yt:")
}

@Serializable
data class HistoryEntry(val track: Track, val atMs: Long, val source: String? = null)

@Serializable
data class LibraryData(
    val liked: List<Track> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val saved: List<Collection> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    /** Spotify track id → matched playable track id (cache for Spotify sync). */
    val matches: Map<String, Track> = emptyMap(),
)

/** The user's own data. Everything starts empty: no demo content. */
class LibraryRepo(dir: File) {
    private val file = JsonFile(File(dir, "library.json"), LibraryData.serializer(), debounceMs = 800)
    private val _data = MutableStateFlow(LibraryData())
    val data: StateFlow<LibraryData> = _data

    fun load() { file.read()?.let { _data.value = it } }
    private fun edit(f: (LibraryData) -> LibraryData) { _data.update(f); file.save { _data.value } }

    fun isLiked(id: String) = _data.value.liked.any { it.id == id }
    fun setLiked(t: Track, liked: Boolean) = edit { d ->
        d.copy(liked = if (liked) listOf(t) + d.liked.filterNot { it.id == t.id } else d.liked.filterNot { it.id == t.id })
    }
    fun mergeLiked(tracks: List<Track>) = edit { d -> d.copy(liked = (d.liked + tracks.filter { t -> d.liked.none { it.id == t.id } })) }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList(), origin: String = "local", art: String? = null): LocalPlaylist {
        val p = LocalPlaylist("pl-" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "New playlist" }, tracks, origin, art, System.currentTimeMillis())
        edit { it.copy(playlists = listOf(p) + it.playlists) }
        return p
    }
    /** Inserts or replaces the playlist with the same origin (sync), keeping its id. */
    fun upsertSynced(origin: String, name: String, tracks: List<Track>, art: String?) = edit { d ->
        val existing = d.playlists.firstOrNull { it.origin == origin }
        val p = (existing ?: LocalPlaylist("pl-" + origin.hashCode().toUInt().toString(36), name, origin = origin))
            .copy(name = name, tracks = tracks, artUrl = art, syncedAtMs = System.currentTimeMillis())
        d.copy(playlists = if (existing != null) d.playlists.map { if (it.id == p.id) p else it } else d.playlists + p)
    }
    fun renamePlaylist(id: String, name: String) = edit { d -> d.copy(playlists = d.playlists.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }) }
    fun deletePlaylist(id: String) = edit { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }
    fun removeSyncedFrom(prefix: String) = edit { d -> d.copy(playlists = d.playlists.filterNot { it.origin.startsWith(prefix) }) }
    fun addToPlaylist(id: String, t: Track) = edit { d -> d.copy(playlists = d.playlists.map { if (it.id == id && it.tracks.none { x -> x.id == t.id }) it.copy(tracks = it.tracks + t) else it }) }
    fun removeFromPlaylist(id: String, trackId: String) = edit { d -> d.copy(playlists = d.playlists.map { if (it.id == id) it.copy(tracks = it.tracks.filterNot { x -> x.id == trackId }) else it }) }
    fun movePlaylistTrack(id: String, from: Int, to: Int) = edit { d -> d.copy(playlists = d.playlists.map { p ->
        if (p.id != id || from !in p.tracks.indices || to !in p.tracks.indices) p else p.copy(tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) })
    }) }
    fun playlist(id: String) = _data.value.playlists.firstOrNull { it.id == id }

    fun isSaved(id: String) = _data.value.saved.any { it.id == id }
    fun toggleSaved(c: Collection) = edit { d -> d.copy(saved = if (d.saved.any { it.id == c.id }) d.saved.filterNot { it.id == c.id } else listOf(c) + d.saved) }
    fun mergeSaved(cs: List<Collection>) = edit { d -> d.copy(saved = d.saved + cs.filter { c -> d.saved.none { it.id == c.id } }) }

    fun recordPlay(t: Track, source: String?) = edit { d ->
        val last = d.history.firstOrNull()
        if (last != null && last.track.id == t.id && System.currentTimeMillis() - last.atMs < 30_000) d
        else d.copy(history = (listOf(HistoryEntry(t, System.currentTimeMillis(), source)) + d.history).take(MAX_HISTORY))
    }
    fun mergeHistory(tracks: List<Track>) = edit { d ->
        val known = d.history.mapTo(HashSet()) { it.track.id }
        val now = System.currentTimeMillis()
        d.copy(history = (d.history + tracks.filter { known.add(it.id) }.mapIndexed { i, t -> HistoryEntry(t, now - 3_600_000L * (i + 1), "YouTube Music history") })
            .sortedByDescending { it.atMs }.take(MAX_HISTORY))
    }
    fun clearHistory() = edit { it.copy(history = emptyList()) }

    fun cachedMatch(spotifyId: String) = _data.value.matches[spotifyId]
    fun rememberMatches(m: Map<String, Track>) = edit { d -> d.copy(matches = (d.matches + m).entries.toList().takeLast(MAX_MATCHES).associate { it.key to it.value }) }

    companion object { const val MAX_HISTORY = 1000; const val MAX_MATCHES = 5000 }
}

@Serializable
data class QueueSnapshot(val tracks: List<Track> = emptyList(), val index: Int = 0, val positionMs: Long = 0, val shuffle: Boolean = false, val repeat: Int = 0)

/** Queue persistence: restored on the next start (also for media-button resumption). */
class QueueStore(dir: File) {
    private val file = JsonFile(File(dir, "queue.json"), QueueSnapshot.serializer(), debounceMs = 1500)
    fun read(): QueueSnapshot? = file.read()
    fun save(s: QueueSnapshot) = file.save { s }
}
