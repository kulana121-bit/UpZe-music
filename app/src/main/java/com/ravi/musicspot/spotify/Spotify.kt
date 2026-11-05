/*
 * Spotify library import, ported (trimmed) from OpenTune 1.0.4 core/data/spotify/SpotifyWebPlayer.kt and
 * SpotifyLibrary.kt, GPL-3.0-or-later. Only metadata (playlists, liked songs) is read: Spotify audio cannot be
 * streamed by third-party apps, so imported songs play through UpZe's other sources. No writes. v2.0.7 adds Canvas.
 */
package com.ravi.musicspot.spotify

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.Http.await
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SpotifyClient(private val dir: File?) {
    class SignedOut(message: String) : IOException(message)

    @Serializable data class Secret(val version: Int, val codes: List<Int>)
    @Serializable data class WebPlayer(val clientVersion: String, val secret: Secret, val hashes: Map<String, String>, val complete: Boolean, val at: Long)
    data class Playlist(val id: String, val name: String, val owner: String?, val imageUrl: String?)

    private class Access(val token: String, val expiresAt: Long, val clientId: String?, val cookie: String)
    private class ClientTok(val token: String, val expiresAt: Long)

    private val lock = Mutex()
    private val lane = Mutex()
    private var webPlayer: WebPlayer? = null
    private var access: Access? = null
    private var client: ClientTok? = null

    fun signedOut() { access = null; client = null }

    data class Profile(val name: String?, val imageUrl: String?)

    /**
     * Validates an sp_dc cookie by fetching a web-player token (throws [SignedOut] when the cookie is not a
     * signed-in session), then best-effort reads the display name and avatar from /v1/me.
     */
    suspend fun profile(cookie: String): Profile {
        val bearer = accessToken(cookie)
        val (code, text) = runCatching {
            send(Request.Builder().url("https://api.spotify.com/v1/me").header("Authorization", "Bearer ${bearer.token}")
                .header("Accept", "application/json").header("User-Agent", UA).build())
        }.getOrNull() ?: return Profile(null, null)
        if (code !in 200..299) return Profile(null, null)
        val o = runCatching { Http.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Profile(null, null)
        val img = (o["images"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("url") }.firstOrNull()
        return Profile(o.text("display_name") ?: o.text("id"), img)
    }

    // ---- library ----------------------------------------------------------------------------------------------
    suspend fun playlists(cookie: String): List<Playlist> {
        val all = mutableListOf(Playlist(LIKED_ID, "Liked Songs", null, LIKED_COVER))
        var offset = 0
        while (offset < MAX_ITEMS) {
            val root = query("libraryV3", cookie, buildJsonObject {
                putJsonArray("filters") { add(JsonPrimitive("Playlists")) }
                put("order", JsonNull); put("textFilter", "")
                putJsonArray("features") { listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS").forEach { add(JsonPrimitive(it)) } }
                put("limit", 50); put("offset", offset); put("flatten", true)
                putJsonArray("expandedFolders") {}
                put("folderUri", JsonNull); put("includeFoldersWhenFlattening", false)
            })
            val (items, total, raw) = playlistPage(root)
            all += items; offset += raw
            if (raw == 0 || offset >= total) break
        }
        return all.distinctBy { it.id }
    }

    /** All songs of a playlist (or Liked Songs) as metadata-only tracks ("sp:<id>"). */
    suspend fun tracks(cookie: String, playlistId: String): List<Track> {
        val all = mutableListOf<Track>()
        var offset = 0
        while (offset < MAX_ITEMS) {
            val root = if (playlistId == LIKED_ID) query("fetchLibraryTracks", cookie, buildJsonObject { put("offset", offset); put("limit", 100) })
            else query("fetchPlaylist", cookie, buildJsonObject { put("uri", "spotify:playlist:$playlistId"); put("offset", offset); put("limit", 100); put("enableWatchFeedEntrypoint", false) })
            val (items, total, raw) = if (playlistId == LIKED_ID) likedPage(root) else trackPage(root)
            all += items; offset += raw
            if (raw == 0 || offset >= total) break
        }
        return all
    }

    // ---- Canvas (v2.0.7) ---------------------------------------------------------------------------------------
    private val canvasCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val NONE = ""

    /**
     * The Canvas video (mp4 URL) for [t], or null when the song has none. Spotify songs ("sp:<id>") are looked up
     * directly; any other song is matched first through the Web API search (ISRC when known, else title + artist).
     */
    suspend fun canvasUrl(cookie: String, t: Track): String? {
        val key = t.id
        canvasCache[key]?.let { return it.ifEmpty { null } }
        val id = if (t.id.startsWith("sp:")) t.id.removePrefix("sp:") else findTrackId(cookie, t)
        if (id == null) { canvasCache[key] = NONE; return null }
        // v2.0.8: a network/token failure is not "no Canvas": only a real answer is cached, so it retries next time.
        val root = runCatching { query("canvas", cookie, buildJsonObject { put("trackUri", "spotify:track:$id") }) }.getOrNull() ?: return null
        val url = (root.at("data", "trackUnion", "canvas", "url") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.startsWith("https://") && it.substringBefore('?').endsWith(".mp4") }
        canvasCache[key] = url ?: NONE
        return url
    }

    /** OpenTune's matcher: the web player's searchTracks, same title + artist, length within 4 s. */
    private suspend fun findTrackId(cookie: String, t: Track): String? {
        fun fold(x: String) = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("""\(.*?\)|\[.*?]|\s(feat|ft)\..*|\s-\s.*"""), "").filter { it.isLetterOrDigit() }
        val artist0 = t.artist.substringBefore(',').substringBefore('&').trim()
        val wantT = fold(t.title); val wantA = fold(artist0)
        if (wantT.isNotEmpty()) runCatching {
            val root = query("searchTracks", cookie, buildJsonObject {
                put("searchTerm", "${t.title} $artist0"); put("offset", 0); put("limit", 10); put("numberOfTopResults", 5)
                put("includeAudiobooks", false); put("includePreReleases", false); put("includeAuthors", false)
            })
            val items = (root.at("data", "searchV2", "tracksV2", "items") as? JsonArray).orEmpty()
            val hits = items.mapNotNull { it.at("item", "data") as? JsonObject }
            val hit = hits.firstOrNull { d ->
                val name = fold(d.text("name").orEmpty())
                val artists = (d.at("artists", "items") as? JsonArray).orEmpty().mapNotNull { (it.at("profile", "name") as? JsonPrimitive)?.contentOrNull?.let(::fold) }
                val ms = ((d.at("duration", "totalMilliseconds") ?: d.at("trackDuration", "totalMilliseconds")) as? JsonPrimitive)?.longOrNull ?: 0L
                name.isNotEmpty() && (name == wantT || name.startsWith(wantT) || wantT.startsWith(name)) &&
                    (wantA.isEmpty() || artists.any { it == wantA || it.contains(wantA) || wantA.contains(it) }) &&
                    (t.durationMs <= 0 || ms <= 0 || kotlin.math.abs(ms - t.durationMs) <= 4_000)
            }
            hit?.text("uri")?.takeIf { it.startsWith("spotify:track:") }?.substringAfterLast(':')?.let { return it }
        }
        return findTrackIdWebApi(cookie, t)
    }

    private suspend fun findTrackIdWebApi(cookie: String, t: Track): String? {
        val bearer = accessToken(cookie)
        fun norm(s: String) = s.lowercase().replace(Regex("""\(.*?\)|\[.*?]|feat\..*|ft\..*"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        val queries = listOfNotNull(t.isrc?.let { "isrc:$it" }, "track:${t.title} artist:${t.artist.substringBefore(',')}", "${t.title} ${t.artist.substringBefore(',')}")
        for (q in queries) {
            val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("type", "track").addQueryParameter("limit", "5").build()
            val (code, text) = runCatching {
                send(Request.Builder().url(url).header("Authorization", "Bearer ${bearer.token}").header("Accept", "application/json").header("User-Agent", UA).build())
            }.getOrNull() ?: continue
            if (code !in 200..299) continue
            val items = (runCatching { Http.json.parseToJsonElement(text) }.getOrNull().at("tracks", "items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val want = norm(t.title)
            val artist = norm(t.artist.substringBefore(','))
            val hit = items.firstOrNull { o ->
                val name = norm(o.text("name").orEmpty())
                val artists = (o["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name")?.let(::norm) }
                val dur = (o["duration_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
                (q.startsWith("isrc:") || (name.contains(want) || want.contains(name)) && artists.any { it.contains(artist) || artist.contains(it) }) &&
                    (t.durationMs <= 0 || dur <= 0 || kotlin.math.abs(dur - t.durationMs) < 15_000)
            }
            hit?.text("id")?.let { return it }
        }
        return null
    }

    // ---- parsing (pure; unit-tested) --------------------------------------------------------------------------
    companion object {
        const val LIKED_ID = "liked"
        private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
        private const val MAX_ITEMS = 10_000
        private const val WEB = "https://open.spotify.com"
        private const val CDN = "https://open.spotifycdn.com/cdn/build/web-player/"
        private const val PATHFINDER = "https://api-partner.spotify.com/pathfinder/v2/query"
        private const val CLIENT_TOKEN = "https://clienttoken.spotify.com/v1/clienttoken"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        private const val TTL = 12 * 60 * 60_000L

        /** Last hashes known to work (OpenTune, web player script of 2026-10-24); refreshed from the live script. */
        val KNOWN_HASHES = mapOf(
            "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
            "fetchPlaylist" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
            "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
            // v2.0.7: Canvas (the short looping video Spotify shows behind a song). Variables {trackUri}.
            "canvas" to "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f",
            // v2.0.8: the web player's own search (as OpenTune), used to match non-Spotify songs for Canvas.
            "searchTracks" to "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751",
        )
        private val KNOWN_SECRET = Secret(12, listOf(107, 81, 49, 57, 67, 93, 87, 81, 69, 67, 40, 93, 48, 50, 46, 91, 94, 113, 41, 47))

        fun JsonElement?.at(vararg keys: String): JsonElement? = keys.fold(this) { a, k -> (a as? JsonObject)?.get(k) }
        private fun JsonObject.text(k: String) = (get(k) as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(k: String) = (get(k) as? JsonPrimitive)?.intOrNull
        private fun largest(sources: JsonElement?): String? = (sources as? JsonArray).orEmpty()
            .mapNotNull { s -> (s.at("url") as? JsonPrimitive)?.contentOrNull?.let { it to ((s.at("width") as? JsonPrimitive)?.intOrNull ?: 0) } }
            .maxByOrNull { it.second }?.first

        fun playlistPage(root: JsonObject): Triple<List<Playlist>, Int, Int> {
            val lib = root.at("data", "me", "libraryV3") as? JsonObject ?: throw IOException("Spotify's library answer was empty")
            val raw = lib["items"] as? JsonArray ?: JsonArray(emptyList())
            val items = raw.mapNotNull { e ->
                val w = e.at("item") as? JsonObject ?: return@mapNotNull null
                val data = w["data"] as? JsonObject ?: return@mapNotNull null
                if (data.text("__typename") != "Playlist") return@mapNotNull null
                val uri = w.text("_uri") ?: return@mapNotNull null
                val name = data.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Playlist(uri.substringAfterLast(':'), name, (data.at("ownerV2", "data", "name") as? JsonPrimitive)?.contentOrNull,
                    largest((data.at("images", "items") as? JsonArray)?.firstOrNull().at("sources")))
            }
            return Triple(items, lib.int("totalCount") ?: raw.size, raw.size)
        }

        fun trackPage(root: JsonObject): Triple<List<Track>, Int, Int> {
            val pl = root.at("data", "playlistV2") as? JsonObject ?: throw IOException("Spotify's playlist answer was empty")
            val content = pl["content"] as? JsonObject ?: throw IOException("Spotify's playlist answer had no songs")
            val raw = content["items"] as? JsonArray ?: JsonArray(emptyList())
            val items = raw.mapNotNull { e -> (e.at("itemV2", "data") as? JsonObject)?.let { item(it, it.text("uri") ?: it.text("_uri")) } }
            return Triple(items, content.int("totalCount") ?: raw.size, raw.size)
        }

        fun likedPage(root: JsonObject): Triple<List<Track>, Int, Int> {
            val tracks = root.at("data", "me", "library", "tracks") as? JsonObject ?: throw IOException("Spotify's liked songs answer was empty")
            val raw = tracks["items"] as? JsonArray ?: JsonArray(emptyList())
            val items = raw.mapNotNull { e ->
                val w = e.at("track") as? JsonObject ?: return@mapNotNull null
                val data = w["data"] as? JsonObject ?: return@mapNotNull null
                item(data, w.text("_uri") ?: data.text("uri"))
            }
            return Triple(items, tracks.int("totalCount") ?: raw.size, raw.size)
        }

        private fun item(data: JsonObject, uri: String?): Track? {
            if (uri == null || !uri.startsWith("spotify:track:")) return null
            val title = data.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val artists = (data.at("artists", "items") as? JsonArray).orEmpty()
                .mapNotNull { (it.at("profile", "name") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            val ms = ((data.at("trackDuration", "totalMilliseconds") ?: data.at("duration", "totalMilliseconds")) as? JsonPrimitive)?.longOrNull ?: 0L
            val album = (data.at("albumOfTrack", "name") as? JsonPrimitive)?.contentOrNull
            val art = largest(data.at("albumOfTrack", "coverArt", "sources"))
            return Track("sp:${uri.substringAfterLast(':')}", title, artists.joinToString(", "), album, ms, art)
        }

        fun secretsIn(script: String): List<Secret> =
            Regex("""\{secret:(['"])((?:\\.|(?!\1).)*)\1,version:(\d+)\}""").findAll(script)
                .map { m -> Secret(m.groupValues[3].toInt(), unescapeJs(m.groupValues[2]).map { it.code }) }
                .sortedByDescending { it.version }.toList()

        fun queryHashIn(script: String, op: String): String? =
            Regex("""["']$op["']\s*,\s*["'](?:query|mutation)["']\s*,\s*["']([0-9a-f]{64})["']""").find(script)?.groupValues?.get(1)

        fun unescapeJs(body: String): String = Regex("""\\(u\{[0-9a-fA-F]+\}|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[\s\S])""").replace(body) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("u") && e.length > 1 -> String(Character.toChars(e.drop(1).trim('{', '}').toInt(16)))
                e.startsWith("x") && e.length == 3 -> e.drop(1).toInt(16).toChar().toString()
                else -> when (e) { "n" -> "\n"; "r" -> "\r"; "t" -> "\t"; "0" -> "\u0000"; else -> e }
            }
        }

        /** TOTP over SHA-1, 30 s steps, key = secret codes XOR their position (the web player's scheme). */
        fun totp(codes: List<Int>, epochMs: Long): String {
            val key = codes.mapIndexed { i, c -> c xor ((i % 33) + 9) }.joinToString("").toByteArray()
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
            val h = mac.doFinal(ByteBuffer.allocate(8).putLong(epochMs / 1000 / 30).array())
            val o = h.last().toInt() and 0x0f
            val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
            return (bin % 1_000_000).toString().padStart(6, '0')
        }
    }

    // ---- network --------------------------------------------------------------------------------------------
    private suspend fun send(req: Request): Pair<Int, String> = lane.withLock {
        var tries = 0
        while (true) {
            val (code, text, retry) = Http.client.newCall(req).await().use { Triple(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
            if (code != 429 || tries++ >= 2) return@withLock code to text
            delay(((retry?.toLongOrNull() ?: 5L) * 1000).coerceIn(1000, 30_000))
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun query(op: String, cookie: String, variables: JsonObject, retried: Boolean = false): JsonObject {
        val bearer = accessToken(cookie)
        val player = lock.withLock { readWebPlayer(false) }
        val ct = clientToken(bearer, player)
        val body = buildJsonObject {
            put("variables", variables); put("operationName", op)
            putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", player.hashes[op] ?: KNOWN_HASHES.getValue(op)) } }
        }.toString()
        val req = Request.Builder().url(PATHFINDER).post(body.toRequestBody("application/json;charset=UTF-8".toMediaType()))
            .header("Authorization", "Bearer ${bearer.token}").header("Accept", "application/json").header("App-Platform", "WebPlayer")
            .header("Origin", WEB).header("Referer", "$WEB/").header("User-Agent", UA)
            .apply { if (ct != null) header("Client-Token", ct) }.build()
        val (code, text) = send(req)
        if (code == 401 && !retried) { access = null; client = null; return query(op, cookie, variables, true) }
        val root = runCatching { Http.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val errors = (root?.get("errors") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("message") }
        if (errors.any { it.contains("PersistedQueryNotFound", true) } && !retried) {
            lock.withLock { webPlayer = null; readWebPlayer(true) }
            return query(op, cookie, variables, true)
        }
        if (code !in 200..299) throw IOException("Spotify $op answered $code")
        return root ?: throw IOException("Spotify $op: not JSON")
    }

    private suspend fun accessToken(cookie: String): Access = lock.withLock {
        access?.takeIf { it.cookie == cookie && System.currentTimeMillis() < it.expiresAt - 30_000 }?.let { return@withLock it }
        for (force in listOf(false, true)) {
            val player = readWebPlayer(force)
            val code = totp(player.secret.codes, System.currentTimeMillis())
            val url = "$WEB/api/token".toHttpUrl().newBuilder().addQueryParameter("reason", "init").addQueryParameter("productType", "web-player")
                .addQueryParameter("totp", code).addQueryParameter("totpServer", code).addQueryParameter("totpVer", player.secret.version.toString()).build()
            val (status, text) = send(Request.Builder().url(url).header("Cookie", "sp_dc=$cookie").header("User-Agent", UA)
                .header("Accept", "application/json").header("Referer", "$WEB/").build())
            val body = runCatching { Http.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (body?.get("totpVerExpired") != null && !force) continue
            if (status == 401) throw SignedOut("Your Spotify sign-in has expired")
            if ((body?.get("isAnonymous") as? JsonPrimitive)?.booleanOrNull == true) throw SignedOut("Not signed in to Spotify")
            val token = body?.text("accessToken")
            if (token != null) {
                val exp = (body["accessTokenExpirationTimestampMs"] as? JsonPrimitive)?.longOrNull ?: (System.currentTimeMillis() + 3_600_000)
                return@withLock Access(token, exp, body.text("clientId"), cookie).also { access = it }
            }
        }
        throw IOException("Spotify wouldn't give a token")
    }

    private suspend fun clientToken(bearer: Access, player: WebPlayer): String? {
        client?.takeIf { System.currentTimeMillis() < it.expiresAt - 30_000 }?.let { return it.token }
        val clientId = bearer.clientId ?: return null
        val body = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", player.clientVersion); put("client_id", clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown"); put("device_model", "unknown"); put("os", "windows"); put("os_version", "unknown")
                    put("device_id", deviceId()); put("device_type", "computer")
                }
            }
        }.toString()
        return runCatching {
            val (_, text) = send(Request.Builder().url(CLIENT_TOKEN).post(body.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json").header("User-Agent", UA).build())
            val root = Http.json.parseToJsonElement(text).jsonObject
            if (root.text("response_type") != "RESPONSE_GRANTED_TOKEN_RESPONSE") return@runCatching null
            val g = root["granted_token"] as? JsonObject ?: return@runCatching null
            val t = g.text("token") ?: return@runCatching null
            client = ClientTok(t, System.currentTimeMillis() + ((g["expires_after_seconds"] as? JsonPrimitive)?.longOrNull ?: 3600) * 1000)
            t
        }.getOrNull()
    }

    private fun deviceId(): String {
        val f = dir?.let { File(it, "spotify-device-id") }
        f?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length == 32 }?.let { return it }
        return UUID.randomUUID().toString().replace("-", "").also { id -> runCatching { f?.writeText(id) } }
    }

    private suspend fun text(url: String): String? = runCatching {
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).await().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    /** Callers hold [lock]. */
    private suspend fun readWebPlayer(force: Boolean): WebPlayer {
        val file = dir?.let { File(it, "spotify-web-player.json") }
        if (!force) {
            val cached = webPlayer ?: file?.takeIf { it.exists() }?.let { runCatching { Http.json.decodeFromString(WebPlayer.serializer(), it.readText()) }.getOrNull() }
            if (cached != null && System.currentTimeMillis() - cached.at < TTL) { webPlayer = cached; return cached }
        }
        val html = text("$WEB/").orEmpty()
        val version = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""").find(html)?.groupValues?.get(1)
            ?.let { runCatching { String(java.util.Base64.getDecoder().decode(it)) }.getOrNull() }
            ?.let { runCatching { Http.json.parseToJsonElement(it).jsonObject.text("clientVersion") }.getOrNull() }
        val bundleUrl = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[0-9a-f]+\.js""").find(html)?.value
        val bundle = bundleUrl?.let { text(it) }.orEmpty()
        val secret = secretsIn(bundle).firstOrNull()
        val found = KNOWN_HASHES.keys.associateWith { queryHashIn(bundle, it) }
        val read = WebPlayer(version ?: "1.2.77.0", secret ?: KNOWN_SECRET, KNOWN_HASHES.mapValues { (op, k) -> found[op] ?: k },
            secret != null && version != null && found.values.all { it != null }, System.currentTimeMillis())
        webPlayer = read
        runCatching { file?.writeText(Http.json.encodeToString(WebPlayer.serializer(), read)) }
        return read
    }
}
