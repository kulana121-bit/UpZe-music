package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Musixmatch mobile / desktop flow:
 * Anonymous 600-second session token without account, retrieving word-by-word RichSync JSON
 * or synchronized line-by-line LRC. Strong global, Hindi, Punjabi and English coverage.
 */
object Musixmatch {
    private const val ROOT = "https://apic-desktop.musixmatch.com/ws/1.1/"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"

    private val json = Json { ignoreUnknownKeys = true }
    private val tokenRef = AtomicReference<Pair<String, Long>?>(null)

    private fun getToken(): String? {
        val now = System.currentTimeMillis()
        tokenRef.get()?.let { (tok, expiry) ->
            if (now < expiry) return tok
        }

        return runCatching {
            val url = "${ROOT}token.get?app_id=$APP_ID&user_language=en&t=$now"
            val req = Request.Builder().url(url).header("User-Agent", UA).header("Cookie", "awc=true").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = json.parseToJsonElement(body) as? JsonObject ?: return null
                val tok = ((root["message"] as? JsonObject)?.get("body") as? JsonObject)
                    ?.get("user_token")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return null
                
                tokenRef.set(tok to (now + 540_000L)) // cache for 9 minutes
                tok
            }
        }.getOrNull()
    }

    fun lookup(title: String, artist: String, durationMs: Long): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val token = getToken()
        val query = URLEncoder.encode("${cleaned.title} ${cleaned.artist}", "UTF-8")
        val now = System.currentTimeMillis()

        // 1. Search for matching track
        val searchUrl = "${ROOT}track.search?q=$query&page_size=5&page=1&app_id=$APP_ID${token?.let { "&usertoken=$it" }.orEmpty()}&t=$now"
        val searchResp = get(searchUrl) ?: return null
        val tracks = (((searchResp["message"] as? JsonObject)?.get("body") as? JsonObject)
            ?.get("track_list") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { it["track"] as? JsonObject }
        if (tracks.isEmpty()) return null

        val bestTrack = tracks.firstOrNull { t ->
            val name = (t["track_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            LyricsQuality.sameTitle(cleaned.title, name)
        } ?: tracks.first()

        val trackId = (bestTrack["track_id"] as? JsonPrimitive)?.intOrNull?.toString()
            ?: (bestTrack["track_id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val songDur = ((bestTrack["track_length"] as? JsonPrimitive)?.longOrNull ?: 0L) * 1000L

        // 2. Try Word-by-Word RichSync first
        val hasRich = (bestTrack["has_richsync"] as? JsonPrimitive)?.intOrNull == 1
        if (hasRich) {
            val richUrl = "${ROOT}track.richsync.get?track_id=$trackId&app_id=$APP_ID${token?.let { "&usertoken=$it" }.orEmpty()}&t=${System.currentTimeMillis()}"
            get(richUrl)?.let { resp ->
                val bodyObj = (resp["message"] as? JsonObject)?.get("body") as? JsonObject
                val richBody = (bodyObj?.get("richsync") as? JsonObject)?.get("richsync_body")?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (!richBody.isNullOrBlank()) {
                    val lines = parseRichsync(richBody, durationMs)
                    if (lines.isNotEmpty()) {
                        return Lyrics.Synced(lines, "Musixmatch", recordingMs = songDur)
                    }
                }
            }
        }

        // 3. Fall back to Line-by-Line Synced Subtitles (LRC)
        val subUrl = "${ROOT}track.subtitle.get?track_id=$trackId&subtitle_format=lrc&app_id=$APP_ID${token?.let { "&usertoken=$it" }.orEmpty()}&t=${System.currentTimeMillis()}"
        get(subUrl)?.let { resp ->
            val bodyObj = (resp["message"] as? JsonObject)?.get("body") as? JsonObject
            val lrc = (bodyObj?.get("subtitle") as? JsonObject)?.get("subtitle_body")?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (!lrc.isNullOrBlank() && lrc.contains('[')) {
                val lines = LrcParser.parse(lrc, durationMs)
                if (lines.isNotEmpty()) {
                    return Lyrics.Synced(lines, "Musixmatch", recordingMs = songDur)
                }
            }
        }

        return null
    }

    private fun parseRichsync(jsonStr: String, durationMs: Long): List<LyricLine> {
        return runCatching {
            val arr = json.parseToJsonElement(jsonStr) as? JsonArray ?: return emptyList()
            val lines = mutableListOf<LyricLine>()

            for (item in arr.filterIsInstance<JsonObject>()) {
                val lineTs = (item["ts"] as? JsonPrimitive)?.doubleOrNull ?: continue
                val lineTe = (item["te"] as? JsonPrimitive)?.doubleOrNull ?: (lineTs + 3.0)
                val lineStartMs = (lineTs * 1000).toLong()
                val lineEndMs = (lineTe * 1000).toLong()

                val lArr = (item["l"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val words = mutableListOf<LyricWord>()

                for (wObj in lArr) {
                    val c = (wObj["c"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val o = (wObj["o"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                    val wStart = (lineStartMs + (o * 1000).toLong())
                    // Word duration estimate
                    val wEnd = wStart + 350L
                    words.add(LyricWord(c, wStart, wEnd))
                }

                val fullText = (if (words.isNotEmpty()) words.joinToString("") { it.text } else (item["x"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    .replace(Regex("""\s+"""), " ").trim()

                if (fullText.isNotEmpty()) {
                    val isWordSynced = words.size >= 2
                    lines.add(
                        LyricLine(
                            startMs = lineStartMs,
                            endMs = lineEndMs,
                            text = fullText,
                            words = if (isWordSynced) words else LrcParser.spreadWords(fullText, lineStartMs, lineEndMs),
                            wordSynced = isWordSynced
                        )
                    )
                }
            }
            lines.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }

    private fun get(url: String): JsonObject? {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Cookie", "awc=true").build()
        return runCatching {
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.string()?.let { json.parseToJsonElement(it) as? JsonObject }
            }
        }.getOrNull()
    }
}
