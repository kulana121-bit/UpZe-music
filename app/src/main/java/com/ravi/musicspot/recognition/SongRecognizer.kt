package com.ravi.musicspot.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.Http.await
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Pack 6 Part 2: "What's playing" (music recognition). Route verified before the UI was built: Shazam's public
 * recognition endpoint (the one SongRec uses) answers a [ShazamSignature] with artist + title, with NO account and NO
 * API key. Only the fingerprint is sent (no audio, no location); the request ids are random per request.
 */
data class RecognizedSong(val title: String, val artist: String, val album: String? = null, val isrc: String? = null, val artUrl: String? = null)

object ShazamClient {
    private const val ENDPOINT = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/"
    private const val QUERY = "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"

    /** The song for [sig], or null when Shazam found no match. Throws an IOException when the service can't be reached. */
    suspend fun identify(sig: ShazamSignature): RecognizedSong? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val body = """{"signature":{"samplems":${sig.durationMs},"timestamp":$now,"uri":"${sig.uri()}"},"timestamp":$now,"timezone":"Europe/London"}"""
        val req = Request.Builder()
            .url(ENDPOINT + UUID.randomUUID().toString().uppercase() + "/" + UUID.randomUUID() + QUERY)
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 10)")
            .header("Content-Language", "en_US")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw Http.HttpStatusException(r.code, ENDPOINT)
            parse(r.body?.string().orEmpty())
        }
    }

    /** Shazam's answer → song (null for no match or an unreadable answer). */
    fun parse(json: String): RecognizedSong? {
        val root = runCatching { Http.json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val matches = root["matches"] as? JsonArray
        val track = root["track"] as? JsonObject ?: return null
        if (matches != null && matches.isEmpty()) return null
        fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val title = track.str("title") ?: return null
        val artist = track.str("subtitle") ?: return null
        val album = runCatching {
            track["sections"]?.jsonArray?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("type") == "SONG" }
                ?.get("metadata")?.jsonArray?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("title") == "Album" }?.str("text")
        }.getOrNull()
        val art = runCatching { track["images"]?.jsonObject?.get("coverart")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return RecognizedSong(title, artist, album, track.str("isrc"), art)
    }
}

/**
 * Turns a recognised song into a playable UpZe track: the user's enabled sources are searched (as AiMix finds its
 * picks) and the best same-recording candidate wins. Pure parts ([pick], [target]) are unit-tested.
 */
object RecognitionMatch {
    /** Recognition matches need a clear title + artist agreement (the cross-source threshold, not the lenient one). */
    const val THRESHOLD = TrackMatcher.THRESHOLD

    fun target(s: RecognizedSong) = Track(id = "rec:0", title = s.title, artist = s.artist, album = s.album, isrc = s.isrc)

    fun query(s: RecognizedSong) = "${s.title} ${s.artist.substringBefore(',').substringBefore('&').trim()}".trim()

    /**
     * Best candidate across [results] (one list per source, in the user's source priority order): highest match score
     * at or above [THRESHOLD]; within 0.02 of the best, the earlier (higher-priority) source wins. Null = no match.
     */
    fun pick(s: RecognizedSong, results: List<List<Track>>): Track? {
        val t = target(s)
        val scored = results.flatMapIndexed { order, list -> list.map { Triple(it, TrackMatcher.score(t, it, lenient = true), order) } }
            .filter { it.second >= THRESHOLD }
        val top = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second >= top - 0.02 }.minWith(compareBy<Triple<Track, Double, Int>> { it.third }.thenByDescending { it.second }).first
    }

    /** Searches every enabled source (5 per source, 6 s each, in parallel) and picks the best. Network. */
    suspend fun resolve(s: RecognizedSong): Track? {
        val g = com.ravi.musicspot.Graph
        val enabled = g.sources.enabledKinds()
        val order = (g.settings.value.sourcePriority + enabled).distinct().filter { it in enabled && it != SourceKind.ARCHIVE && it != SourceKind.RADIO }
        val q = query(s)
        val results = coroutineScope {
            order.map { k ->
                async {
                    try { withTimeoutOrNull(6_000) { g.sources.source(k)?.search(q, 5) }.orEmpty() }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                }
            }.awaitAll()
        }
        return pick(s, results)
    }
}

/**
 * The microphone part: records 16 kHz mono into memory only (never to a file), tries to identify at 4, 8 and 12 s,
 * and stops the microphone the moment a song is found, the 12 s are up, or the caller's coroutine is cancelled
 * (sheet closed / Cancel). The PCM is dropped when [listen] returns.
 */
object MicRecognizer {
    sealed interface Outcome {
        data class Found(val song: RecognizedSong) : Outcome
        data object NoMatch : Outcome
        data class Failed(val message: String) : Outcome
    }

    const val MAX_MS = 12_000
    private val CHECKPOINTS_MS = intArrayOf(4_000, 8_000, 12_000)

    /** Needs RECORD_AUDIO (the caller checks it). [onLevel] gets a 0..1 input level for the listening animation. */
    @SuppressLint("MissingPermission")
    suspend fun listen(onLevel: (Float) -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        val rate = ShazamSignature.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext Outcome.Failed("The microphone isn't available")
        val rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate)) }
            catch (e: Exception) { return@withContext Outcome.Failed("The microphone isn't available") }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext Outcome.Failed("The microphone isn't available") }
        val pcm = ShortArray(rate * MAX_MS / 1000)
        var n = 0
        var lastError: String? = null
        try {
            rec.startRecording()
            val chunk = ShortArray(rate / 10)
            for (cp in CHECKPOINTS_MS) {
                val until = rate * cp / 1000
                while (n < until && isActive) {
                    val r = rec.read(chunk, 0, minOf(chunk.size, until - n))
                    if (r < 0) return@withContext Outcome.Failed("The microphone stopped (error $r)")
                    System.arraycopy(chunk, 0, pcm, n, r); n += r
                    var peak = 0
                    for (i in 0 until r) peak = maxOf(peak, kotlin.math.abs(chunk[i].toInt()))
                    onLevel(peak / 32768f)
                }
                if (!isActive) break
                val sig = ShazamSignature().apply { feed(pcm, n) }
                if (sig.peakCount < 20) continue // near silence: nothing to send yet
                try {
                    ShazamClient.identify(sig)?.let { return@withContext Outcome.Found(it) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { lastError = "Couldn't reach the recognition service" }
            }
            if (lastError != null) Outcome.Failed(lastError) else Outcome.NoMatch
        } finally {
            runCatching { rec.stop() }
            rec.release()
            java.util.Arrays.fill(pcm, 0) // nothing of the recording outlives the call
        }
    }
}
