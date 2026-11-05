package com.ravi.musicspot.source

import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track

/** What a source hands the player for one track. [uri] is https://, content:// or file://. */
data class Resolved(
    val uri: String,
    val info: StreamInfo,
    val headers: Map<String, String> = emptyMap(),
    /** Stable cache key for these exact bytes (source + id + rendition). */
    val cacheKey: String,
    /** Set when this plays from another source because [fallbackFrom] (YouTube) was exhausted or excluded. */
    val fallbackFrom: SourceKind? = null,
)

interface MusicSource {
    val kind: SourceKind
    /** Whether the user has this source switched on and configured. */
    fun available(): Boolean
    suspend fun search(query: String, limit: Int = 20): List<Track>
    suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved
    /** A cheap real request used by Diagnostics; returns a short description of what answered. */
    suspend fun probe(): String
}

class SourceUnavailableException(msg: String, cause: Throwable? = null) : java.io.IOException(msg, cause)

/** Parses "3:45" / "1:02:03" into ms. */
fun parseClock(text: String?): Long {
    if (text.isNullOrBlank()) return 0
    val parts = text.trim().split(':').mapNotNull { it.trim().toLongOrNull() }
    if (parts.isEmpty()) return 0
    return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
}

/** HTML entity cleanup for catalog APIs that return escaped titles (JioSaavn). */
fun unescapeHtml(s: String?): String = (s ?: "")
    .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

/** v2.0.4: status of a 2-byte ranged GET of a resolved stream (local/file URIs are not probed). */
object StreamProbe {
    suspend fun status(r: Resolved): Int? {
        if (!r.uri.startsWith("https://")) return null
        val rb = okhttp3.Request.Builder().url(r.uri).header("Range", "bytes=0-1")
        r.headers.forEach { (k, v) -> rb.header(k, v) }
        return kotlinx.coroutines.withTimeoutOrNull(6_000) {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                com.ravi.musicspot.core.Http.client.newCall(rb.build()).execute().use { it.code }
            }
        }
    }
}
