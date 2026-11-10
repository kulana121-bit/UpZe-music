package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * AMLL (Apple Music-like Lyrics) database provider:
 * Provides syllable-synchronized word-by-word TTML lyrics without authentication.
 * Includes automatic fallback to synced LRCLIB format.
 */
object Amll {
    private const val BASE = "https://api.amll.dev"
    private const val UA = "UpZe/1.0 (Android)"

    private val json = Json { ignoreUnknownKeys = true }

    private val P_RE = Regex("""<p\s+begin="([^"]+)"\s+end="([^"]+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val SPAN_RE = Regex("""<span\s+begin="([^"]+)"\s+end="([^"]+)"[^>]*>([^<]*)</span>(\s*)""")
    private val TAG_STRIP_RE = Regex("""<[^>]+>""")

    fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val query = URLEncoder.encode("${cleaned.title} ${cleaned.artist}", "UTF-8")

        // 1. Try AMLL TTML syllable-level word-by-word lyrics
        val searchUrl = "$BASE/v1/lyrics/search?q=$query&pageSize=5"
        val searchResp = get(searchUrl)
        val items = ((searchResp?.get("data") as? JsonObject)?.get("items") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

        if (items.isNotEmpty()) {
            val best = items.firstOrNull { item ->
                val names = (item["musicNames"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                names.any { LyricsQuality.sameTitle(cleaned.title, it) }
            } ?: items.first()

            val id = (best["id"] as? JsonPrimitive)?.longOrNull
            if (id != null) {
                val getUrl = "$BASE/v1/lyrics/get?id=$id"
                get(getUrl)?.let { getResp ->
                    val data = getResp["data"] as? JsonObject
                    val ttml = (data?.get("lyrics") as? JsonPrimitive)?.contentOrNull
                    if (!ttml.isNullOrBlank()) {
                        val lines = parseTtml(ttml, durationMs)
                        if (lines.isNotEmpty()) {
                            return Lyrics.Synced(lines, "AMLL (TTML)", recordingMs = durationMs)
                        }
                    }
                }
            }
        }

        // 2. Fall back to AMLL LRCLIB-compatible mirror
        val lrcUrl = "$BASE/v1/lrclib/get?track_name=${URLEncoder.encode(cleaned.title, "UTF-8")}&artist_name=${URLEncoder.encode(cleaned.artist, "UTF-8")}"
        get(lrcUrl)?.let { lrcResp ->
            val synced = (lrcResp["syncedLyrics"] as? JsonPrimitive)?.contentOrNull
            if (!synced.isNullOrBlank()) {
                val lines = LrcParser.parse(synced, durationMs)
                if (lines.isNotEmpty()) {
                    return Lyrics.Synced(lines, "AMLL", recordingMs = durationMs)
                }
            }
        }

        return null
    }

    private fun parseTtml(ttml: String, durationMs: Long): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        for (pMatch in P_RE.findAll(ttml)) {
            val pStart = parseTime(pMatch.groupValues[1])
            val pEnd = parseTime(pMatch.groupValues[2])
            val pBody = pMatch.groupValues[3]

            val words = mutableListOf<LyricWord>()
            for (sMatch in SPAN_RE.findAll(pBody)) {
                val wStart = parseTime(sMatch.groupValues[1])
                val wEnd = parseTime(sMatch.groupValues[2])
                val text = sMatch.groupValues[3] + sMatch.groupValues[4]
                if (text.isNotEmpty()) {
                    words.add(LyricWord(text, wStart, wEnd))
                }
            }

            val fullText = if (words.isNotEmpty()) {
                words.joinToString("") { it.text }
            } else {
                TAG_STRIP_RE.replace(pBody, "").trim()
            }.replace(Regex("""\s+"""), " ").trim()

            if (fullText.isNotEmpty()) {
                val isWordSynced = words.size >= 2
                lines.add(
                    LyricLine(
                        startMs = pStart,
                        endMs = pEnd,
                        text = fullText,
                        words = if (isWordSynced) words else LrcParser.spreadWords(fullText, pStart, pEnd),
                        wordSynced = isWordSynced
                    )
                )
            }
        }

        return lines.sortedBy { it.startMs }
    }

    private fun parseTime(t: String?): Long {
        if (t.isNullOrBlank()) return 0L
        val trimmed = t.trim()
        if (trimmed.endsWith("ms")) return trimmed.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: 0L
        if (trimmed.endsWith("s")) return (trimmed.removeSuffix("s").toDoubleOrNull()?.times(1000L))?.toLong() ?: 0L

        val parts = trimmed.split(':')
        val sec = parts.lastOrNull()?.toDoubleOrNull() ?: 0.0
        val min = parts.getOrNull(parts.size - 2)?.toLongOrNull() ?: 0L
        val hr = parts.getOrNull(parts.size - 3)?.toLongOrNull() ?: 0L

        return ((hr * 3600L + min * 60L) * 1000L + (sec * 1000.0).toLong()).coerceAtLeast(0L)
    }

    private fun get(url: String): JsonObject? {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        return runCatching {
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.string()?.let { json.parseToJsonElement(it) as? JsonObject }
            }
        }.getOrNull()
    }
}
