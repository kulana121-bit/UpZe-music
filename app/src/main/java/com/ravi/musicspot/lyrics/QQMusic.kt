package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import java.net.URLEncoder
import java.util.Base64
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Line-synced and word-synced lyrics from QQ Music's public endpoints.
 * Requires no key or account.
 */
object QQMusic {
    private const val SEARCH_BASE = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val LYRIC_BASE = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
    private const val TOLERANCE_MS = 6_000L

    private val json = Json { ignoreUnknownKeys = true }

    fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val query = "${cleaned.title} ${cleaned.artist}".trim()

        val searchUrl = SEARCH_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("p", "1")
            .addQueryParameter("n", "8")
            .addQueryParameter("w", query)
            .addQueryParameter("format", "json")
            .build()

        val searchResp = get(searchUrl.toString()) ?: return null
        val song = pick(searchResp, cleaned.title, durationMs) ?: return null
        val songmid = (song["songmid"] as? JsonPrimitive)?.contentOrNull ?: return null
        val songDur = ((song["interval"] as? JsonPrimitive)?.longOrNull ?: 0L) * 1000L

        // 1. Try BetterLyrics / QRC syllable word-timed endpoint first
        val betterUrl = "https://lyrics.portato.dev/qq/getLyrics?title=${URLEncoder.encode(cleaned.title, "UTF-8")}&artist=${URLEncoder.encode(cleaned.artist, "UTF-8")}"
        get(betterUrl)?.let { resp ->
            val qrc = ((resp as? JsonObject)?.get("qrc") as? JsonPrimitive)?.contentOrNull
                ?: ((resp as? JsonObject)?.get("lyrics") as? JsonPrimitive)?.contentOrNull
            if (!qrc.isNullOrBlank() && qrc.contains("(")) {
                val qrcLines = KuGou.clean(YrcParser.parse(qrc, durationMs), cleaned.title, cleaned.artist)
                if (qrcLines.isNotEmpty() && qrcLines.any { it.wordSynced }) {
                    return Lyrics.Synced(qrcLines, "QQ Music (QRC)", recordingMs = songDur)
                }
            }
        }

        // 2. Fetch from official QQ Music lyrics endpoint
        val lyricUrl = LYRIC_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("songmid", songmid)
            .addQueryParameter("format", "json")
            .addQueryParameter("nobase64", "1")
            .addQueryParameter("qrc", "1")
            .build()

        val lyricResp = get(lyricUrl.toString()) ?: return null
        val rawLrc = ((lyricResp as? JsonObject)?.get("lyric") as? JsonPrimitive)?.contentOrNull
            ?: return null

        val lrc = if (rawLrc.startsWith("[")) {
            rawLrc
        } else {
            // In case nobase64 was ignored by server
            runCatching { String(Base64.getDecoder().decode(rawLrc), Charsets.UTF_8) }.getOrNull() ?: rawLrc
        }

        if (!lrc.contains("[")) return null

        // If returned text contains QRC syllable timestamps: (start,dur)
        val lines = if (lrc.contains("(") && lrc.contains(")")) {
            val qrcLines = KuGou.clean(YrcParser.parse(lrc, durationMs), cleaned.title, cleaned.artist)
            if (qrcLines.isNotEmpty()) qrcLines else KuGou.clean(LrcParser.parse(lrc, durationMs), cleaned.title, cleaned.artist)
        } else {
            KuGou.clean(LrcParser.parse(lrc, durationMs), cleaned.title, cleaned.artist)
        }
        return if (lines.isEmpty()) null else Lyrics.Synced(lines, "QQ Music", recordingMs = songDur)
    }

    private fun pick(search: JsonElement?, title: String, durationMs: Long): JsonObject? {
        val data = (search as? JsonObject)?.get("data") as? JsonObject ?: return null
        val songList = ((data["song"] as? JsonObject)?.get("list") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        if (songList.isEmpty()) return null

        fun gap(o: JsonObject): Long {
            if (durationMs <= 0) return 0L
            val dur = ((o["interval"] as? JsonPrimitive)?.longOrNull ?: 0L) * 1000L
            return if (dur <= 0) Long.MAX_VALUE / 2 else abs(dur - durationMs)
        }

        return songList.filter { gap(it) <= TOLERANCE_MS }.minByOrNull(::gap) ?: songList.firstOrNull()
    }

    private fun get(url: String): JsonElement? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Referer", "https://y.qq.com")
            .build()

        return runCatching {
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null
                else r.body?.string()?.let(json::parseToJsonElement)
            }
        }.getOrNull()
    }
}
