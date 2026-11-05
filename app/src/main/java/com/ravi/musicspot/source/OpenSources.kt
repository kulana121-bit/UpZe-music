package com.ravi.musicspot.source

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.yt.NewPipeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo as NpStreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.o(k: String) = this[k] as? JsonObject
private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

/** Audius: official open API (no key; app_name identifies the client). Streams are MP3 up to 320 kbps. */
class AudiusSource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.AUDIUS
    override fun available() = enabled()
    @Volatile private var host: String? = null

    private suspend fun host(): String = host ?: run {
        // v2.0.14: discovery failure falls back to the public gateway instead of failing search/play.
        val h = runCatching {
            val o = Http.json.parseToJsonElement(Http.getString("https://api.audius.co")) as? JsonObject
            (o?.get("data") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.firstOrNull { it.startsWith("https://") }
        }.getOrNull() ?: "https://api.audius.co"
        h.also { host = it }
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val url = "${host()}/v1/tracks/search".toHttpUrl().newBuilder().addQueryParameter("query", query)
            .addQueryParameter("app_name", APP).addQueryParameter("limit", limit.toString()).build().toString()
        val o = Http.json.parseToJsonElement(Http.getString(url)) as? JsonObject ?: return emptyList()
        return (o["data"] as? JsonArray).orEmpty().mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            val id = t.s("id") ?: return@mapNotNull null
            if (t.s("is_streamable") == "false") return@mapNotNull null
            Track("au:$id", t.s("title").orEmpty(), t.o("user")?.s("name").orEmpty(), null, (t.l("duration") ?: 0) * 1000,
                t.o("artwork")?.s("480x480") ?: t.o("artwork")?.s("150x150"), format = "mp3")
        }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val url = "${host()}/v1/tracks/${track.nativeId}/stream".toHttpUrl().newBuilder().addQueryParameter("app_name", APP).build().toString()
        // Bitrate is refined from the decoder's Format once playback starts.
        return Resolved(url, StreamInfo(kind, "MP3", null, null, track.id), cacheKey = "au:${track.nativeId}")
    }

    override suspend fun probe() = "${search("lofi", 2).size} results from ${host()}"
    companion object { const val APP = "UpZe" }
}

/** SoundCloud through NewPipeExtractor (public client id discovery, progressive MP3 or HLS). */
class SoundCloudSource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.SOUNDCLOUD
    override fun available() = enabled()

    override suspend fun search(query: String, limit: Int): List<Track> = runInterruptible(Dispatchers.IO) {
        NewPipeBridge.ensureInit()
        val ex = ServiceList.SoundCloud.getSearchExtractor(query, listOf("tracks"), "")
        ex.fetchPage()
        ex.initialPage.items.filterIsInstance<StreamInfoItem>().take(limit).map {
            Track("sc:${it.url}", it.name, it.uploaderName.orEmpty(), null, it.duration.coerceAtLeast(0) * 1000,
                it.thumbnails.maxByOrNull { t -> t.height }?.url)
        }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved = runInterruptible(Dispatchers.IO) {
        NewPipeBridge.ensureInit()
        val info = NpStreamInfo.getInfo(ServiceList.SoundCloud, track.nativeId)
        val best = info.audioStreams.sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { it.averageBitrate }
            .thenBy { if (it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP) 0 else 1 })
            .firstOrNull { it.isUrl } ?: throw SourceUnavailableException("SoundCloud: no stream")
        Resolved(best.content, StreamInfo(kind, best.format?.name, best.averageBitrate.takeIf { it > 0 }, null, track.id),
            cacheKey = "sc:${track.nativeId.hashCode()}@${best.averageBitrate}")
    }

    override suspend fun probe() = "${search("ambient", 2).size} results"
}

/**
 * Jamendo: official API, free Creative Commons music. Needs the user's own free client_id (developer.jamendo.com).
 * FLAC when the artist allows downloads (audiodlformat=flac), otherwise high-VBR MP3.
 */
