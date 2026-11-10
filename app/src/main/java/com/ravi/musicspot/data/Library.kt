package com.ravi.musicspot.data

import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.core.JsonFile
import kotlinx.coroutines.launch
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
    /** 1.0.2: pinned playlists sit at the top of their Library section. */
    val pinned: Boolean = false,
    /** 1.0.2: how the playlist page orders songs (PlaylistSort name); CUSTOM = the playlist's own order. */
    val sort: String = "CUSTOM",
) {
    val isSynced: Boolean get() = origin.startsWith("spotify:") || origin.startsWith("yt:")
}

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
    // 1.0.5: holdWritesUntilRead: an edit made while Graph.init is still loading (e.g. a Bluetooth/media-button
    // resume recording a play) used to be saved after 800 ms, and on a slow device that save could land before
    // load() and overwrite library.json with an almost empty library.
    // 1.0.6: 1500 ms debounce (was 800): a burst of plays/likes is one write; ON_STOP / onTaskRemoved flush at once.
    private val file = JsonFile(File(dir, "library.json"), LibraryData.serializer(), debounceMs = 1500, holdWritesUntilRead = true)
    private val _data = MutableStateFlow(LibraryData())
    val data: StateFlow<LibraryData> = _data
    private val lock = Any()
    @Volatile private var loaded = false
    /** 1.0.5: edits made before [load] finished, replayed on top of the data read from disk. */
    private val beforeLoad = mutableListOf<(LibraryData) -> LibraryData>()

    /** Background thread only (Graph.init runs it on Dispatchers.IO). */
    fun load() {
        val raw = file.read()
        // 1.0.6 retroactive clean-up: older versions kept one History row per play. Keep the newest row per song (its
        // plays summed) so existing duplicates disappear on the first start; the cleaned file is written back below.
        val cleaned = raw?.let { if (HistoryOps.isClean(it.history)) it else it.copy(history = HistoryOps.dedupe(it.history, MAX_HISTORY)) }
            ?.let { LibraryClean.clean(it) } // 1.0.7: repeated ids (older Spotify imports) crashed keyed lists
        // 1.0.5: was `_data.value = it`, which silently dropped every edit made before the read finished.
        synchronized(lock) {
            if (cleaned != null) _data.update { _ -> beforeLoad.fold(cleaned) { d, f -> f(d) } }
            beforeLoad.clear(); loaded = true
        }
        // Pending (held) saves are written now by JsonFile itself. A cleaned-up history is saved once (debounced).
        if (raw != null && cleaned !== raw) file.save { _data.value }
    }

    /** 1.0.5: suspending variant for callers outside Graph.init; never touches the disk on the caller's thread. */
    suspend fun loadAsync() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load() }

    private fun edit(f: (LibraryData) -> LibraryData) {
        synchronized(lock) { _data.update(f); if (!loaded) beforeLoad += f }
        file.save { _data.value }
    }

    fun isLiked(id: String) = _data.value.liked.any { it.id == id }
    fun setLiked(t: Track, liked: Boolean) = edit { d ->
        d.copy(liked = if (liked) listOf(t) + d.liked.filterNot { it.id == t.id } else d.liked.filterNot { it.id == t.id })
    }
    fun mergeLiked(tracks: List<Track>) = edit { d -> d.copy(liked = LibraryClean.mergeTracks(d.liked, tracks)) } // 1.0.7: each id once, O(n)

    fun createPlaylist(name: String, tracks: List<Track> = emptyList(), origin: String = "local", art: String? = null): LocalPlaylist {
        val p = LocalPlaylist("pl-" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "New playlist" }, LibraryClean.tracks(tracks), origin, art, System.currentTimeMillis()) // 1.0.7: each id once
        edit { it.copy(playlists = listOf(p) + it.playlists) }
        return p
    }
    /** Inserts or replaces the playlist with the same origin (sync), keeping its id. */
    fun upsertSynced(origin: String, name: String, tracks: List<Track>, art: String?) = edit { d ->
        val existing = d.playlists.firstOrNull { it.origin == origin }
        val p = (existing ?: LocalPlaylist("pl-" + origin.hashCode().toUInt().toString(36), name, origin = origin))
            .copy(name = name, tracks = LibraryClean.tracks(tracks), artUrl = art, syncedAtMs = System.currentTimeMillis()) // 1.0.7: each id once
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
    fun setPlaylistPinned(id: String, pinned: Boolean) = edit { d -> d.copy(playlists = d.playlists.map { if (it.id == id) it.copy(pinned = pinned) else it }) }
    fun setPlaylistSort(id: String, sort: String) = edit { d -> d.copy(playlists = d.playlists.map { if (it.id == id) it.copy(sort = sort) else it }) }
    fun playlist(id: String) = _data.value.playlists.firstOrNull { it.id == id }

    fun isSaved(id: String) = _data.value.saved.any { it.id == id }
    /** 1.0.4: pin an online album/playlist to the top of Library (saving it first); unpin keeps it saved, one place down. */
    fun pinSaved(c: Collection, pin: Boolean) = edit { d ->
        val rest = d.saved.filterNot { it.id == c.id }
        d.copy(saved = if (pin) listOf(c) + rest else rest.take(1) + listOf(c) + rest.drop(1))
    }
    fun toggleSaved(c: Collection) = edit { d -> d.copy(saved = if (d.saved.any { it.id == c.id }) d.saved.filterNot { it.id == c.id } else listOf(c) + d.saved) }
    fun mergeSaved(cs: List<Collection>) = edit { d -> d.copy(saved = LibraryClean.mergeCollections(d.saved, cs)) } // 1.0.7: each id once, O(n)

    /**
     * 1.0.6 upsert: the song's existing History row (if any) is removed and the updated row goes to the top with the
     * new time, so History shows each song once, newest first. Play counts for stats/taste are PlayStats' job
     * (PlaybackService counts every repeat play there), not History's.
     */
    fun recordPlay(t: Track, source: String?, atMs: Long = System.currentTimeMillis()) = if (com.ravi.musicspot.Graph.incognito) Unit else edit { d ->
        val h = HistoryOps.upsert(d.history, t, atMs, source, MAX_HISTORY)
        if (h === d.history) d else d.copy(history = h)
    }
    fun mergeHistory(tracks: List<Track>) = edit { d ->
        d.copy(history = HistoryOps.merge(d.history, tracks, System.currentTimeMillis(), "YouTube Music history", MAX_HISTORY))
    }
    fun clearHistory() = edit { it.copy(history = emptyList()) }
    /** 1.0.2: Backup restore. */
    fun replace(d: LibraryData) = edit { LibraryClean.clean(d.copy(history = HistoryOps.dedupe(d.history, MAX_HISTORY))) } // 1.0.6: old backups may hold duplicates
    /**
     * 1.0.2: "Remove from history" on a row. 1.0.6: History holds each song once, so this removes the song's row.
     * [atMs] is kept for callers (the row menu passes it); a row whose time no longer matches is still that song.
     */
    @Suppress("UNUSED_PARAMETER")
    fun removeHistory(trackId: String, atMs: Long) = edit { d ->
        val h = HistoryOps.remove(d.history, trackId)
        if (h === d.history) d else d.copy(history = h)
    }

    fun cachedMatch(spotifyId: String) = _data.value.matches[spotifyId]
    fun rememberMatches(m: Map<String, Track>) = edit { d -> d.copy(matches = (d.matches + m).entries.toList().takeLast(MAX_MATCHES).associate { it.key to it.value }) }

    companion object { const val MAX_HISTORY = 1000; const val MAX_MATCHES = 5000 }
}

@Serializable
data class QueueSnapshot(val tracks: List<Track> = emptyList(), val index: Int = 0, val positionMs: Long = 0, val shuffle: Boolean = false, val repeat: Int = 0)

/** Queue persistence: restored on the next start (also for media-button resumption). */
class QueueStore(dir: File) {
    // 1.0.5: written even when it was never read this session (restore is skipped when something already plays).
    private val file = JsonFile(File(dir, "queue.json"), QueueSnapshot.serializer(), debounceMs = 1500, holdWritesUntilRead = false)
    /** Blocking: background thread only (PlaybackService.restoreQueue and Backup call it on Dispatchers.IO). */
    fun read(): QueueSnapshot? = file.read()
    /** 1.0.5: suspending read on Dispatchers.IO. */
    suspend fun load(): QueueSnapshot? = file.load()
    fun save(s: QueueSnapshot) = file.save { s }
    /** 1.0.5: write the pending snapshot now (service stopping / task removed), on IO. */
    fun flush() { IoScope.launch { file.flush() } }
}
