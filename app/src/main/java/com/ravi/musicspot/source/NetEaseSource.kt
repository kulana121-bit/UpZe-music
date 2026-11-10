package com.ravi.musicspot.source

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * NetEase Cloud Music source: offers mainstream catalogue with lossless FLAC (16-bit 44.1kHz / 24-bit Hi-Res)
 * and 320 kbps MP3 without requiring an account.
 */
class NetEaseSource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.NETEASE
    override fun available() = enabled()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val encQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://music.163.com/api/search/get?s=$encQuery&type=1&limit=$limit"

        val resp = try {
            Http.json.parseToJsonElement(Http.getString(url, mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "https://music.163.com/"
            ))) as? JsonObject
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val songs = ((resp["result"] as? JsonObject)?.get("songs") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return songs.mapNotNull { s ->
            val id = (s["id"] as? JsonPrimitive)?.longOrNull?.toString() ?: return@mapNotNull null
            val title = (s["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val artists = ((s["artists"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>())
                .mapNotNull { (it["name"] as? JsonPrimitive)?.contentOrNull }
                .joinToString(", ")
            val albumObj = s["album"] as? JsonObject
            val album = (albumObj?.get("name") as? JsonPrimitive)?.contentOrNull
            val artUrl = (albumObj?.get("picUrl") as? JsonPrimitive)?.contentOrNull
            val dur = (s["duration"] as? JsonPrimitive)?.longOrNull ?: 0L

            Track(
                id = "${kind.prefix}:$id",
                title = title,
                artist = artists.ifBlank { "Unknown Artist" },
                album = album,
                durationMs = dur,
                artUrl = artUrl,
                format = "flac",
                bitrateKbps = 940,
            )
        }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val br = when (quality) {
            Quality.LOSSLESS, Quality.MAX -> 740 // 16-bit FLAC (or 999 for 24-bit)
            Quality.HIGH -> 320
            Quality.AUTO -> if (metered) 128 else 320
        }

        val urlApi = "https://music-api.gdstudio.xyz/api.php?types=url&source=netease&id=${track.nativeId}&br=$br"
        val resp = try {
            Http.json.parseToJsonElement(Http.getString(urlApi, mapOf("User-Agent" to "Mozilla/5.0"))) as? JsonObject
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SourceUnavailableException("NetEase: failed to resolve stream for ${track.title}", e)
        } ?: throw SourceUnavailableException("NetEase: empty response for ${track.title}")

        val streamUrl = (resp["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }
            ?: throw SourceUnavailableException("NetEase: no stream URL returned")

        val realBr = (resp["br"] as? JsonPrimitive)?.intOrNull ?: br
        val isFlac = streamUrl.contains(".flac", ignoreCase = true) || realBr >= 740
        val codec = if (isFlac) "FLAC" else "MP3"

        return Resolved(
            uri = streamUrl,
            info = StreamInfo(kind, codec, realBr, null, track.id),
            headers = mapOf("Referer" to "https://music.163.com/"),
            cacheKey = "${kind.prefix}:${track.nativeId}@$realBr"
        )
    }

    override suspend fun probe(): String {
        val count = search("Ed Sheeran", 2).size
        return "$count results from NetEase"
    }
}
