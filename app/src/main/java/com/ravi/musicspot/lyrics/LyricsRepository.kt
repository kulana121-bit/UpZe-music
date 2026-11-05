package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        val direct = runCatching { Http.json.parseToJsonElement(Http.getString(get, mapOf("User-Agent" to UA))) as? JsonObject }.getOrNull()
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
    override val id = "netease"; override val label = "NetEase Cloud Music (synced)"
    override suspend fun lookup(q: LyricsQuery) = runInterruptible(Dispatchers.IO) { NetEase.lookup(q.title, q.artist, q.durationMs) }
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
    suspend fun find(providers: List<LyricsProvider>, q: LyricsQuery, perProviderMs: Long = 8_000, onTried: (String, String) -> Unit = { _, _ -> }): Lyrics? {
        var plain: Lyrics? = null
        for (p in providers) {
            val r = try {
                withTimeoutOrNull(perProviderMs) { p.lookup(q) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { onTried(p.id, "error: ${e.message}"); continue }
            onTried(p.id, when (r) { null -> "none"; is Lyrics.Synced -> "synced"; is Lyrics.Plain -> "plain" })
            when (r) {
                is Lyrics.Synced -> return r
                is Lyrics.Plain -> if (plain == null) plain = r
                null -> {}
            }
        }
        return plain
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
        runCatching { file(key).writeText(Http.json.encodeToString(CachedLyrics.serializer(), CachedLyrics(lyrics, System.currentTimeMillis()))) }
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > maxFiles) files.take(files.size - maxFiles).forEach { it.delete() }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
