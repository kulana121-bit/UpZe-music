package com.ravi.musicspot.source

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Qobuz open proxy pool:
 * Provides experimental lossless FLAC (16-bit 44.1kHz / 24-bit up to 192kHz) streams via open proxies.
 */
class QobuzProxySource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.QOBUZ
    override fun available() = enabled()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val PROXIES = listOf(
            "https://squid.wtf/api/qobuz",
            "https://qobuz.squid.wtf",
            "https://qobuz-api.binimum.org"
        )
    }

    override suspend fun search(query: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val encQuery = URLEncoder.encode(query, "UTF-8")
        for (proxy in PROXIES) {
            val url = "$proxy/search?query=$encQuery&limit=$limit"
            try {
                val respStr = Http.getString(url, mapOf("User-Agent" to "UpZe/1.0"))
                val resp = json.parseToJsonElement(respStr) as? JsonObject ?: continue
                val items = (((resp["tracks"] as? JsonObject)?.get("items") as? JsonArray)
                    ?: resp["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

                if (items.isNotEmpty()) {
                    return@withContext items.mapNotNull { item ->
                        val id = (item["id"] as? JsonPrimitive)?.longOrNull?.toString()
                            ?: (item["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val title = (item["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val performer = (item["performer"] as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
                            ?: (item["artist"] as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
                            ?: "Unknown Artist"
                        val albumObj = item["album"] as? JsonObject
                        val album = (albumObj?.get("title") as? JsonPrimitive)?.contentOrNull
                        val artUrl = (albumObj?.get("image") as? JsonObject)?.get("large")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        val dur = ((item["duration"] as? JsonPrimitive)?.longOrNull ?: 0L) * 1000L

                        Track(
                            id = "${kind.prefix}:$id",
                            title = title,
                            artist = performer,
                            album = album,
                            durationMs = dur,
                            artUrl = artUrl,
                            format = "flac",
                            bitrateKbps = 1411
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Next proxy
            }
        }
        emptyList()
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved = withContext(Dispatchers.IO) {
        val trackId = track.nativeId
        val qFormat = when (quality) {
            Quality.LOSSLESS, Quality.MAX -> "27" // 24-bit 192kHz or 16-bit 44.1kHz FLAC
            Quality.HIGH -> "6" // 16-bit 44.1kHz FLAC
            Quality.AUTO -> if (metered) "5" else "6" // 320k MP3 vs 16-bit FLAC
        }

        for (proxy in PROXIES) {
            val url = "$proxy/track/url?track_id=$trackId&format_id=$qFormat"
            try {
                val respStr = Http.getString(url, mapOf("User-Agent" to "UpZe/1.0"))
                val resp = json.parseToJsonElement(respStr) as? JsonObject ?: continue
                val streamUrl = (resp["url"] as? JsonPrimitive)?.contentOrNull ?: continue

                if (streamUrl.startsWith("http")) {
                    return@withContext Resolved(
                        uri = streamUrl,
                        info = StreamInfo(kind, "FLAC", 1411, null, track.id),
                        headers = mapOf("User-Agent" to "UpZe/1.0"),
                        cacheKey = "${kind.prefix}:$trackId@$qFormat"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Next proxy
            }
        }
        throw SourceUnavailableException("Qobuz: unable to resolve stream for ${track.title}")
    }

    override suspend fun probe(): String {
        val count = search("Mozart", 2).size
        return "$count results from Qobuz proxy pool"
    }
}
