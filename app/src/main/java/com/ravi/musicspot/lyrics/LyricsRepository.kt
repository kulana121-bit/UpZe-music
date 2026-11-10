package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest

/** What a provider is asked for. */
data class LyricsQuery(val title: String, val artist: String, val album: String?, val durationMs: Long, val videoId: String? = null)

interface LyricsProvider {
    val id: String
    val label: String
    suspend fun lookup(q: LyricsQuery): Lyrics?
}

/** LRCLIB: open, free, synced LRC (and plain). */
object LrcLib : LyricsProvider {
    override val id = "lrclib"; override val label = "LRCLIB (synced)"
    private const val UA = "UpZe/2.0 (https://github.com/ ; GPL-3.0, based on OpenTune)"

    override suspend fun lookup(q: LyricsQuery): Lyrics? {
        val c = TrackNameCleaner.clean(q.title, q.artist)
        val get = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", c.title).addQueryParameter("artist_name", c.artist)
            .apply { q.album?.takeIf { it.isNotBlank() }?.let { addQueryParameter("album_name", it) } }
            .apply { if (q.durationMs > 0) addQueryParameter("duration", (q.durationMs / 1000).toString()) }.build().toString()
        // 1.0.5: runCatching swallowed CancellationException, so a cancelled lookup (track changed) went on to the search request.
        val direct = try { Http.json.parseToJsonElement(Http.getString(get, mapOf("User-Agent" to UA))) as? JsonObject }
            catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        direct?.let { parse(it, q.durationMs)?.let { l -> return l } }
        val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", c.title).addQueryParameter("artist_name", c.artist).build().toString()
        val arr = Http.json.parseToJsonElement(Http.getString(search, mapOf("User-Agent" to UA))) as? JsonArray ?: return null
        val candidates = arr.mapNotNull { it as? JsonObject }.filter { o ->
            val d = ((o["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) * 1000
            (q.durationMs <= 0 || d <= 0 || kotlin.math.abs(d - q.durationMs) <= 5_000) &&
                LyricsQuality.sameTitle(c.title, (o["trackName"] as? JsonPrimitive)?.contentOrNull)
        }
        return candidates.sortedByDescending { (it["syncedLyrics"] as? JsonPrimitive)?.contentOrNull != null }.firstNotNullOfOrNull { parse(it, q.durationMs) }
    }

    fun parse(o: JsonObject, durationMs: Long): Lyrics? {
        if ((o["instrumental"] as? JsonPrimitive)?.contentOrNull == "true") return null
        (o["syncedLyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { lrc ->
            val lines = LrcParser.parse(lrc, durationMs)
            if (lines.isNotEmpty()) return Lyrics.Synced(lines, "LRCLIB")
        }
        return (o["plainLyrics"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Lyrics.Plain(it, "LRCLIB") }
    }
}

object LyricsPlusProvider : LyricsProvider {
    override val id = "lyricsplus"; override val label = "LyricsPlus (word-by-word)"
    override suspend fun lookup(q: LyricsQuery) = LyricsPlus.lookup(q.title, q.artist, q.durationMs, q.album)
}

object KuGouProvider : LyricsProvider {
    override val id = "kugou"; override val label = "KuGou (synced, strong for Asian music)"
    override suspend fun lookup(q: LyricsQuery) = runInterruptible(Dispatchers.IO) { KuGou.lookup(q.title, q.artist, q.durationMs) }
}

object NetEaseProvider : LyricsProvider {
    override val id = "netease"; override val label = "NetEase Cloud Music (synced & word-by-word)"
    override suspend fun lookup(q: LyricsQuery) = runInterruptible(Dispatchers.IO) { NetEase.lookup(q.title, q.artist, q.durationMs) }
}

object QQMusicProvider : LyricsProvider {
    override val id = "qqmusic"; override val label = "QQ Music (synced & QRC)"
    override suspend fun lookup(q: LyricsQuery) = runInterruptible(Dispatchers.IO) { QQMusic.lookup(q.title, q.artist, q.durationMs) }
}

object MusixmatchProvider : LyricsProvider {
    override val id = "musixmatch"; override val label = "Musixmatch (word-by-word RichSync)"
    override suspend fun lookup(q: LyricsQuery) = runInterruptible(Dispatchers.IO) { Musixmatch.lookup(q.title, q.artist, q.durationMs) }
}

object AmllProvider : LyricsProvider {
    override val id = "amll"; override val label = "AMLL (Apple Music syllable TTML)"
    override suspend fun lookup(q: LyricsQuery) = runInterruptible(Dispatchers.IO) { Amll.lookup(q.title, q.artist, q.durationMs) }
}

object JioSaavnLyricsProvider : LyricsProvider {
    override val id = "jiosaavn_lyrics"; override val label = "JioSaavn Lyrics (Indian)"
    override suspend fun lookup(q: LyricsQuery) = runInterruptible(Dispatchers.IO) { JioSaavnLyrics.lookup(q.title, q.artist, q.durationMs) }
}

object LyricsOvhProvider : LyricsProvider {
    override val id = "lyricsovh"; override val label = "Lyrics.ovh (plain)"
    override suspend fun lookup(q: LyricsQuery) = runInterruptible(Dispatchers.IO) { LyricsOvh.lookup(q.title, q.artist) }
}

/** YouTube Music's own lyrics tab (plain text, licensed provider credit shown). */
class YtLyricsProvider(private val fetch: suspend (String) -> Pair<String, String?>?) : LyricsProvider {
    override val id = "ytmusic"; override val label = "YouTube Music (plain)"
    override suspend fun lookup(q: LyricsQuery): Lyrics? {
        val vid = q.videoId ?: return null
        val (text, credit) = fetch(vid) ?: return null
        return Lyrics.Plain(text, credit?.let { "YouTube Music · $it" } ?: "YouTube Music")
    }
}

/**
 * Walks providers in the user's order. A synced result wins immediately; the first plain result is kept as a
 * fallback while later providers are asked for synced lines. Each provider gets its own time budget, and one
 * failing never stops the chain.
 */
object LyricsChain {
    /**
     * 1.0.5: one provider walk per track. A request for another track cancels the walks still running for earlier
     * tracks (their network calls stop), and a result that finishes after the track changed is never returned (the
     * caller gets a CancellationException instead), so stale lyrics can't be published or cached. Callers asking
     * for the same track at once (the lyrics pane and the capsule) share one walk; it is cancelled when the last of
     * them stops waiting.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.ravi.musicspot.core.BackgroundFaults)
    private val lock = Any()
    private class Shared(val track: String, val deferred: Deferred<Lyrics?>) { var waiters = 0 }
    private val inflight = HashMap<String, Shared>()
    @Volatile private var latestTrack: String? = null

    /** The track a query is for (same song, same length bucket → same id). */
    internal fun trackKey(q: LyricsQuery): String = q.videoId?.let { "v:$it" } ?: "${q.title}|${q.artist}|${q.durationMs / 2000}"

    suspend fun find(providers: List<LyricsProvider>, q: LyricsQuery, perProviderMs: Long = 8_000, onTried: (String, String) -> Unit = { _, _ -> }): Lyrics? {
        val track = trackKey(q)
        val key = track + "#" + providers.joinToString(",") { it.id } + "#" + perProviderMs
        val shared = synchronized(lock) {
            if (latestTrack != track) {
                latestTrack = track
                // The track changed: everything still running for another track is stale.
                inflight.values.filter { it.track != track }.forEach { it.deferred.cancel(CancellationException("lyrics: track changed")) }
                inflight.entries.removeAll { it.value.track != track }
            }
            (inflight[key]?.takeIf { it.deferred.isActive } ?: Shared(track, scope.async { walk(providers, q, perProviderMs, onTried) }).also { inflight[key] = it })
                .also { it.waiters++ }
        }
        try {
            val r = shared.deferred.await()
            // Check the track before publishing: a newer request means this answer is for a song no longer shown.
            if (latestTrack != track) throw CancellationException("lyrics: stale result for a previous track")
            return r
        } finally {
            synchronized(lock) {
                shared.waiters--
                if (shared.waiters <= 0) {
                    if (shared.deferred.isActive) shared.deferred.cancel()
                    if (inflight[key] === shared) inflight.remove(key)
                }
            }
        }
    }

    /**
     * 1.0.7: word-by-word lyrics win over line-by-line, and line-by-line over plain (it used to stop at the first
     * synced answer, so a line-synced LRCLIB hit hid QQ Music's or AMLL's word timing). Providers are asked at the
     * same time (at most [PARALLEL] at once, each with its own budget) and answers are read in the user's order:
     * the first word-synced answer returns at once and cancels the rest; otherwise the first line-synced one, then
     * the first plain one. Total wait is the slowest needed provider, not the sum of all of them.
     */
    private suspend fun walk(providers: List<LyricsProvider>, q: LyricsQuery, perProviderMs: Long, onTried: (String, String) -> Unit): Lyrics? =
        kotlinx.coroutines.coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL)
            val jobs = providers.map { p ->
                p to async {
                    gate.acquire()
                    try {
                        try { Answer(withTimeoutOrNull(perProviderMs) { p.lookup(q) }, null) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { Answer(null, "error: ${e.message}") }
                    } finally { gate.release() }
                }
            }
            var line: Lyrics? = null
            var plain: Lyrics? = null
            for ((p, job) in jobs) {
                val (r, err) = job.await()
                onTried(p.id, err ?: LyricsRank.label(r)) // reported in the user's order, once per provider
                when (LyricsRank.of(r)) {
                    LyricsRank.WORD -> { jobs.forEach { it.second.cancel() }; return@coroutineScope r }
                    LyricsRank.LINE -> if (line == null) line = r
                    LyricsRank.PLAIN -> if (plain == null) plain = r
                    LyricsRank.NONE -> {}
                }
            }
            line ?: plain
        }

    private const val PARALLEL = 4
    private data class Answer(val lyrics: Lyrics?, val error: String?)
}

/** 1.0.7: what a provider's answer is worth (pure, unit-tested): word-by-word > line-by-line > plain > nothing. */
enum class LyricsRank {
    WORD, LINE, PLAIN, NONE;
    companion object {
        fun of(r: Lyrics?): LyricsRank = when (r) {
            null -> NONE
            is Lyrics.Synced -> if (r.lines.any { it.wordSynced }) WORD else if (r.lines.isEmpty()) NONE else LINE
            is Lyrics.Plain -> PLAIN
        }
        fun label(r: Lyrics?): String = when (of(r)) { WORD -> "word-synced"; LINE -> "synced"; PLAIN -> "plain"; NONE -> "none" }
        /** The best of [answers] given in the user's order. */
        fun best(answers: List<Lyrics?>): Lyrics? =
            answers.firstOrNull { of(it) == WORD } ?: answers.firstOrNull { of(it) == LINE } ?: answers.firstOrNull { of(it) == PLAIN }
    }
}

@Serializable
private data class CachedLyrics(val lyrics: Lyrics? = null, val atMs: Long)

/** Disk cache (bounded to [maxFiles]); a miss is cached for a day so a song without lyrics isn't re-asked on every play. */
class LyricsCache(private val dir: File, private val maxFiles: Int = 400) {
    private fun file(key: String) = File(dir, MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun get(key: String): Pair<Boolean, Lyrics?> {
        val f = file(key)
        if (!f.isFile) return false to null
        val c = runCatching { Http.json.decodeFromString(CachedLyrics.serializer(), f.readText()) }.getOrNull() ?: return false to null
        if (c.lyrics == null && System.currentTimeMillis() - c.atMs > 86_400_000L) return false to null
        f.setLastModified(System.currentTimeMillis())
        return true to c.lyrics
    }

    fun put(key: String, lyrics: Lyrics?) {
        dir.mkdirs()
        // 1.0.5: atomic (temp file + rename): two writers for the same song, or a crash mid-write, can no longer leave
        // a half-written JSON behind. Callers run on Dispatchers.IO (Graph.lyricsFor).
        runCatching {
            val f = file(key)
            val tmp = File(dir, f.name + "." + Thread.currentThread().id + ".tmp")
            tmp.writeText(Http.json.encodeToString(CachedLyrics.serializer(), CachedLyrics(lyrics, System.currentTimeMillis())))
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) tmp.delete() }
        }
        val files = dir.listFiles { x -> x.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: return
        if (files.size > maxFiles) files.take(files.size - maxFiles).forEach { it.delete() }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}

/**
 * 1.0.6 offline synced lyrics for downloaded songs. Stored next to the downloads (filesDir/downloads/lyrics), never
 * evicted by [LyricsCache]'s size limit, and removed with the download. Each song has:
 *  - `<id>.json`: the lyrics exactly as found (word timing, duet voices, parts) plus the romanized lines, read back
 *    by Graph.lyricsFor before any network lookup, so synced and romanized lyrics work fully offline;
 *  - `<id>.lrc`: a standard LRC copy of synced lyrics (enhanced word tags when word-synced) for portability.
 */
@Serializable
data class OfflineLyrics(val lyrics: Lyrics? = null, val roman: List<String?> = emptyList(), val atMs: Long = 0)

class OfflineLyricsStore(private val dir: File) {
    private fun base(trackId: String) = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
    private fun json(trackId: String) = File(dir, base(trackId) + ".json")
    private fun lrc(trackId: String) = File(dir, base(trackId) + ".lrc")

    fun has(trackId: String): Boolean = json(trackId).isFile

    /** Blocking (Dispatchers.IO). Null when nothing is stored for the song or the file is unreadable. */
    fun get(trackId: String): OfflineLyrics? {
        val f = json(trackId)
        if (!f.isFile) return null
        return runCatching { Http.json.decodeFromString(OfflineLyrics.serializer(), f.readText()) }.getOrNull()
    }

    /** Blocking (Dispatchers.IO). Atomic per file (temp + rename). */
    fun put(trackId: String, lyrics: Lyrics?, roman: List<String?>) {
        dir.mkdirs()
        writeAtomic(json(trackId), Http.json.encodeToString(OfflineLyrics.serializer(), OfflineLyrics(lyrics, roman, System.currentTimeMillis())))
        if (lyrics is Lyrics.Synced) writeAtomic(lrc(trackId), LrcWriter.write(lyrics)) else lrc(trackId).delete()
    }

    fun delete(trackId: String) { json(trackId).delete(); lrc(trackId).delete() }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + "." + Thread.currentThread().id + ".tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) tmp.delete() }
        }.onFailure { tmp.delete() }
    }
}

/** 1.0.6: synced lyrics → LRC text ("[mm:ss.xx]line", enhanced "<mm:ss.xx>word" tags when word-synced). Pure. */
object LrcWriter {
    fun stamp(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val min = t / 60_000
        val sec = (t % 60_000) / 1000
        val cs = (t % 1000) / 10
        return "%02d:%02d.%02d".format(min, sec, cs)
    }

    fun write(l: Lyrics.Synced): String = buildString {
        appendLine("[re:UpZe]")
        appendLine("[by:${l.source.replace(']', ')')}]")
        for (line in l.lines) {
            append('[').append(stamp(line.startMs)).append(']')
            if (line.wordSynced && line.words.isNotEmpty()) line.words.forEach { w -> append('<').append(stamp(w.startMs)).append('>').append(w.text) }
            else append(line.text)
            append('\n')
        }
    }
}
