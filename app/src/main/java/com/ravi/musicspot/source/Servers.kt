package com.ravi.musicspot.source

import com.ravi.musicspot.account.Account
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.Http.await
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

/** Normalises a server address: https only (http is refused, never silently tried — fixes OpenTune S5). */
fun serverBase(input: String): HttpUrl {
    val t = input.trim().trimEnd('/')
    require(!t.startsWith("http://", true)) { "Only https:// servers are supported" }
    val url = (if (t.startsWith("https://", true)) t else "https://$t").toHttpUrl()
    require(url.isHttps) { "Only https:// servers are supported" }
    return url
}

/**
 * Subsonic API (Navidrome, Airsonic, Gonic…). Token auth with a fresh random salt on every request.
 * Lossless: stream.view?format=raw sends the original file untouched.
 */
class SubsonicSource(private val account: () -> Account?, private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.SUBSONIC
    override fun available() = enabled() && account() != null
    private val rnd = SecureRandom()

    private fun url(method: String, acct: Account = account() ?: throw SourceUnavailableException("No Subsonic server"), vararg params: Pair<String, String>): String {
        val salt = ByteArray(8).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
        val token = MessageDigest.getInstance("MD5").digest((acct.secret + salt).toByteArray()).joinToString("") { "%02x".format(it) }
        return serverBase(acct.extra ?: "").newBuilder().addPathSegments("rest/$method")
            .addQueryParameter("u", acct.name).addQueryParameter("t", token).addQueryParameter("s", salt)
            .addQueryParameter("v", "1.16.1").addQueryParameter("c", "UpZe").addQueryParameter("f", "json")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
    }

    private suspend fun call(method: String, vararg params: Pair<String, String>, acct: Account? = null): JsonObject {
        val a = acct ?: account() ?: throw SourceUnavailableException("No Subsonic server")
        val o = Http.json.parseToJsonElement(Http.getString(url(method, a, *params))) as JsonObject
        val r = o["subsonic-response"] as? JsonObject ?: throw SourceUnavailableException("Not a Subsonic server")
        if (r.s("status") != "ok") throw SourceUnavailableException("Subsonic: " + ((r["error"] as? JsonObject)?.s("message") ?: "error"))
        return r
    }

    suspend fun test(acct: Account): String { call("ping", acct = acct); return "OK" }

    override suspend fun search(query: String, limit: Int): List<Track> {
        val r = call("search3", "query" to query, "songCount" to limit.toString(), "albumCount" to "0", "artistCount" to "0")
        val songs = ((r["searchResult3"] as? JsonObject)?.get("song") as? JsonArray).orEmpty()
        return songs.mapNotNull { (it as? JsonObject)?.let(::toTrack) }
    }

    suspend fun randomSongs(n: Int = 30): List<Track> =
        ((call("getRandomSongs", "size" to n.toString())["randomSongs"] as? JsonObject)?.get("song") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::toTrack) }

    private fun toTrack(o: JsonObject): Track? {
        val id = o.s("id") ?: return null
        return Track("sub:$id", o.s("title").orEmpty(), o.s("artist").orEmpty(), o.s("album"), (o.l("duration") ?: 0) * 1000,
            o.s("coverArt")?.let { runCatching { url("getCoverArt", params = *arrayOf("id" to it, "size" to "600")) }.getOrNull() },
            year = o.s("year"), format = o.s("suffix"), bitrateKbps = o.l("bitRate")?.toInt())
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val lossless = quality == Quality.LOSSLESS || (quality == Quality.MAX && !metered)
        val params = if (lossless) arrayOf("id" to track.nativeId, "format" to "raw") else arrayOf("id" to track.nativeId, "maxBitRate" to "320")
        val u = url("stream", params = *params)
        val codec = if (lossless) track.format?.uppercase() else (if (QualityEstimate.isLosslessFormat(track.format)) "transcoded" else track.format?.uppercase())
        return Resolved(u, StreamInfo(kind, codec, if (lossless) track.bitrateKbps else 320.coerceAtMost(track.bitrateKbps ?: 320), null, track.id),
            cacheKey = "sub:${track.nativeId}@${if (lossless) "raw" else "320"}")
    }

    override suspend fun probe() = "ping ${test(account() ?: throw SourceUnavailableException("not configured"))}"
}

