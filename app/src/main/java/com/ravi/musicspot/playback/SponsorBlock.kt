package com.ravi.musicspot.playback

import com.ravi.musicspot.core.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.0.2 Playback › SponsorBlock (sponsor.ajay.app, community data). For YouTube songs only. Uses the k-anonymous
 * hash-prefix lookup, so the server never learns which video is playing.
 */
object SponsorBlock {
    data class Segment(val startMs: Long, val endMs: Long, val category: String)

    /** id → label, in the order the settings page shows them. */
    val CATEGORIES = linkedMapOf(
        "music_offtopic" to "Non-music parts (talking, skits)",
        "sponsor" to "Sponsors",
        "selfpromo" to "Self-promotion",
        "intro" to "Intros / intermissions",
        "outro" to "Outros / end cards",
        "interaction" to "Subscribe reminders",
        "preview" to "Previews / recaps",
        "filler" to "Filler tangents",
    )

    private val cache = ConcurrentHashMap<String, List<Segment>>()

    fun cached(videoId: String, categories: Set<String>): List<Segment>? = cache[videoId]?.filter { it.category in categories }

    suspend fun segments(videoId: String, categories: Set<String>): List<Segment> {
        cache[videoId]?.let { return it.filter { s -> s.category in categories } }
        val prefix = sha256(videoId).take(4)
        val cats = URLEncoder.encode(CATEGORIES.keys.joinToString(",", "[", "]") { "\"$it\"" }, "UTF-8")
        val body = try { Http.getString("https://sponsor.ajay.app/api/skipSegments/$prefix?categories=$cats") }
            catch (e: Http.HttpStatusException) { if (e.code == 404) "[]" else throw e }
        val all = parse(body, videoId)
        cache[videoId] = all
        return all.filter { it.category in categories }
    }

    internal fun parse(body: String, videoId: String): List<Segment> =
        runCatching { Http.json.parseToJsonElement(body).jsonArray }.getOrDefault(JsonArray(emptyList()))
            .map { it.jsonObject }.firstOrNull { it["videoID"]?.jsonPrimitive?.content == videoId }
            ?.get("segments")?.jsonArray?.mapNotNull { s ->
                val o = s.jsonObject
                if (o["actionType"]?.jsonPrimitive?.content.let { it != null && it != "skip" }) return@mapNotNull null
                val seg = o["segment"]?.jsonArray ?: return@mapNotNull null
                val a = (seg[0].jsonPrimitive.double * 1000).toLong(); val b = (seg[1].jsonPrimitive.double * 1000).toLong()
                if (b - a < 1_000) null else Segment(a, b, o["category"]?.jsonPrimitive?.content ?: "")
            }.orEmpty().sortedBy { it.startMs }

    /** The segment [posMs] is inside (with a little lead-in so a skip never replays its first second), or null. */
    fun skipAt(segments: List<Segment>, posMs: Long, durationMs: Long): Segment? =
        segments.firstOrNull { posMs >= it.startMs && posMs < it.endMs - 600 && (durationMs <= 0 || it.startMs < durationMs - 1_500) }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
