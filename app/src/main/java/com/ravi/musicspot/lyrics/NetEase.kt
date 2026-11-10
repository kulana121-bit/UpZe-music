/*
 * Ported from OpenTune 1.0.4 (lyrics/NetEase.kt), Copyright (C) OpenTune contributors, GPL-3.0-or-later.
 * Modified for UpZe (C) 2026 Ravi, GPL-3.0-or-later. See LICENSE and THIRD_PARTY_NOTICES.md.
 */
package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Line-synced lyrics from NetEase Cloud Music's public web endpoints, with
 * no account or key: a search by title and artist lists songs with their
 * lengths, and the lyric endpoint returns the chosen one as LRC. A large
 * catalogue of Chinese, Korean, Japanese and Western songs.
 */
object NetEase {
    private const val BASE = "https://music.163.com/api"
    /** A song further than this from the track's length is a different recording. */
    private const val TOLERANCE_MS = 5_000L

    private val json = Json { ignoreUnknownKeys = true }

    fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val search = get(
            "$BASE/search/get".toHttpUrl().newBuilder()
                .addQueryParameter("s", "${cleaned.title} ${cleaned.artist}")
                .addQueryParameter("type", "1")
                .addQueryParameter("limit", "10")
                .build(),
        )
        val song = best(search, cleaned.title, durationMs, cleaned.artist) ?: return null
        val id = (song["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val lyricResp = get(
            "$BASE/song/lyric".toHttpUrl().newBuilder()
                .addQueryParameter("id", id.toString())
                .addQueryParameter("lv", "1")
                .addQueryParameter("kv", "1")
                .addQueryParameter("tv", "1")
                .addQueryParameter("yv", "1")
                .build()
        )

        // 1. Try word-by-word YRC format first
        val yrcText = (((lyricResp as? JsonObject)?.get("yrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
        if (!yrcText.isNullOrBlank()) {
            val yrcLines = KuGou.clean(YrcParser.parse(yrcText, durationMs), cleaned.title, cleaned.artist)
            if (yrcLines.isNotEmpty()) {
                val recordingMs = (song["duration"] as? JsonPrimitive)?.longOrNull ?: 0
                return Lyrics.Synced(yrcLines, "NetEase", recordingMs = recordingMs)
            }
        }

        // 2. Fall back to standard line-synced LRC
        val lrc = lrcOf(lyricResp) ?: return null
        val lines = KuGou.clean(LrcParser.parse(lrc, durationMs), cleaned.title, cleaned.artist)
        val recordingMs = (song["duration"] as? JsonPrimitive)?.longOrNull ?: 0
        return if (lines.isEmpty()) null else Lyrics.Synced(lines, "NetEase", recordingMs = recordingMs)
    }

    private val VERSION = Regex("""\b(remix|live|instrumental|karaoke|acoustic|cover|sped up|slowed|nightcore|8d)\b|伴奏|翻唱|现场""", RegexOption.IGNORE_CASE)

    /**
     * The song whose title matches and whose length is closest to
     * [durationMs], leaving out remixes, live cuts and sped-up edits unless
     * [title] is one.
     */
    internal fun pick(search: JsonElement?, title: String, durationMs: Long, artist: String = ""): Long? =
        best(search, title, durationMs, artist)?.let { (it["id"] as? JsonPrimitive)?.longOrNull }

    /** As [pick], the whole entry; one by [artist] wins over a same-titled song by someone else. */
    internal fun best(search: JsonElement?, title: String, durationMs: Long, artist: String = ""): JsonObject? {
        val songs = (((search as? JsonObject)?.get("result") as? JsonObject)?.get("songs") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val want = key(title)
        val wantsVersion = VERSION.containsMatchIn(title)
        fun gap(o: JsonObject) = if (durationMs <= 0) 0L else abs(((o["duration"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE / 2) - durationMs)
        fun artists(o: JsonObject) = (o["artists"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
        val fitting = songs
            .filter { o -> val name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(); key(name) == want || key(name).startsWith(want) }
            .filter { wantsVersion || !VERSION.containsMatchIn((it["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            .filter { gap(it) <= TOLERANCE_MS }
        val byArtist = if (artist.isBlank()) emptyList() else fitting.filter { o -> artists(o).any { LyricsQuality.sameArtist(artist, it) && LyricsQuality.sameArtist(it, artist) } }
        return byArtist.ifEmpty { fitting }.minByOrNull(::gap)
    }

    /** The LRC text in a lyric answer; null when the song has none or only "instrumental". */
    internal fun lrcOf(answer: JsonElement?): String? =
        (((answer as? JsonObject)?.get("lrc") as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.contains('[') && it.lines().count { l -> l.contains(']') && l.substringAfter(']').isNotBlank() } >= 3 }

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun get(url: HttpUrl): JsonElement? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            .header("Referer", "https://music.163.com/")
            .build()
        return Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("NetEase answered ${r.code}")
            r.body?.string()?.let(json::parseToJsonElement)
        }
    }
}
