/*
 * Innertube (youtubei) client for UpZe. Request shapes follow OpenTune 1.0.4 core/data/innertube/Innertube.kt
 * (GPL-3.0-or-later): WEB_REMIX for browse/search/next with SAPISIDHASH signing, device clients for `player`,
 * a visitor id from sw.js_data. Rewritten on OkHttp with cancellable calls. GPL-3.0-or-later.
 */
package com.ravi.musicspot.yt

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.Http.await
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

class Innertube(
    private val http: OkHttpClient = Http.client,
    /** youtubei base for app-shaped player clients (tests point this at a MockWebServer). */
    private val ytBase: String = YT_BASE,
    private val musicBase: String = MUSIC_BASE,
    /** Visitor-id source; null disables the fetch (tests). */
    private val visitorUrl: String? = "https://www.youtube.com/sw.js_data",
) {
    /** Port of OpenTune 1.0.4 Innertube.UnplayableException's classification (GPL-3.0-or-later). */
    class UnplayableException(val status: String, val reason: String?) : IOException("$status: ${reason ?: "unplayable"}") {
        private val text get() = reason ?: status
        val isAgeGate: Boolean get() = listOf("confirm your age", "age-restricted", "age restricted", "inappropriate for some users").any { text.contains(it, true) }
        val looksLikeBotCheck: Boolean get() = !isAgeGate && listOf("bot", "unusual traffic", "sign in", "login_required").any { text.contains(it, true) || status.contains(it, true) }
        val isPermanent: Boolean get() = listOf("not available in your country", "who has blocked it in your country", "removed by the uploader",
            "account associated with this video has been terminated", "private video", "members-only").any { text.contains(it, true) }
    }

    /** Session cookie from the sign-in WebView; null = guest. Set from the encrypted account store only. */
    @Volatile var cookie: String? = null
        set(value) { if (field != value) visitorData = null; field = value }

    @Volatile private var visitorData: String? = null
    @Volatile var region: String = Locale.getDefault().country.uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "IN"
    val language: String get() = Locale.getDefault().language.ifEmpty { "en" }.let { if (it == "iw") "he" else if (it == "in") "id" else it }
    val signedIn: Boolean get() = cookie?.let { sapisidFrom(it) } != null

    private val jsonType = "application/json".toMediaType()

    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh) visitorData?.let { return it }
        val src = visitorUrl ?: return visitorData
        val body = runCatching { Http.getString(src, mapOf("User-Agent" to PlayerClient.WEB_REMIX.userAgent), http) }.getOrNull()
            ?: return visitorData
        val payload = runCatching { Http.json.parseToJsonElement(body.substringAfter("\n", body.drop(5))) }.getOrNull() ?: return null
        visitorData = findVisitor(payload)
        return visitorData
    }

    private fun findVisitor(e: JsonElement): String? = when (e) {
        is JsonArray -> e.firstNotNullOfOrNull { findVisitor(it) }
        is JsonPrimitive -> e.contentOrNull?.takeIf { VISITOR.matches(it) }
        else -> null
    }

    // ---- WEB_REMIX endpoints ----------------------------------------------------------------------------------
    suspend fun browse(browseId: String, params: String? = null): JsonObject = postMusic("browse") {
        put("browseId", browseId); params?.let { put("params", it) }
    }

    suspend fun browseContinuation(token: String): JsonObject =
        postMusic("browse", mapOf("ctoken" to token, "continuation" to token, "type" to "next")) { put("continuation", token) }

    suspend fun charts(country: String?): JsonObject = postMusic("browse") {
        put("browseId", "FEmusic_charts")
        country?.let { c -> putJsonObject("formData") { putJsonArray("selectedValues") { add(c) } } }
    }

    suspend fun search(query: String, params: String? = null): JsonObject = postMusic("search") {
        put("query", query); params?.let { put("params", it) }
    }

    suspend fun searchContinuation(token: String): JsonObject =
        postMusic("search", mapOf("ctoken" to token, "continuation" to token, "type" to "next")) { put("continuation", token) }

    suspend fun suggestions(input: String): JsonObject = postMusic("music/get_search_suggestions") { put("input", input) }

    /** Watch-next: radio queue ("RDAMVM<id>"), related browse id, lyrics browse id. */
    suspend fun next(videoId: String, playlistId: String? = "RDAMVM$videoId"): JsonObject = postMusic("next") {
        put("videoId", videoId); playlistId?.let { put("playlistId", it) }; put("isAudioOnly", true)
    }

    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu") {}

    suspend fun like(videoId: String, like: Boolean) {
        postMusic(if (like) "like/like" else "like/removelike") { putJsonObject("target") { put("videoId", videoId) } }
    }

    suspend fun createPlaylist(title: String, videoIds: List<String>): String? =
        postMusic("playlist/create") {
            put("title", title); put("privacyStatus", "PRIVATE")
            putJsonArray("videoIds") { videoIds.forEach { add(it) } }
        }["playlistId"].let { it as? JsonPrimitive }?.contentOrNull // v2.0.16: safe cast

    suspend fun addToPlaylist(playlistId: String, videoId: String) {
        postMusic("browse/edit_playlist") {
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions") { add(buildJsonObject { put("action", "ACTION_ADD_VIDEO"); put("addedVideoId", videoId) }) }
        }
    }

    // ---- player (device clients, anonymous unless asked) ------------------------------------------------------
    suspend fun player(videoId: String, client: PlayerClient, authenticated: Boolean = false, signatureTimestamp: Int? = null): JsonObject {
        ensureVisitorData()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.clientName); put("clientVersion", client.clientVersion)
                    client.osName?.let { put("osName", it) }; client.osVersion?.let { put("osVersion", it) }
                    client.deviceMake?.let { put("deviceMake", it) }; client.deviceModel?.let { put("deviceModel", it) }
                    client.androidSdkVersion?.let { put("androidSdkVersion", it.toInt()) }
                    put("hl", language); put("gl", region)
                    visitorData?.let { put("visitorData", it) }
                }
            }
            if (client.needsSignatureTimestamp && signatureTimestamp != null) {
                putJsonObject("playbackContext") { putJsonObject("contentPlaybackContext") { put("signatureTimestamp", signatureTimestamp) } }
            }
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
        }
        val base = if (client.usesMusicHost) musicBase else ytBase
        val rb = Request.Builder().url("$base/player?prettyPrint=false")
            .post(body.toString().toRequestBody(jsonType))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientId)
            .header("X-YouTube-Client-Version", client.clientVersion)
        client.origin?.let { rb.header("Origin", it) }
        client.referer?.let { rb.header("Referer", it) }
        visitorData?.let { rb.header("X-Goog-Visitor-Id", it) }
        val c = cookie
        if (authenticated && c != null) {
            rb.header("Cookie", c)
            sapisidFrom(c)?.let { rb.header("Authorization", sapisidHash(it, client.origin ?: if (client.usesMusicHost) MUSIC_ORIGIN else YT_ORIGIN)) }
        }
        val res = execJson(rb.build())
        // v2.0.16: safe casts; a non-object playabilityStatus / non-primitive field no longer throws IllegalArgumentException.
        val ps = res["playabilityStatus"] as? JsonObject
        val status = (ps?.get("status") as? JsonPrimitive)?.contentOrNull
        if (status != null && status != "OK") {
            val reason = (ps?.get("reason") as? JsonPrimitive)?.contentOrNull
            throw UnplayableException(status, reason)
        }
        return res
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------
    private suspend fun postMusic(endpoint: String, query: Map<String, String> = emptyMap(), extras: JsonObjectBuilder.() -> Unit): JsonObject {
        if (visitorData == null) ensureVisitorData()
        val url = "$musicBase/$endpoint".toHttpUrl().newBuilder().addQueryParameter("prettyPrint", "false")
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX"); put("clientVersion", PlayerClient.WEB_REMIX.clientVersion)
                    put("hl", language); put("gl", region); visitorData?.let { put("visitorData", it) }
                }
                putJsonObject("user") { put("lockedSafetyMode", false) }
                putJsonObject("request") { put("useSsl", true) }
            }
            extras()
        }
        var attempt = 0
        while (true) {
            val rb = Request.Builder().url(url).post(body.toString().toRequestBody(jsonType))
                .header("User-Agent", PlayerClient.WEB_REMIX.userAgent)
                .header("Accept-Language", "$language,en;q=0.8")
                .header("Origin", MUSIC_ORIGIN).header("X-Origin", MUSIC_ORIGIN).header("Referer", "$MUSIC_ORIGIN/")
                .header("X-YouTube-Client-Name", PlayerClient.WEB_REMIX.clientId)
                .header("X-YouTube-Client-Version", PlayerClient.WEB_REMIX.clientVersion)
            visitorData?.let { rb.header("X-Goog-Visitor-Id", it) }
            cookie?.let { c ->
                rb.header("Cookie", c); rb.header("X-Goog-AuthUser", "0")
                sapisidFrom(c)?.let { rb.header("Authorization", sapisidHash(it, MUSIC_ORIGIN)) }
            }
            try {
                val res = execJson(rb.build())
                if (visitorData == null) visitorData = ((res["responseContext"] as? JsonObject)?.get("visitorData") as? JsonPrimitive)?.contentOrNull // v2.0.16: safe casts
                return res
            } catch (e: Http.HttpStatusException) {
                if (e.code !in RETRYABLE || ++attempt >= 3) throw e
                delay(400L * attempt)
            }
        }
    }

    private suspend fun execJson(req: Request): JsonObject = http.newCall(req).await().use { r ->
        if (!r.isSuccessful) throw Http.HttpStatusException(r.code, req.url.toString())
        // v2.0.16: non-JSON / non-object bodies become an IOException (callers handle IO) instead of SerializationException/IAE.
        val text = r.body?.string().orEmpty()
        (runCatching { Http.json.parseToJsonElement(text) }.getOrNull() as? JsonObject)
            ?: throw java.io.IOException("Innertube: response is not a JSON object (${text.length} bytes)")
    }

    companion object {
        const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
        const val YT_BASE = "https://www.youtube.com/youtubei/v1"
        const val MUSIC_ORIGIN = "https://music.youtube.com"
        const val YT_ORIGIN = "https://www.youtube.com"
        private val RETRYABLE = setOf(429, 500, 502, 503, 504)
        private val VISITOR = Regex("""Cg[A-Za-z0-9_%-]{40,}""")
        private val SAPISID_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

        fun sapisidFrom(cookieHeader: String): String? {
            val jar = cookieHeader.split(';').mapNotNull { e ->
                val n = e.substringBefore('=').trim(); val v = e.substringAfter('=', "").trim()
                if (n.isEmpty() || v.isEmpty()) null else n to v
            }.toMap()
            return SAPISID_NAMES.firstNotNullOfOrNull { jar[it] }
        }

        fun sapisidHash(sapisid: String, origin: String, nowSec: Long = System.currentTimeMillis() / 1000): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$nowSec $sapisid $origin".toByteArray())
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
            return "SAPISIDHASH ${nowSec}_$digest"
        }
    }
}
