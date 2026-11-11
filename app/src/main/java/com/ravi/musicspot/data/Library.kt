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

/** Pack 6: one named queue (its own tracks, index, position, shuffle and repeat: the fields QueueSnapshot carries). */
@Serializable
data class NamedQueue(val id: String, val name: String, val snapshot: QueueSnapshot = QueueSnapshot())

/** Pack 6: up to [QueueSets.MAX] named queues; [active] is the one in the player. */
@Serializable
data class QueueSet(val active: String = QueueSets.DEFAULT_ID, val queues: List<NamedQueue> = listOf(NamedQueue(QueueSets.DEFAULT_ID, QueueSets.DEFAULT_NAME))) {
    val activeQueue: NamedQueue? get() = queues.firstOrNull { it.id == active } ?: queues.firstOrNull()
}

/** Pack 6: pure multi-queue rules (unit-tested). Every function returns the same instance when nothing changes. */
object QueueSets {
    const val MAX = 10
    const val DEFAULT_ID = "q-default"
    const val DEFAULT_NAME = "My queue"

    /** The legacy single queue (queue.json) becomes "My queue", active, unchanged. */
    fun migrate(legacy: QueueSnapshot?): QueueSet = QueueSet(DEFAULT_ID, listOf(NamedQueue(DEFAULT_ID, DEFAULT_NAME, legacy ?: QueueSnapshot())))

    /** Repairs a set read from disk: at least one queue, unique ids, at most MAX, active pointing at a real queue. */
    fun sane(s: QueueSet): QueueSet {
        val qs = s.queues.distinctBy { it.id }.take(MAX).ifEmpty { listOf(NamedQueue(DEFAULT_ID, DEFAULT_NAME)) }
        val active = if (qs.any { it.id == s.active }) s.active else qs.first().id
        return if (qs == s.queues && active == s.active) s else QueueSet(active, qs)
    }

    /** The active queue's state is replaced (the player saved it). */
    fun saveActive(s: QueueSet, snap: QueueSnapshot): QueueSet {
        val a = s.activeQueue ?: return s
        if (a.snapshot == snap) return s
        return s.copy(queues = s.queues.map { if (it.id == a.id) it.copy(snapshot = snap) else it })
    }

    /** "Queue 2", "Queue 3", … : the first name not taken. */
    fun nextName(s: QueueSet): String = generateSequence(2) { it + 1 }.map { "Queue $it" }.first { n -> s.queues.none { it.name.equals(n, ignoreCase = true) } }

    /** Adds a queue (not active). Null when the cap is reached. */
    fun create(s: QueueSet, id: String, name: String?, snap: QueueSnapshot = QueueSnapshot()): QueueSet? {
        if (s.queues.size >= MAX || s.queues.any { it.id == id }) return null
        return s.copy(queues = s.queues + NamedQueue(id, name?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: nextName(s), snap))
    }

    fun rename(s: QueueSet, id: String, name: String): QueueSet {
        val n = name.trim().take(40)
        if (n.isEmpty() || s.queues.none { it.id == id && it.name != n }) return s
        return s.copy(queues = s.queues.map { if (it.id == id) it.copy(name = n) else it })
    }

    fun setActive(s: QueueSet, id: String): QueueSet = if (s.active == id || s.queues.none { it.id == id }) s else s.copy(active = id)

    /** Deletes a queue. Refused (null) for the last queue and for the active one (switch away first). */
    fun delete(s: QueueSet, id: String): QueueSet? {
        if (s.queues.size <= 1 || id == s.active || s.queues.none { it.id == id }) return null
        return s.copy(queues = s.queues.filterNot { it.id == id })
    }

    /** The queue to switch to before deleting [id]: the next one in the list, else the previous one. */
    fun fallbackFor(s: QueueSet, id: String): String? {
        val i = s.queues.indexOfFirst { it.id == id }
        if (i < 0 || s.queues.size <= 1) return null
        return (s.queues.getOrNull(i + 1) ?: s.queues[i - 1]).id
    }
}

