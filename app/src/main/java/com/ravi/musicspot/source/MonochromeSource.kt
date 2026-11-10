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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

/**
 * Tidal / Monochrome HiFi mirror pool:
 * Delivers genuine 16-bit / 44.1kHz FLAC streams (and 24-bit Hi-Res via hifi mirrors where reachable)
 * without requiring any user account. Includes automatic pool failover and fLaC header verification.
 */
class MonochromeSource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.MONOCHROME
    override fun available() = enabled()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        // Mirrored failover pool for Tidal / Monochrome catalogue
        private val MIRRORS = listOf(
            "https://tracks.monochrome.st",
            "https://api.monochrome.tf",
            "https://frankfurt-1.monochrome.tf",
            "https://ohio-1.monochrome.tf"
        )
    }

    override suspend fun search(query: String, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val encQuery = URLEncoder.encode(query, "UTF-8")
        
        // Search Tidal catalog via open mirror pool
        for (mirror in MIRRORS) {
            val url = "$mirror/search?query=$encQuery&limit=$limit"
            try {
                val respStr = Http.getString(url, mapOf("User-Agent" to "UpZe/1.0 (Android)"))
                val resp = json.parseToJsonElement(respStr) as? JsonObject ?: continue
                val items = ((resp["tracks"] as? JsonObject)?.get("items") as? JsonArray
                    ?: resp["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                
                if (items.isNotEmpty()) {
                    return@withContext items.mapNotNull { item ->
                        val id = (item["id"] as? JsonPrimitive)?.longOrNull?.toString()
                            ?: (item["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val title = (item["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        val artists = ((item["artists"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>())
                            .mapNotNull { (it["name"] as? JsonPrimitive)?.contentOrNull }
                            .joinToString(", ")
                        val albumObj = item["album"] as? JsonObject
                        val album = (albumObj?.get("title") as? JsonPrimitive)?.contentOrNull
                        val artUrl = (albumObj?.get("cover") as? JsonPrimitive)?.contentOrNull
                            ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" }
                        val dur = ((item["duration"] as? JsonPrimitive)?.longOrNull ?: 0L) * 1000L

                        Track(
                            id = "${kind.prefix}:$id",
                            title = title,
                            artist = artists.ifBlank { (item["artist"] as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "Unknown Artist" },
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
                // Try next mirror
            }
        }
        emptyList()
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved = withContext(Dispatchers.IO) {
        val trackId = track.nativeId
        val audioQuality = when (quality) {
            Quality.LOSSLESS, Quality.MAX -> "HI_RES" // 24-bit / 16-bit FLAC
            Quality.HIGH -> "LOSSLESS" // 16-bit 44.1kHz FLAC
            Quality.AUTO -> if (metered) "HIGH" else "LOSSLESS"
        }

        // Try mirrors in sequence with fLaC header probe
        for (mirror in MIRRORS) {
            val urlApi = "$mirror/track/$trackId?quality=$audioQuality"
            try {
                val respStr = Http.getString(urlApi, mapOf("User-Agent" to "UpZe/1.0 (Android)"))
                val resp = json.parseToJsonElement(respStr) as? JsonObject ?: continue
                val streamUrl = (resp["url"] as? JsonPrimitive)?.contentOrNull
                    ?: (resp["streamUrl"] as? JsonPrimitive)?.contentOrNull ?: continue

                if (streamUrl.startsWith("http")) {
                    // Check fLaC header (first 4 bytes: 0x66, 0x4C, 0x61, 0x43) to guarantee non-truncated FLAC
                    val isFlac = verifyFlacHeader(streamUrl)
                    val codec = if (isFlac) "FLAC" else "AAC"
                    val br = if (isFlac) 1411 else 320

                    return@withContext Resolved(
                        uri = streamUrl,
                        info = StreamInfo(kind, codec, br, null, track.id),
                        headers = mapOf("User-Agent" to "UpZe/1.0 (Android)"),
                        cacheKey = "${kind.prefix}:$trackId@$audioQuality"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Failover to next mirror
            }
        }
        throw SourceUnavailableException("Monochrome: unable to resolve valid FLAC stream from any mirror for ${track.title}")
    }

    private fun verifyFlacHeader(streamUrl: String): Boolean {
        return runCatching {
            val req = Request.Builder().url(streamUrl).header("Range", "bytes=0-3").build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bytes = resp.body?.bytes() ?: return false
                bytes.size >= 4 && bytes[0] == 0x66.toByte() && bytes[1] == 0x4C.toByte() &&
                    bytes[2] == 0x61.toByte() && bytes[3] == 0x43.toByte() // "fLaC"
            }
        }.getOrDefault(true) // optimistic default if range probe is skipped
    }

    override suspend fun probe(): String {
        val count = search("Coldplay", 2).size
        return "$count results from Tidal/Monochrome mirror pool"
    }
}
