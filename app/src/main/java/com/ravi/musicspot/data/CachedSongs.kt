package com.ravi.musicspot.data

import android.content.Context
import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.PlaybackService
import com.ravi.musicspot.source.Resolved
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * 1.0.2: "Cached songs". Every streamed song already lands in the media cache (Settings › Storage limit); this keeps an
 * index of which cache entry belongs to which song, so a song that was played to the end can be listed and played with
 * no network at all. The cache is still a cache: the oldest songs make room for new ones once the limit is reached
 * (downloads are the permanent way to keep a song).
 */
class CachedSongs(private val ctx: Context, dir: File) {
    @Serializable
    data class Entry(val track: Track, val cacheKey: String, val url: String, val info: StreamInfo, val atMs: Long)

    // 1.0.5: holdWritesUntilRead so a record() made before the index was read can't overwrite the file.
    private val file = JsonFile(File(dir, "cached-songs.json"), MapSerializer(String.serializer(), Entry.serializer()), holdWritesUntilRead = true)
    /**
     * 1.0.5: was `file.read()` in the constructor. [Graph.cached] is a lazy property first touched by Library's
     * Compose screen (main thread) or the loader thread, so the whole index (up to 2000 entries) was decoded on the
     * main thread. Now it is read on Dispatchers.IO; changes made meanwhile are merged on top of it.
     */
    @Volatile private var index: Map<String, Entry> = emptyMap()
    private val loaded = java.util.concurrent.CountDownLatch(1)
    /** Track ids removed before the index finished loading (so a removal isn't undone by the merge). */
    private val removedBeforeLoad = HashSet<String>()
    @Volatile private var cleared = false

    init {
        com.ravi.musicspot.core.IoScope.launch {
            val disk = try { file.read().orEmpty() } catch (e: Throwable) { emptyMap() }
            synchronized(this@CachedSongs) {
                val base = if (cleared) emptyMap() else disk - removedBeforeLoad
                index = base + index // entries recorded meanwhile win
                removedBeforeLoad.clear()
            }
            loaded.countDown()
        }
    }

    /** 1.0.5: background callers wait (bounded) for the index; the main thread never blocks on disk. */
    private fun awaitIndex() {
        if (loaded.count == 0L) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && android.os.Looper.myLooper() != null) return
        runCatching { loaded.await(3, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private val _complete = MutableStateFlow<List<Entry>>(emptyList())
    /** Songs whose whole stream is in the cache, newest first (refresh() recomputes it). */
    val complete: StateFlow<List<Entry>> = _complete

    /** Called once a stream is resolved for playback (loader thread). Live radio, device files and downloads are skipped. */
    fun record(track: Track, r: Resolved) {
        if (com.ravi.musicspot.Graph.incognito || track.source == SourceKind.RADIO || track.source == SourceKind.LOCAL || r.cacheKey.startsWith("file:") || !r.uri.startsWith("http")) return
        synchronized(this) {
            index = (index - track.id) + (track.id to Entry(track, r.cacheKey, r.uri, r.info, System.currentTimeMillis()))
            if (index.size > MAX) index = index.values.sortedByDescending { it.atMs }.take(MAX).associateBy { it.track.id }
        }
        file.save { index }
    }

    /** The cached stream for [track] when every byte of it is in the cache; otherwise null. */
    fun offline(track: Track): Resolved? {
        awaitIndex() // 1.0.5: loader thread: don't stream from the network just because the index is still loading
        val e = index[track.id] ?: return null
        if (!isComplete(e.cacheKey)) return null
        return Resolved(e.url, e.info, emptyMap(), e.cacheKey)
    }

    fun isComplete(key: String): Boolean = runCatching {
        val c = PlaybackService.cache(ctx)
        val len = androidx.media3.datasource.cache.ContentMetadata.getContentLength(c.getContentMetadata(key))
        len > 0 && c.isCached(key, 0, len)
    }.getOrDefault(false)

    /** Recompute [complete] (IO thread); also drops index entries the cache has already evicted. */
    fun refresh(): List<Entry> {
        awaitIndex() // 1.0.5
        val all = index.values.sortedByDescending { it.atMs }
        val done = all.filter { isComplete(it.cacheKey) }
        val gone = all.filter { e -> runCatching { PlaybackService.cache(ctx).getCachedSpans(e.cacheKey).isEmpty() }.getOrDefault(false) }
        if (gone.isNotEmpty()) { synchronized(this) { index = index - gone.map { it.track.id }.toSet() }; file.save { index } }
        _complete.value = done
        return done
    }

    fun bytes(entries: List<Entry>): Long = runCatching { val c = PlaybackService.cache(ctx); entries.sumOf { c.getCachedBytes(it.cacheKey, 0, androidx.media3.common.C.LENGTH_UNSET.toLong()) } }.getOrDefault(0L)

    fun remove(trackId: String) {
        // 1.0.5: removing the cache resource is disk I/O (SimpleCache); never on the caller's (often main) thread.
        val e = index[trackId]
        synchronized(this) { index = index - trackId; if (loaded.count > 0L) removedBeforeLoad += trackId }
        e?.let { com.ravi.musicspot.core.IoScope.launch { runCatching { PlaybackService.cache(ctx).removeResource(it.cacheKey) } } }
        if (e == null && loaded.count == 0L) return
        file.save { index }
        _complete.value = _complete.value.filterNot { it.track.id == trackId }
    }

    fun clearIndex() { synchronized(this) { index = emptyMap(); if (loaded.count > 0L) cleared = true }; file.save { index }; _complete.value = emptyList() }

    companion object { const val MAX = 2000 }
}
