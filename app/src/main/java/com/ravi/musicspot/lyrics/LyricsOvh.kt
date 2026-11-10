package com.ravi.musicspot.lyrics

import com.ravi.musicspot.core.Http
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

/**
 * Lyrics.ovh provider:
 * Public, keyless API for plain text song lyrics.
 */
object LyricsOvh {
    private const val BASE = "https://api.lyrics.ovh/v1"
    private val json = Json { ignoreUnknownKeys = true }

    fun lookup(title: String, artist: String): Lyrics? {
        val cleaned = TrackNameCleaner.clean(title, artist)
        val encArtist = URLEncoder.encode(cleaned.artist, "UTF-8").replace("+", "%20")
        val encTitle = URLEncoder.encode(cleaned.title, "UTF-8").replace("+", "%20")
        val url = "$BASE/$encArtist/$encTitle"

        val req = Request.Builder().url(url).header("User-Agent", "UpZe/1.0").build()
        val text = runCatching {
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.string()?.let { json.parseToJsonElement(it) as? JsonObject }
                    ?.get("lyrics")?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
        }.getOrNull()?.trim()

        if (text.isNullOrBlank()) return null
        return Lyrics.Plain(text, "lyrics.ovh")
    }
}