/**
 * Queue persistence: restored on the next start (also for media-button resumption).
 *
 * Pack 6: holds up to 10 named queues in queues.json (each with its own tracks, index, position, shuffle, repeat);
 * [read]/[load]/[save] still mean "the active queue", so every existing call site (PlaybackService restore, the
 * periodic position save, Backup) works unchanged. On the first read, an existing queue.json (the old single queue)
 * becomes the active "My queue" exactly as it was. queue.json keeps being written with the active queue, so an
 * older UpZe installed over this one still restores the queue the user was on.
 */
class QueueStore(dir: File) {
    // 1.0.5: written even when it was never read this session (restore is skipped when something already plays).
    private val legacy = JsonFile(File(dir, "queue.json"), QueueSnapshot.serializer(), debounceMs = 1500, holdWritesUntilRead = false)
    // Pack 6: held until read; edits made before the read are replayed on top of what was on disk (no lost queues).
    private val file = JsonFile(File(dir, "queues.json"), QueueSet.serializer(), debounceMs = 1500, holdWritesUntilRead = true)
    private val lock = Any()
    @Volatile private var loaded = false
    private val beforeLoad = mutableListOf<(QueueSet) -> QueueSet>()
    private val _state = MutableStateFlow(QueueSet())
    /** All queues and which one is active (for the Queues sheet). */
    val state: StateFlow<QueueSet> = _state

    init { IoScope.launch { runCatching { ensureLoaded() } } } // read once in the background; no call site has to

    /** Blocking: background thread only. Reads queues.json, or migrates queue.json on the first start after the update. */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val disk = file.read()
            val migrated = disk == null
            val base = QueueSets.sane(disk ?: QueueSets.migrate(legacy.read()))
            _state.value = beforeLoad.fold(base) { s, f -> f(s) }
            beforeLoad.clear(); loaded = true
            if (migrated || _state.value !== base || base !== disk) file.save { _state.value }
        }
    }

    private fun edit(f: (QueueSet) -> QueueSet): QueueSet {
        val out = synchronized(lock) {
            val before = _state.value
            val after = f(before)
            if (!loaded) beforeLoad += f
            _state.value = after
            if (after === before) return after
            after
        }
        file.save { _state.value }
        out.activeQueue?.snapshot?.let { snap -> legacy.save { snap } }
        return out
    }

    /** Blocking: background thread only (PlaybackService.restoreQueue and Backup call it on Dispatchers.IO). The active queue. */
    fun read(): QueueSnapshot? { ensureLoaded(); return _state.value.activeQueue?.snapshot?.takeIf { it.tracks.isNotEmpty() } }
    /** 1.0.5: suspending read on Dispatchers.IO. The active queue. */
    suspend fun load(): QueueSnapshot? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { read() }
    /** Pack 6: every queue (Backup). Blocking: background thread only. */
    fun readAll(): QueueSet { ensureLoaded(); return _state.value }
    /** Saves the active queue's state (the player calls this on every change and every ~5 s while playing). */
    fun save(s: QueueSnapshot) { edit { QueueSets.saveActive(it, s) } }

    /** Pack 6: a new queue (not active). Returns its id, or null when 10 queues exist already. */
    fun create(name: String? = null, from: QueueSnapshot = QueueSnapshot()): String? {
        val id = "q-" + System.currentTimeMillis().toString(36) + (0..999).random().toString(36)
        var ok = false
        edit { s -> QueueSets.create(s, id, name, from)?.also { ok = true } ?: s }
        return id.takeIf { ok }
    }
    fun rename(id: String, name: String) { edit { QueueSets.rename(it, id, name) } }
    /** Marks [id] active. The player switch (save current, load this one) is PlaybackService.switchQueue. */
    fun setActive(id: String) { edit { QueueSets.setActive(it, id) } }
    /** Refused for the last queue and the active one; returns whether it was deleted. */
    fun delete(id: String): Boolean {
        var ok = false
        edit { s -> QueueSets.delete(s, id)?.also { ok = true } ?: s }
        return ok
    }
    fun queue(id: String): NamedQueue? = _state.value.queues.firstOrNull { it.id == id }

    /** 1.0.5: write the pending snapshot now (service stopping / task removed), on IO. */
    fun flush() { IoScope.launch { file.flush(); legacy.flush() } }
}
