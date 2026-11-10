package com.ravi.musicspot.ai

import android.content.Context
import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.GcmBox
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.Http.await
import com.ravi.musicspot.logic.ListenStats
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 1.0.2 Settings › AI Integration. Any OpenAI-compatible chat API (OpenAI, Gemini, Groq, OpenRouter, DeepSeek,
 * Mistral, Anthropic's compatible endpoint, or any custom https base URL) with the user's own key.
 * It reads the user's taste (top artists and songs, recent plays, liked songs), asks for a mix, and finds each
 * suggested song on the user's sources. Only artist and song names are sent to the provider.
 */
object AiMix {
    data class Preset(val id: String, val label: String, val baseUrl: String, val model: String, val keyHint: String)
    val PRESETS = listOf(
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "sk-…"),
        Preset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "AIza…"),
        Preset("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "gsk_…"),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "sk-or-…"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "sk-…"),
        Preset("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", ""),
        Preset("anthropic", "Anthropic (Claude)", "https://api.anthropic.com/v1", "claude-haiku-4-5", "sk-ant-…"),
        Preset("custom", "Custom (OpenAI-compatible)", "", "", ""),
    )
    fun preset(id: String) = PRESETS.firstOrNull { it.id == id } ?: PRESETS.last()

    // ---- key storage: encrypted with the Android keystore, never in settings.json or backups ----------------------
    private const val PREFS = "secure_ai"; private const val KEY = "api_key"
    fun hasKey(ctx: Context) = !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null).isNullOrBlank()
    fun saveKey(ctx: Context, key: String?) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (key.isNullOrBlank()) p.remove(KEY) else p.putString(KEY, GcmBox.keystore().seal(key.trim()))
        p.apply()
    }
    private fun key(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        ?.let { runCatching { GcmBox.keystore().open(it) }.getOrNull() }

    fun endpoint(): Pair<String, String> {
        val s = Graph.settings.value; val p = preset(s.aiPreset)
        return s.aiBaseUrl.ifBlank { p.baseUrl }.trimEnd('/') to s.aiModel.ifBlank { p.model }
    }

    @Serializable data class Pick(val title: String, val artist: String)
    data class Result(val playlistId: String, val name: String, val found: List<Track>, val asked: Int)

    private val client by lazy { Http.client.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build() }

    /** One chat call; returns the reply text. Throws with a readable message. */
    suspend fun chat(ctx: Context, system: String, user: String): String = withContext(Dispatchers.IO) {
        val (base, model) = endpoint()
        if (base.isBlank()) throw IllegalStateException("Add the provider's base URL")
        if (model.isBlank()) throw IllegalStateException("Add a model name")
        val k = key(ctx)
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.9)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
        }
        val req = Request.Builder().url("$base/chat/completions")
            .apply { if (!k.isNullOrBlank()) header("Authorization", "Bearer $k") }
            .apply { if (base.contains("anthropic.com") && !k.isNullOrBlank()) header("x-api-key", k).header("anthropic-version", "2023-06-01") }
            .apply { if (base.contains("openrouter.ai")) header("HTTP-Referer", "https://github.com/").header("X-Title", "UpZe") }
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { Http.json.parseToJsonElement(text).jsonObject["error"]?.let { e -> (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString() } }.getOrNull()
                throw java.io.IOException(when (r.code) { 401, 403 -> "The API key was refused"; 404 -> "Model or URL not found"; 429 -> "Rate limit or no credit left"; else -> "HTTP ${r.code}" } +
                    (msg?.let { ": ${it.take(160)}" } ?: ""))
            }
            Http.json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?: throw java.io.IOException("The provider sent an empty answer")
        }
    }

    suspend fun test(ctx: Context): String = chat(ctx, "Reply with exactly: OK", "ping").trim().take(40)

    /** What the provider is told about the user: names only. */
    fun tasteProfile(): String {
        val stats = Graph.playStats.data.value
        val month = ListenStats.report(stats, ListenStats.Period.MONTH, limit = 25)
        val all = ListenStats.report(stats, ListenStats.Period.ALL, limit = 25)
        val lib = Graph.library.data.value
        fun songs(l: List<Track>) = l.joinToString("; ") { "${it.title} – ${ListenStats.mainArtist(it.artist)}" }
        return buildString {
            appendLine("Top artists (all time): " + all.artists.take(15).joinToString(", ") { it.name })
            appendLine("Top artists (last month): " + month.artists.take(10).joinToString(", ") { it.name })
            appendLine("Most played songs: " + songs(all.songs.take(20).map { it.track }))
            appendLine("Recently played: " + songs(lib.history.take(15).map { it.track }))
            appendLine("Liked songs: " + songs(lib.liked.take(20)))
            // 1.0.6 time-of-day: computed on the phone from local play times; only song names go into the prompt.
            val now = System.currentTimeMillis()
            val part = com.ravi.musicspot.logic.TimeOfDay.partAt(now)
            val atThisHour = com.ravi.musicspot.logic.TimeOfDay.picksFor(stats.stats.values, stats.dayparts, part, now, limit = 12)
            appendLine("Time of day now: ${part.label}")
            if (atThisHour.isNotEmpty()) appendLine("What they usually play at this time of day: " + songs(atThisHour))
            val seeds = com.ravi.musicspot.data.SeedArtists.state.value
            if (seeds.isNotEmpty()) appendLine("Artists they picked as favourites: " + seeds.joinToString(", ") { it.name })
        }
    }

    internal fun parsePicks(reply: String): List<Pick> {
        val start = reply.indexOf('['); val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching { Http.json.parseToJsonElement(reply.substring(start, end + 1)) as JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = o["title"]?.jsonPrimitive?.contentOrNull?.trim(); val a = o["artist"]?.jsonPrimitive?.contentOrNull?.trim()
            if (t.isNullOrBlank() || a.isNullOrBlank()) null else Pick(t, a)
        }.distinctBy { (it.title + "|" + it.artist).lowercase() }
    }

    /** Ask, find, and save as a new playlist. [onStep] reports progress text. */
    suspend fun makeMix(ctx: Context, mood: String, count: Int, onStep: (String) -> Unit): Result {
        onStep("Reading your taste…")
        val profile = tasteProfile()
        onStep("Asking ${preset(Graph.settings.value.aiPreset).label}…")
        val sys = "You are a music curator. Answer ONLY with a JSON array of objects {\"title\": string, \"artist\": string}. No prose, no markdown."
        val user = buildString {
            appendLine("Make a playlist of $count real, released songs for this listener.")
            appendLine(profile)
            if (mood.isNotBlank()) appendLine("The mix should fit: ${mood.trim()}")
            // 1.0.6: no mood typed → fit the time of day (morning energetic, afternoon focus/beats, late-night chill/lo-fi).
            else appendLine("The mix should fit the time of day: ${com.ravi.musicspot.logic.TimeOfDay.partAt(System.currentTimeMillis()).mood}")
            appendLine("Mix about 40% songs/artists they already love with 60% new discoveries that match their taste and language. No duplicates, at most 3 songs per artist, use the official song title and main artist.")
        }
        val picks = parsePicks(chat(ctx, sys, user)).take(count)
        if (picks.isEmpty()) throw java.io.IOException("The AI's answer had no songs in it; try again or another model")
        onStep("Finding ${picks.size} songs…")
        val gate = Semaphore(4)
        val found = coroutineScope {
            picks.map { p -> async { gate.withPermit { find(p) } } }.awaitAll()
        }.filterNotNull().distinctBy { it.id }
        if (found.isEmpty()) throw java.io.IOException("None of the suggested songs could be found on your sources")
        val name = "AI Mix · " + (mood.trim().takeIf { it.isNotBlank() }?.take(28)?.replaceFirstChar { it.uppercase() }
            ?: java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date()))
        val pl = Graph.library.createPlaylist(name, found, origin = "local", art = found.firstOrNull()?.artUrl)
        return Result(pl.id, name, found, picks.size)
    }

    private suspend fun find(p: Pick): Track? {
        val q = "${p.title} ${p.artist}"
        val norm = { s: String -> java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[^\\p{L}\\p{N} ]"), "") }
        val want = norm(p.title).split(' ').filter { it.length > 1 }
        fun good(t: Track) = norm(t.title).let { title -> want.isEmpty() || want.count { it in title } >= (want.size + 1) / 2 }
        val sources = listOf<suspend () -> List<Track>>({ Graph.yt.search(q, 5) }, { Graph.jiosaavn.search(q, 5) })
        for (s in sources) {
            val r = runCatching { s() }.getOrNull().orEmpty()
            r.firstOrNull { good(it) }?.let { return it }
        }
        return null
    }

}