class JamendoSource(private val clientId: () -> String?, private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.JAMENDO
    override fun available() = enabled() && !clientId().isNullOrBlank()

    private fun base() = "https://api.jamendo.com/v3.0/tracks/".toHttpUrl().newBuilder()
        .addQueryParameter("client_id", clientId() ?: throw SourceUnavailableException("Jamendo: add your client_id in Settings → Sources"))
        .addQueryParameter("format", "json").addQueryParameter("audioformat", "mp32").addQueryParameter("audiodlformat", "flac")

    override suspend fun search(query: String, limit: Int): List<Track> {
        val o = Http.json.parseToJsonElement(Http.getString(base().addQueryParameter("search", query).addQueryParameter("limit", limit.toString()).build().toString())) as? JsonObject
            ?: return emptyList()
        return (o["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            val flac = t.s("audiodownload_allowed") == "true"
            Track("jm:${t.s("id") ?: return@mapNotNull null}", t.s("name").orEmpty(), t.s("artist_name").orEmpty(), t.s("album_name"),
                (t.l("duration") ?: 0) * 1000, t.s("image"), format = if (flac) "flac" else "mp3")
        }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val o = Http.json.parseToJsonElement(Http.getString(base().addQueryParameter("id", track.nativeId).build().toString())) as? JsonObject
        val t = (o?.get("results") as? JsonArray)?.firstOrNull() as? JsonObject ?: throw SourceUnavailableException("Jamendo: not found")
        val wantFlac = (quality == Quality.LOSSLESS || quality == Quality.MAX) && t.s("audiodownload_allowed") == "true"
        val url = (if (wantFlac) t.s("audiodownload") else t.s("audio"))?.replace("http://", "https://") ?: throw SourceUnavailableException("Jamendo: no audio")
        return Resolved(url, StreamInfo(kind, if (wantFlac) "FLAC" else "MP3", null, null, track.id), cacheKey = "jm:${track.nativeId}@${if (wantFlac) "flac" else "mp3"}")
    }

    override suspend fun probe() = "${search("piano", 2).size} results"
}

/** Internet Archive: public-domain and live recordings, FLAC where the uploader provided it. */
class ArchiveSource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.ARCHIVE
    override fun available() = enabled()

    /** Items (concerts/albums) matching [query]. */
    suspend fun searchItems(query: String, rows: Int = 20): List<Collection> {
        val url = "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", "($query) AND mediatype:audio")
            .addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title").addQueryParameter("fl[]", "creator")
            .addQueryParameter("rows", rows.toString()).addQueryParameter("output", "json").build().toString()
        val o = Http.json.parseToJsonElement(Http.getString(url)) as? JsonObject ?: return emptyList()
        val docs = (o.o("response")?.get("docs") as? JsonArray).orEmpty()
        return docs.mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val id = d.s("identifier") ?: return@mapNotNull null
            val creator = (d["creator"] as? JsonPrimitive)?.contentOrNull ?: (d["creator"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            Collection("ia:$id", Collection.Kind.ALBUM, d.s("title") ?: id, creator, "https://archive.org/services/img/$id")
        }
    }

    /** Tracks in one item: original FLAC files preferred, otherwise VBR MP3 derivatives. */
    suspend fun item(identifier: String): Pair<Collection, List<Track>> {
        val o = Http.json.parseToJsonElement(Http.getString("https://archive.org/metadata/$identifier")) as? JsonObject
            ?: throw SourceUnavailableException("Archive: item not found")
        val meta = o.o("metadata")
        val title = (meta?.get("title") as? JsonPrimitive)?.contentOrNull ?: identifier
        val creator = (meta?.get("creator") as? JsonPrimitive)?.contentOrNull ?: ""
        val files = (o["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val flac = files.filter { (it.s("format") ?: "").contains("Flac", true) }
        val chosen = flac.ifEmpty { files.filter { it.s("format") == "VBR MP3" } }
        val art = "https://archive.org/services/img/$identifier"
        val tracks = chosen.sortedBy { it.s("track")?.substringBefore('/')?.toIntOrNull() ?: Int.MAX_VALUE }.map { f ->
            val name = f.s("name").orEmpty()
            Track("ia:$identifier/$name", f.s("title") ?: name.substringBeforeLast('.'), f.s("creator") ?: creator, title,
                (f.s("length")?.let { l -> l.toDoubleOrNull()?.times(1000)?.toLong() ?: parseClock(l) } ?: 0), art,
                albumId = "ia:$identifier", format = if ((f.s("format") ?: "").contains("Flac", true)) "flac" else "mp3")
        }
        return Collection("ia:$identifier", Collection.Kind.ALBUM, title, creator, art) to tracks
    }

    override suspend fun search(query: String, limit: Int): List<Track> = emptyList() // items, not tracks: see searchItems

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val path = track.nativeId.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return Resolved("https://archive.org/download/$path", StreamInfo(kind, track.format?.uppercase(), null, null, track.id), cacheKey = "ia:${track.nativeId}")
    }

    override suspend fun probe() = "${searchItems("jazz", 2).size} items"
}
