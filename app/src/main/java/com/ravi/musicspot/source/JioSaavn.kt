/*
 * JioSaavn public catalog/CDN protocol. Endpoints and the media-URL decoding follow OpenTune 1.0.4
 * data/lossless/JioSaavnSource.kt (itself adapted from BitChord), GPL-3.0-or-later. 320 kbps AAC, not lossless.
 */
package com.ravi.musicspot.source

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.model.Collection
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.Shelf
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.ravi.musicspot.core.PlaybackLog
import kotlinx.coroutines.async
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class JioSaavnSource(private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.JIOSAAVN
    override fun available() = enabled()

    /**
     * v2.0.5: every call logs its HTTP status and latency to the playback log (search/reco only, to keep it short)
     * and runs on [client]: connect 5 s, read 8 s, whole call 10 s, shared pool + IPv4-first DNS.
     */
    private suspend fun call(method: String, vararg params: Pair<String, String>, ctx: String = "android", log: Boolean = false): JsonElement {
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("__call", method).addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0").addQueryParameter("ctx", ctx).addQueryParameter("api_version", "4")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
        val t0 = System.nanoTime()
        val body = try { Http.getString(url, mapOf("User-Agent" to Http.BROWSER_UA), client) } catch (e: Exception) {
            if (log && e !is kotlinx.coroutines.CancellationException) PlaybackLog.log("JioSaavn $method: ${(e as? Http.HttpStatusException)?.let { "HTTP ${it.code}" } ?: e.javaClass.simpleName} after ${(System.nanoTime() - t0) / 1_000_000} ms")
            throw e
        }
        if (log) PlaybackLog.log("JioSaavn $method${params.firstOrNull { it.first == "q" || it.first == "query" }?.let { " \"${it.second.take(40)}\"" } ?: ""}: HTTP 200 in ${(System.nanoTime() - t0) / 1_000_000} ms")
        // v2.0.16: JioSaavn sometimes answers 200 with an HTML/garbage page; surface it as an IOException, not a SerializationException.
        return try { Http.json.parseToJsonElement(body) } catch (e: kotlinx.serialization.SerializationException) {
            throw SourceUnavailableException("JioSaavn $method: invalid JSON", e)
        }
    }

    /**
     * search.getResults; when it has not answered within [FALLBACK_AFTER_MS] (or fails), autocomplete.get (web
     * context) is raced against it and the first non-empty answer wins.
     */
    override suspend fun search(query: String, limit: Int): List<Track> = kotlinx.coroutines.coroutineScope {
        val primary = async { runCatching { searchResults(query, limit) } }
        val early = kotlinx.coroutines.withTimeoutOrNull(FALLBACK_AFTER_MS) { primary.await() }
        if (early != null && early.getOrNull()?.isNotEmpty() == true) return@coroutineScope early.getOrThrow()
        val fallback = async { runCatching { autocomplete(query, limit) } }
        while (true) {
            val p = if (primary.isCompleted) primary.await() else null
            val f = if (fallback.isCompleted) fallback.await() else null
            p?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { fallback.cancel(); return@coroutineScope it }
            f?.getOrNull()?.takeIf { it.isNotEmpty() }?.let { primary.cancel(); return@coroutineScope it }
            if (p != null && f != null) return@coroutineScope p.getOrNull() ?: f.getOrNull() ?: throw (p.exceptionOrNull() ?: f.exceptionOrNull()!!)
            kotlinx.coroutines.delay(40)
        }
        emptyList<Track>()
    }

    private suspend fun searchResults(query: String, limit: Int): List<Track> {
        val root = call("search.getResults", "q" to query, "n" to limit.toString(), "p" to "1", log = true) as? JsonObject ?: return emptyList()
        return (root["results"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toTrack) }
    }

    /** autocomplete.get (ctx web6dot0): songs.data with id/title/album/image and more_info.primary_artists as text. */
    internal suspend fun autocomplete(query: String, limit: Int): List<Track> {
        val root = call("autocomplete.get", "query" to query, ctx = "web6dot0", log = true) as? JsonObject ?: return emptyList()
        return parseAutocomplete(root).take(limit)
    }

    internal fun parseAutocomplete(root: JsonObject): List<Track> =
        ((root["songs"] as? JsonObject)?.get("data") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            if (o.str("type") != null && o.str("type") != "song") return@mapNotNull null
            val artists = o.obj("more_info")?.str("primary_artists")?.takeIf { it.isNotBlank() } ?: o.str("description")?.substringAfter(" · ", "")
            Track(id = "js:$id", title = unescapeHtml(o.str("title")), artist = unescapeHtml(artists), album = o.str("album")?.let(::unescapeHtml),
                durationMs = 0, artUrl = hiRes(o.str("image")))
        }

    /**
     * v2.0.5 autoplay fallback: JioSaavn's song recommendations (reco.getreco) for [seed]. A non-JioSaavn seed is
     * first matched on JioSaavn by title + artist.
     */
    suspend fun recommendationsFor(seed: Track): List<Track> {
        val pid = if (seed.source == SourceKind.JIOSAAVN) seed.nativeId else {
            val q = TrackMatcher.searchQueries(seed).firstOrNull() ?: "${seed.title} ${seed.artist}"
            TrackMatcher.best(seed, search(q, 10), TrackMatcher.LENIENT_THRESHOLD, lenient = true)?.nativeId ?: return emptyList()
        }
        val root = call("reco.getreco", "pid" to pid, log = true)
        val arr = (root as? JsonObject)?.get(pid) as? JsonArray ?: root as? JsonArray
        return arr.orEmpty().mapNotNull { (it as? JsonObject)?.let(::toTrack) }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val id = track.nativeId
        val details = call("song.getDetails", "pids" to id) as? JsonObject ?: throw SourceUnavailableException("JioSaavn: no details")
        val song = (details[id] as? JsonObject) ?: ((details["songs"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?: throw SourceUnavailableException("JioSaavn: track not found")
        val info = song.obj("more_info") ?: throw SourceUnavailableException("JioSaavn: no media info")
        val enc = info.str("encrypted_media_url") ?: throw SourceUnavailableException("JioSaavn: no media url")
        // v2.0.16: bad base64 / DES padding threw IllegalArgumentException / BadPaddingException past IO handlers.
        val decoded = try { decodeMediaUrl(enc) } catch (e: Exception) { throw SourceUnavailableException("JioSaavn: undecodable media url", e) }
        val has320 = info.str("320kbps").equals("true", true)
        // Always the 320 kbps rendition when the catalogue has one; 160 otherwise.
        val kbps = if (has320) 320 else 160
        val url = rendition(decoded, kbps) ?: throw SourceUnavailableException("JioSaavn: unexpected media url")
        return Resolved(url, StreamInfo(kind, "AAC", kbps, 44_100, track.id), mapOf("User-Agent" to Http.BROWSER_UA), "js:$id@$kbps")
    }

    override suspend fun probe(): String = "${search("arijit", 3).size} results"

    // ---- browse (Explore: JioSaavn charts / featured playlists) ------------------------------------------------
    suspend fun charts(): Shelf {
        val arr = call("content.getCharts") as? JsonArray ?: return Shelf("JioSaavn charts")
        return Shelf("JioSaavn charts", collections = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Collection("js:pl:" + (o.str("id") ?: return@mapNotNull null), Collection.Kind.PLAYLIST, unescapeHtml(o.str("title")), "JioSaavn", hiRes(o.str("image")))
        })
    }

    /** JioSaavn's own home (webapi.getLaunchData): trending, new releases, top playlists, charts. Real data, guests too. */
    data class Launch(val trending: Shelf, val newReleases: Shelf, val playlists: Shelf, val charts: Shelf)

    suspend fun launch(): Launch {
        val o = call("webapi.getLaunchData") as? JsonObject ?: throw SourceUnavailableException("JioSaavn: no launch data")
        fun shelf(key: String, title: String): Shelf {
            val arr = (o[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            return Shelf(title, tracks = arr.mapNotNull { if (it.str("type") == "song") toTrack(it) else null },
                collections = arr.mapNotNull(::toCollection), subtitle = "JioSaavn")
        }
        return Launch(shelf("new_trending", "Trending on JioSaavn"), shelf("new_albums", "New releases"), shelf("top_playlists", "Top playlists"), shelf("charts", "JioSaavn charts"))
    }

    suspend fun trending(): Shelf = launch().trending

    internal fun toCollection(o: JsonObject): Collection? {
        val id = o.str("id")?.takeIf { ID.matches(it) } ?: return null
        val kind = when (o.str("type")) { "album" -> Collection.Kind.ALBUM; "playlist" -> Collection.Kind.PLAYLIST; else -> return null }
        val sub = o.str("subtitle")?.takeIf { it.isNotBlank() }?.let(::unescapeHtml)
            ?: (o.obj("more_info")?.obj("artistMap")?.get("primary_artists") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }.joinToString(", ").ifBlank { null }?.let(::unescapeHtml)
        return Collection((if (kind == Collection.Kind.ALBUM) "js:al:" else "js:pl:") + id, kind, unescapeHtml(o.str("title")), sub ?: "JioSaavn", hiRes(o.str("image")))
    }

    suspend fun album(id: String): Pair<Collection, List<Track>> {
        val o = call("content.getAlbumDetails", "albumid" to id) as? JsonObject ?: throw SourceUnavailableException("JioSaavn album")
        val tracks = (o["list"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toTrack) }
        return Collection("js:al:$id", Collection.Kind.ALBUM, unescapeHtml(o.str("title")), unescapeHtml(o.str("subtitle")), hiRes(o.str("image"))) to tracks
    }

    suspend fun playlist(id: String): Pair<Collection, List<Track>> {
        val o = call("playlist.getDetails", "listid" to id, "n" to "100") as? JsonObject ?: throw SourceUnavailableException("JioSaavn playlist")
        val tracks = (o["list"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toTrack) }
        return Collection("js:pl:$id", Collection.Kind.PLAYLIST, unescapeHtml(o.str("title")), unescapeHtml(o.str("subtitle")), hiRes(o.str("image"))) to tracks
    }

    /** Real credits from JioSaavn's artistMap: singers, music director, lyricist, label, year. */
    suspend fun credits(track: Track): List<Pair<String, String>> {
        val id = track.nativeId
        val details = call("song.getDetails", "pids" to id) as? JsonObject ?: return emptyList()
        val song = (details[id] as? JsonObject) ?: return emptyList()
        val info = song.obj("more_info") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val artists = (info.obj("artistMap")?.get("artists") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        artists.groupBy { it.str("role").orEmpty() }.forEach { (role, list) ->
            if (role.isNotBlank()) out += role.replaceFirstChar { it.uppercase() } to list.joinToString { unescapeHtml(it.str("name")) }
        }
        info.str("label")?.takeIf { it.isNotBlank() }?.let { out += "Label" to unescapeHtml(it) }
        song.str("year")?.takeIf { it.isNotBlank() }?.let { out += "Year" to it }
        info.str("copyright_text")?.takeIf { it.isNotBlank() }?.let { out += "Copyright" to unescapeHtml(it) }
        return out
    }

    internal fun toTrack(o: JsonObject): Track? {
        val id = o.str("id")?.takeIf { ID.matches(it) } ?: return null
        if (o.str("type") != null && o.str("type") != "song") return null
        val info = o.obj("more_info")
        val primary = (info?.obj("artistMap")?.get("primary_artists") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("name") }.map(::unescapeHtml)
        return Track(
            id = "js:$id",
            title = unescapeHtml(o.str("title")),
            artist = primary.joinToString(", ").ifBlank { unescapeHtml(o.str("subtitle")?.substringBefore(" - ")) },
            album = info?.str("album")?.let(::unescapeHtml),
            durationMs = (info?.str("duration")?.toLongOrNull() ?: 0) * 1000,
            artUrl = hiRes(o.str("image")),
            albumId = info?.str("album_id")?.let { "js:al:$it" },
            explicit = o.str("explicit_content") == "1",
            year = o.str("year"),
            bitrateKbps = if (info?.str("320kbps").equals("true", true)) 320 else 160,
        )
    }

    companion object {
        private const val API = "https://www.jiosaavn.com/api.php"
        /** Race autocomplete.get against search.getResults after this long. */
        const val FALLBACK_AFTER_MS = 4_000L
        private val client by lazy {
            Http.client.newBuilder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        }
        private val ID = Regex("[A-Za-z0-9_-]+")
        private val RENDITION = Regex("_(12|48|96|160|320)\\.mp4$", RegexOption.IGNORE_CASE)

        fun hiRes(url: String?): String? = url?.replace("http://", "https://")?.replace(Regex("(\\d+)x(\\d+)"), "500x500")

        /** DES-ECB with JioSaavn's public key, as their web player does. */
        fun decodeMediaUrl(enc: String): String {
            val c = Cipher.getInstance("DES/ECB/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
            return String(c.doFinal(Base64.getDecoder().decode(enc.trim())), Charsets.UTF_8).trim()
        }

        /** Only https saavncdn.com URLs; swaps in the requested rendition. */
        fun rendition(decoded: String, kbps: Int): String? {
            val url = decoded.replace(Regex("^http://"), "https://")
            val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
            if (!parsed.isHttps) return null
            if (parsed.host != "saavncdn.com" && !parsed.host.endsWith(".saavncdn.com")) return null
            if (!RENDITION.containsMatchIn(parsed.encodedPath)) return null
            return parsed.newBuilder().encodedPath(parsed.encodedPath.replace(RENDITION, "_$kbps.mp4")).build().toString()
        }

        private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    }
}
