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
                    // v1.04: probe the stream's first bytes. Only a positively confirmed "fLaC" header is labelled
                    // FLAC; a failed probe is UNVERIFIED and never claims FLAC/lossless (see streamInfoFor).
                    val probe = probeStreamFormat(streamUrl)

                    return@withContext Resolved(
                        uri = streamUrl,
                        info = streamInfoFor(probe, kind, track.id),
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

    override suspend fun probe(): String {
        val count = search("Coldplay", 2).size
        return "$count results from Tidal/Monochrome mirror pool"
    }
}

/**
 * v1.04: outcome of the format probe on a Monochrome stream URL. Only [Flac] is proof of FLAC; [Failed] means the
 * probe itself did not complete (network error, HTTP error, empty body), so the stream's quality is unverified.
 */
internal sealed interface StreamFormatProbe {
    /** The stream starts with the "fLaC" marker. */
    data object Flac : StreamFormatProbe
    /** The probe read bytes that are not FLAC; [codec] is the delivered format when recognised, else null. */
    data class NotFlac(val codec: String?) : StreamFormatProbe
    /** The probe itself failed: nothing about the stream's format is known. */
    data object Failed : StreamFormatProbe
}

/** v1.04: label shown when the delivered format could not be verified. Never FLAC/lossless. */
internal const val QUALITY_NOT_VERIFIED = "Quality not verified"

/**
 * v1.04: classifies the first bytes of a stream (and its Content-Type as a fallback). "fLaC" is the only thing that
 * yields [StreamFormatProbe.Flac]; a FLAC Content-Type alone is not proof.
 */
internal fun classifyStreamBytes(bytes: ByteArray, contentType: String?): StreamFormatProbe {
    fun at(i: Int, vararg b: Int) = bytes.size >= i + b.size && b.indices.all { bytes[i + it] == b[it].toByte() }
    if (at(0, 0x66, 0x4C, 0x61, 0x43)) return StreamFormatProbe.Flac // "fLaC"
    val byMagic = when {
        at(4, 0x66, 0x74, 0x79, 0x70) -> "AAC" // "ftyp": MP4/M4A container (Tidal's lossy renditions are AAC in MP4)
        at(0, 0x49, 0x44, 0x33) -> "MP3" // "ID3"
        at(0, 0x4F, 0x67, 0x67, 0x53) -> "Ogg" // "OggS"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xF6) == 0xF0 -> "AAC" // ADTS sync word
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> "MP3" // MPEG audio frame sync
        else -> null
    }
    val byType = contentType?.lowercase()?.let { ct ->
        when {
            "mp4" in ct || "aac" in ct || "m4a" in ct -> "AAC"
            "mpeg" in ct || "mp3" in ct -> "MP3"
            "ogg" in ct || "opus" in ct -> "Ogg"
            else -> null
        }
    }
    return StreamFormatProbe.NotFlac(byMagic ?: byType)
}

/** v1.04: reads the first 12 bytes of [streamUrl]. Any failure of the probe itself is [StreamFormatProbe.Failed]. */
internal fun probeStreamFormat(streamUrl: String, client: okhttp3.OkHttpClient = Http.client): StreamFormatProbe =
    try {
        val req = Request.Builder().url(streamUrl).header("Range", "bytes=0-11").header("User-Agent", "UpZe/1.0 (Android)").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return StreamFormatProbe.Failed
            val bytes = resp.body?.byteStream()?.use { it.readNBytesCompat(12) }
            if (bytes == null || bytes.isEmpty()) StreamFormatProbe.Failed
            else classifyStreamBytes(bytes, resp.header("Content-Type"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        StreamFormatProbe.Failed
    }

/** Reads up to [n] bytes (InputStream.readNBytes is API 33+ on Android). */
private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(buf, off, n - off)
        if (r < 0) break
        off += r
    }
    return buf.copyOf(off)
}

/**
 * v1.04: the honest label for a probed Monochrome stream. FLAC / 1411 kbps only when the probe confirmed "fLaC";
 * otherwise the detected delivered format (bitrate unknown), or [QUALITY_NOT_VERIFIED] when the probe failed or the
 * format was not recognised. Playback later replaces the codec with the one the decoder actually reports.
 */
internal fun streamInfoFor(probe: StreamFormatProbe, kind: SourceKind, trackId: String): StreamInfo = when (probe) {
    StreamFormatProbe.Flac -> StreamInfo(kind, "FLAC", 1411, null, trackId)
    is StreamFormatProbe.NotFlac -> StreamInfo(kind, probe.codec ?: QUALITY_NOT_VERIFIED, null, null, trackId)
    StreamFormatProbe.Failed -> StreamInfo(kind, QUALITY_NOT_VERIFIED, null, null, trackId)
}
