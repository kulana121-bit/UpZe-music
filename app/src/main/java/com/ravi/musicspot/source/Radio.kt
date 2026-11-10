package com.ravi.musicspot.source

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.0.2: live radio from the free, community-run Radio Browser directory (radio-browser.info).
 * A station plays as a Track with id "rb:<stationuuid>" and no duration; its stream is never cached.
 */
object Radio {
    @Serializable
    data class Station(
        val stationuuid: String = "",
        val name: String = "",
        val url_resolved: String = "",
        val url: String = "",
        val favicon: String = "",
        val tags: String = "",
        val country: String = "",
        val countrycode: String = "",
        val state: String = "",
        val language: String = "",
        val codec: String = "",
        val bitrate: Int = 0,
        val hls: Int = 0,
        val clickcount: Int = 0,
        val geo_distance: Double? = null,
    ) {
        val stream: String get() = url_resolved.ifBlank { url }
        val place: String get() = listOf(state, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
        fun toTrack() = Track(
            id = "${SourceKind.RADIO.prefix}:$stationuuid",
            title = name.trim().ifBlank { "Radio station" },
            artist = listOf(place, tags.split(',').firstOrNull()?.trim().orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Live radio" },
            album = "Live radio",
            artUrl = favicon.takeIf { it.startsWith("https://") },
            format = codec.lowercase().ifBlank { null },
            bitrateKbps = bitrate.takeIf { it > 0 },
        )
    }

    /** Mirrors of the directory; the first that answers is used. */
    private val hosts = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info", "all.api.radio-browser.info")
    private val known = ConcurrentHashMap<String, Station>()

    private suspend fun query(path: String): List<Station> {
        var last: Exception? = null
        for (h in hosts) {
            try {
                val body = Http.getString("https://$h/json/$path", mapOf("User-Agent" to "UpZe/1.0 (Android)"))
                // HLS (.m3u8) streams need another player pipeline; the progressive ones (MP3/AAC/OGG) play directly.
                return Http.json.decodeFromString(ListSerializer(Station.serializer()), body)
                    .filter { it.hls == 0 && it.stream.startsWith("http") && it.stationuuid.isNotBlank() }
                    .distinctBy { it.stationuuid }
                    .onEach { known[it.stationuuid] = it }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { last = e }
        }
        throw last ?: java.io.IOException("Radio directory unreachable")
    }

    private const val COMMON = "hidebroken=true&order=clickcount&reverse=true"

    /** Stations within [radiusKm] of a point, most listened first. */
    suspend fun near(lat: Double, lon: Double, radiusKm: Int = 400, limit: Int = 40) =
        query("stations/search?geo_lat=$lat&geo_long=$lon&geo_distance=${radiusKm * 1000}&has_geo_info=true&limit=$limit&$COMMON")

    /** Stations of one state/region (by name, as the directory spells it) in a country. */
    suspend fun inState(countryCode: String, state: String, limit: Int = 40) =
        query("stations/search?countrycode=${enc(countryCode)}&state=${enc(state)}&limit=$limit&$COMMON")

    suspend fun topIn(countryCode: String, limit: Int = 60) = query("stations/search?countrycode=${enc(countryCode)}&limit=$limit&$COMMON")

    suspend fun search(q: String, countryCode: String? = null, limit: Int = 50) =
        query("stations/search?name=${enc(q)}${countryCode?.let { "&countrycode=${enc(it)}" }.orEmpty()}&limit=$limit&$COMMON")

    /** Lossless broadcast stations (Radio Paradise, Mother Earth, JB Radio-2, Classical, etc. in bit-perfect FLAC). */
    suspend fun flacStations(limit: Int = 50) = query("stations/bycodec/flac?limit=$limit&$COMMON")

    /** The stream for "rb:<uuid>", from memory or a lookup. */
    suspend fun resolve(track: Track): Resolved {
        val uuid = track.nativeId
        val st = known[uuid] ?: query("stations/byuuid/${enc(uuid)}").firstOrNull() ?: throw java.io.IOException("Station is gone")
        return Resolved(st.stream, StreamInfo(SourceKind.RADIO, st.codec.uppercase().ifBlank { null }, st.bitrate.takeIf { it > 0 }, null, track.id),
            mapOf("User-Agent" to "UpZe/1.0 (Android)"), cacheKey = "rb:$uuid")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