/** Jellyfin: username/password → access token (stored encrypted). Lossless: /Audio/{id}/stream?static=true (original file). */
class JellyfinSource(private val account: () -> Account?, private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.JELLYFIN
    override fun available() = enabled() && account() != null

    private fun authHeader(token: String? = null) =
        "MediaBrowser Client=\"UpZe\", Device=\"Android\", DeviceId=\"musicspot-android\", Version=\"2.0\"" + (token?.let { ", Token=\"$it\"" } ?: "")

    /** Logs in and returns (accessToken, userId). */
    suspend fun login(server: String, user: String, password: String): Pair<String, String> {
        val body = buildJsonObject { put("Username", user); put("Pw", password) }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(serverBase(server).newBuilder().addPathSegments("Users/AuthenticateByName").build())
            .post(body).header("X-Emby-Authorization", authHeader()).header("Authorization", authHeader()).build()
        Http.client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw SourceUnavailableException("Jellyfin login failed (HTTP ${r.code})")
            // v2.0.14: no body!! / hard casts — a malformed reply is a clean source error.
            val body = r.body?.string() ?: throw SourceUnavailableException("Unexpected Jellyfin reply")
            val o = runCatching { Http.json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: throw SourceUnavailableException("Unexpected Jellyfin reply")
            val user = o["User"] as? JsonObject ?: throw SourceUnavailableException("Unexpected Jellyfin reply")
            return (o.s("AccessToken") ?: throw SourceUnavailableException("Unexpected Jellyfin reply")) to (user.s("Id") ?: throw SourceUnavailableException("Unexpected Jellyfin reply"))
        }
    }

    private fun base(a: Account) = serverBase(a.extra?.substringBefore('|') ?: "")
    private fun userId(a: Account) = a.extra?.substringAfter('|', "") ?: ""

    override suspend fun search(query: String, limit: Int): List<Track> {
        val a = account() ?: throw SourceUnavailableException("No Jellyfin server")
        val u = base(a).newBuilder().addPathSegments("Items").addQueryParameter("searchTerm", query)
            .addQueryParameter("IncludeItemTypes", "Audio").addQueryParameter("Recursive", "true")
            .addQueryParameter("UserId", userId(a)).addQueryParameter("Limit", limit.toString())
            .addQueryParameter("Fields", "MediaSources").build().toString()
        val o = Http.json.parseToJsonElement(Http.getString(u, mapOf("X-Emby-Authorization" to authHeader(a.secret), "Authorization" to authHeader(a.secret)))) as JsonObject
        return (o["Items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val it = e as? JsonObject ?: return@mapNotNull null
            val id = it.s("Id") ?: return@mapNotNull null
            val artists = (it["Artists"] as? JsonArray)?.mapNotNull { x -> (x as? JsonPrimitive)?.contentOrNull }?.joinToString(", ")
            val src = ((it["MediaSources"] as? JsonArray)?.firstOrNull() as? JsonObject)
            Track("jf:$id", it.s("Name").orEmpty(), artists ?: it.s("AlbumArtist").orEmpty(), it.s("Album"),
                (it.l("RunTimeTicks") ?: 0) / 10_000,
                (it.s("AlbumId") ?: id).let { img -> base(a).newBuilder().addPathSegments("Items/$img/Images/Primary").addQueryParameter("maxHeight", "600").addQueryParameter("api_key", a.secret).build().toString() },
                format = src?.s("Container") ?: it.s("Container"), bitrateKbps = src?.l("Bitrate")?.let { b -> (b / 1000).toInt() })
        }
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val a = account() ?: throw SourceUnavailableException("No Jellyfin server")
        val lossless = quality == Quality.LOSSLESS || (quality == Quality.MAX && !metered)
        val u = base(a).newBuilder().apply {
            if (lossless) addPathSegments("Audio/${track.nativeId}/stream").addQueryParameter("static", "true")
            else addPathSegments("Audio/${track.nativeId}/stream.mp3").addQueryParameter("audioBitRate", "320000")
        }.addQueryParameter("api_key", a.secret).build().toString()
        return Resolved(u, StreamInfo(kind, if (lossless) track.format?.uppercase() else "MP3", if (lossless) track.bitrateKbps else 320, null, track.id),
            cacheKey = "jf:${track.nativeId}@${if (lossless) "raw" else "320"}")
    }

    override suspend fun probe(): String {
        val a = account() ?: throw SourceUnavailableException("not configured")
        val o = Http.json.parseToJsonElement(Http.getString(base(a).newBuilder().addPathSegments("System/Info/Public").build().toString())) as JsonObject
        return "Jellyfin ${o.s("Version")}"
    }
}
