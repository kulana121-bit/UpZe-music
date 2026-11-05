/*
 * YouTube stream resolution. The client walk and media headers follow OpenTune 1.0.4 StreamResolver/PlayerClient
 * (GPL-3.0-or-later); v2.0.3 ports its playerStream walk (preferred client first, per-track stand-down, n-param
 * deobfuscation behind a mutex, cver patch, byte probe, onPlaybackRefused). Format ranking, cancellation, the
 * availability wait and the NewPipe fallback are UpZe's. GPL-3.0-or-later.
 */
package com.ravi.musicspot.yt

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.Http.await
import com.ravi.musicspot.model.Quality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** One audio rendition YouTube offers. */
data class YtAudio(
    val itag: Int, val mime: String, val bitrate: Int, val url: String?, val sampleRate: Int? = null, val contentLength: Long? = null,
    /** `signatureCipher` (or legacy `cipher`) query string for formats without a plain [url]. */
    val cipher: String? = null,
) {
    val opus get() = "opus" in mime
    val kbps get() = bitrate / 1000
    val codec get() = if (opus) "Opus" else if ("mp4a" in mime) "AAC" else mime.substringAfter('/').substringBefore(';')
}

data class YtResolved(val url: String, val headers: Map<String, String>, val audio: YtAudio, val client: String)

/** Every client and the NewPipe failsafe failed (or were stood down) for this track. */
class YtExhaustedException(msg: String) : java.io.IOException(msg)

object YtFormats {
    /** Premium-only higher renditions: 774 (Opus ~256k), 141 (AAC 256k). */
    val PREMIUM = setOf(774, 141)

    /**
     * Ranks audio renditions for a quality setting. MAX/LOSSLESS (YouTube has no lossless) take the highest bitrate,
     * preferring Opus at equal bitrate; HIGH caps at ~160 kbps (itag 251/140); AUTO behaves as MAX on unmetered
     * networks and HIGH on metered ones.
     */
    fun rank(formats: List<YtAudio>, quality: Quality, metered: Boolean): List<YtAudio> {
        val audio = formats.filter { it.mime.startsWith("audio/") }
        val cap = when (quality) {
            Quality.HIGH -> 170
            Quality.AUTO -> if (metered) 170 else Int.MAX_VALUE
            Quality.MAX, Quality.LOSSLESS -> Int.MAX_VALUE
        }
        val (under, over) = audio.partition { it.kbps <= cap }
        val order = compareByDescending<YtAudio> { it.kbps.coerceAtMost(cap) / 16 }.thenByDescending { it.opus }.thenByDescending { it.bitrate }
        return under.sortedWith(order) + over.sortedBy { it.bitrate }
    }

    /**
     * Why a player response yielded no usable audio: playability status/reason, adaptive/muxed format counts, the
     * mime types seen, how many audio formats had a url / cipher, and whether only an HLS manifest was offered.
     */
    fun describe(player: JsonObject): String {
        val ps = player["playabilityStatus"] as? JsonObject
        val status = (ps?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "?"
        val reason = (ps?.get("reason") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val sd = player["streamingData"] as? JsonObject ?: return "status $status${reason?.let { " ($it)" } ?: ""}, no streamingData"
        val adaptive = (sd["adaptiveFormats"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val muxed = (sd["formats"] as? kotlinx.serialization.json.JsonArray).orEmpty().size
        fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val audio = adaptive.filter { it.str("mimeType")?.startsWith("audio/") == true }
        val mimes = adaptive.mapNotNull { it.str("mimeType")?.substringBefore(';') }.groupingBy { it }.eachCount()
        val hls = sd.str("hlsManifestUrl") != null
        val sabr = sd.str("serverAbrStreamingUrl") != null
        return "status $status, adaptive ${adaptive.size} (audio ${audio.size}: url ${audio.count { it.str("url") != null }}, " +
            "cipher ${audio.count { it.str("signatureCipher") != null || it.str("cipher") != null }}), muxed $muxed, mimes $mimes" +
            (if (hls) ", HLS manifest only (not used: the progressive pipeline cannot play HLS)" else "") + (if (sabr) ", SABR url" else "")
    }

    fun parse(player: JsonObject): List<YtAudio> {
        // v2.0.16: safe casts on the player JSON (hard .jsonObject/.jsonArray threw on unexpected shapes).
        val sd = player["streamingData"] as? JsonObject ?: return emptyList()
        val list = sd["adaptiveFormats"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val mime = o["mimeType"]?.prim?.contentOrNull ?: return@mapNotNull null
            if (!mime.startsWith("audio/")) return@mapNotNull null
            YtAudio(
                itag = o["itag"]?.prim?.intOrNull ?: 0,
                mime = mime,
                bitrate = o["bitrate"]?.prim?.intOrNull ?: o["averageBitrate"]?.prim?.intOrNull ?: 0,
                url = o["url"]?.prim?.contentOrNull,
                sampleRate = o["audioSampleRate"]?.prim?.contentOrNull?.toIntOrNull(),
                contentLength = o["contentLength"]?.prim?.contentOrNull?.toLongOrNull(),
                cipher = (o["signatureCipher"] ?: o["cipher"])?.prim?.contentOrNull,
            )
        }
    }
}

/**
 * YouTube's player JavaScript, behind one mutex. NewPipe's [YoutubeJavaScriptPlayerManager] keeps its parsed player,
 * deobfuscation functions and even a failed parse's exception in static, unsynchronised fields, so every call is
 * serialised. Tests substitute a fake.
 */
interface YtJsPlayer {
    suspend fun signatureTimestamp(videoId: String): Int?
    /** Solved signature, or null when it cannot be solved. */
    suspend fun decipher(videoId: String, signature: String): String?
    /** [url] with its `n` parameter transformed; the original URL when that fails (the probe has the final say). */
    suspend fun deobfuscateN(videoId: String, url: String): String
}

class NewPipeJsPlayer(private val region: () -> String = { "IN" }) : YtJsPlayer {
    private val mutex = Mutex()
    @Volatile private var timestamp: Int? = null
    /** NewPipe cannot parse the current base.js: ciphered formats are pointless for the rest of the process. */
    @Volatile private var solverBroken = false

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock {
        runInterruptible(Dispatchers.IO) { NewPipeBridge.ensureInit(region()); block() }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(e) }

    override suspend fun signatureTimestamp(videoId: String): Int? = timestamp
        ?: attempt { locked { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) } }.getOrNull()?.also { timestamp = it }

    override suspend fun decipher(videoId: String, signature: String): String? {
        if (solverBroken) return null
        return attempt { locked { YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, signature) } }
            .onFailure { e -> if (generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.any { "deobfuscation function" in (it.message ?: "").lowercase() }) solverBroken = true }
            .getOrNull()
    }

    override suspend fun deobfuscateN(videoId: String, url: String): String =
        attempt { locked { YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url) } }.getOrDefault(url)
}

/**
 * Port of OpenTune 1.0.4 StreamResolver.playerStream (GPL-3.0-or-later): walks [CLIENTS] starting with the client
 * that last worked, unlocks each URL (signature cipher, `n` parameter, `cver` patch), waits until the URL is live and
 * proves it with a 16 KB ranged GET carrying the exact headers the player will send. A client whose URL is refused is
 * stood down for that track. [onPlaybackRefused] feeds a refusal seen during playback back into the walk.
 *
 * Fresh googlevideo URLs answer 403 for roughly 2–7 s after the player response. The response's own availability
 * time is honoured when present ([availableAtMs]); otherwise [availabilityWaitMs] passes before the first byte request,
 * and a refused probe is retried once after another wait before the client is dropped.
 */
class YtStreamResolver(
    private val tube: Innertube,
    private val js: YtJsPlayer = NewPipeJsPlayer { tube.region },
    media: OkHttpClient = Http.client,
    private val availabilityWaitMs: Long = 3_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val newPipeFallback: Boolean = true,
) {
    private enum class Probe { OK, REFUSED, UNREACHABLE }

    private val prober = media.newBuilder().callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).build()

    /** The client that served the last track; the walk starts there. */
    @Volatile var preferred: PlayerClient? = null
        private set

    /** "videoId|client@version" → stood down until (epoch ms). */
    private val standDownUntil = ConcurrentHashMap<String, Long>()

    /** URLs handed out → (videoId, minting client), so a playback refusal can find its way back. Bounded. */
    private val minted = object : LinkedHashMap<String, Pair<String, PlayerClient>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, PlayerClient>>?) = size > 64
    }

    private fun key(videoId: String, c: PlayerClient) = "$videoId|${c.clientName}@${c.clientVersion}"

    fun isStoodDown(videoId: String, c: PlayerClient): Boolean {
        val until = standDownUntil[key(videoId, c)] ?: return false
        if (until > now()) return true
        standDownUntil.remove(key(videoId, c)); return false
    }

    /** Drops every stand-down for [videoId] (all clients stood down: one fresh walk). */
    fun clearStandDowns(videoId: String) { standDownUntil.keys.removeAll { it.startsWith("$videoId|") } }

    private fun standDown(videoId: String, c: PlayerClient) {
        if (standDownUntil.size > 512) standDownUntil.clear()
        standDownUntil[key(videoId, c)] = now() + STAND_DOWN_MS
        if (preferred == c) preferred = null
    }

    /** Walk order: the preferred client first, then [CLIENTS], then WEB_REMIX with the session when signed in. */
    fun clientOrder(signedIn: Boolean = tube.signedIn): List<PlayerClient> {
        val base = CLIENTS + (if (signedIn) listOf(PlayerClient.WEB_REMIX) else emptyList())
        val first = preferred?.takeIf { it in base } ?: return base
        return listOf(first) + base.filterNot { it == first }
    }

    suspend fun resolve(videoId: String, quality: Quality, metered: Boolean): YtResolved {
        val errors = mutableListOf<String>()
        val signedIn = tube.signedIn
        var freshVisitor = false
        runCatching { tube.ensureVisitorData() }
        com.ravi.musicspot.core.PlaybackLog.log("YouTube resolve $videoId (signedIn=$signedIn, quality=$quality, clients=${clientOrder(signedIn).joinToString { it.clientName }})")
        var refused = 0; var tried = 0
        // v2.0.5: when every client is stood down for this track, clear the stand-downs once and walk again once,
        // instead of failing instantly on every retry for the next 10 minutes (Ravi's log, 10:45-10:47).
        for (pass in 0..1) {
        if (pass == 1) {
            if (tried > 0) break
            clearStandDowns(videoId)
            com.ravi.musicspot.core.PlaybackLog.log("  every client was stood down for this track: cleared once, walking again")
        }
        walk@ for (client in clientOrder(signedIn)) {
            coroutineContext.ensureActive()
            if (isStoodDown(videoId, client)) { errors += "${client.clientName}: stood down"; com.ravi.musicspot.core.PlaybackLog.log("  ${client.clientName}: stood down for this track"); continue }
            tried++
            // Signed in: ANDROID_MUSIC carries the session (Premium renditions); WEB_REMIX only makes sense with it.
            val auth = signedIn && (client == PlayerClient.ANDROID_MUSIC || client == PlayerClient.WEB_REMIX)
            try {
                val ts = if (client.needsSignatureTimestamp) js.signatureTimestamp(videoId)
                    ?: run { errors += "${client.clientName}: no signature timestamp"; null } else null
                if (client.needsSignatureTimestamp && ts == null) continue
                var fetchedAt = now()
                val res = try {
                    tube.player(videoId, client, authenticated = auth, signatureTimestamp = ts)
                } catch (e: Innertube.UnplayableException) {
                    when {
                        e.isAgeGate && signedIn && !auth -> { fetchedAt = now(); tube.player(videoId, client, authenticated = true, signatureTimestamp = ts) }
                        e.looksLikeBotCheck && !freshVisitor -> {
                            freshVisitor = true
                            tube.ensureVisitorData(refresh = true)
                            fetchedAt = now(); tube.player(videoId, client, authenticated = auth, signatureTimestamp = ts)
                        }
                        else -> throw e
                    }
                }
                com.ravi.musicspot.core.PlaybackLog.log("  ${client.clientName}: player HTTP 200 (status ${res["playabilityStatus"]?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("status")?.prim?.contentOrNull } ?: "?"})")
                val parsed = YtFormats.parse(res)
                val ranked = YtFormats.rank(parsed, quality, metered).filter { it.url != null || it.cipher != null }
                if (ranked.isEmpty()) {
                    // v2.0.5: say why (IOS often answers with only an HLS manifest, or formats without url/cipher).
                    val why = YtFormats.describe(res)
                    com.ravi.musicspot.core.PlaybackLog.log("  ${client.clientName}: no usable audio formats: $why")
                    errors += "${client.clientName}: no audio formats"; standDown(videoId, client); continue
                }
                var picked: YtAudio? = null; var url: String? = null
                for (a in ranked) {
                    val u = unlock(videoId, a)?.let { patchClientVersion(it, client.clientVersion) } ?: continue
                    picked = a; url = u; break
                }
                if (picked == null || url == null) { errors += "${client.clientName}: formats could not be unlocked"; standDown(videoId, client); continue }
                val headers = client.mediaHeaders()
                val waitAt = availableAtMs(res, fetchedAt) ?: (fetchedAt + availabilityWaitMs)
                com.ravi.musicspot.core.PlaybackLog.log("  ${client.clientName}: ${picked.codec} ${picked.bitrate / 1000}kbps ${if (picked.cipher != null) "ciphered (decipher ok)" else "plain url"}, availability wait ${(waitAt - now()).coerceIn(0, MAX_AVAILABILITY_WAIT_MS)} ms")
                waitUntil(waitAt)
                var verdict = probe(url, headers, client.clientName)
                if (verdict == Probe.REFUSED) {
                    // A URL probed a moment too early answers 403 too: wait once more before blaming the client.
                    sleep(availabilityWaitMs)
                    com.ravi.musicspot.core.PlaybackLog.log("  ${client.clientName}: probe refused, waiting ${availabilityWaitMs} ms and retrying once")
                    verdict = probe(url, headers, client.clientName)
                }
                when (verdict) {
                    Probe.OK -> {
                        preferred = client
                        com.ravi.musicspot.core.PlaybackLog.log("  ${client.clientName}: OK")
                        synchronized(minted) { minted[url] = videoId to client }
                        return YtResolved(url, headers, picked.copy(url = url), client.clientName)
                    }
                    Probe.REFUSED -> { refused++; errors += "${client.clientName}: stream refused (403)"; standDown(videoId, client) }
                    Probe.UNREACHABLE -> errors += "${client.clientName}: stream unreachable"
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Innertube.UnplayableException) {
                com.ravi.musicspot.core.PlaybackLog.log("  ${client.clientName}: ${e.javaClass.simpleName}: ${e.message}")
                errors += "${client.clientName}: ${e.message}"
                if (e.isPermanent) break@walk
                standDown(videoId, client)
            } catch (e: Exception) {
                com.ravi.musicspot.core.PlaybackLog.log("  ${client.clientName}: ${e.javaClass.simpleName}: ${e.message}${(e as? com.ravi.musicspot.core.Http.HttpStatusException)?.let { " (player HTTP ${it.code})" } ?: ""}")
                if ((e as? com.ravi.musicspot.core.Http.HttpStatusException)?.code in REFUSAL_CODES) refused++
                errors += "${client.clientName}: ${e.message}"
            }
        }
        }
        coroutineContext.ensureActive()
        if (newPipeFallback) {
            try {
                val r = newPipe(videoId, quality, metered)
                com.ravi.musicspot.core.PlaybackLog.log("  NewPipe: extract/decipher ok (${r.audio.codec}), availability wait ${availabilityWaitMs} ms")
                waitUntil(now() + availabilityWaitMs)
                var v = probe(r.url, r.headers, "NewPipe")
                if (v == Probe.REFUSED) { sleep(availabilityWaitMs); v = probe(r.url, r.headers, "NewPipe") }
                if (v == Probe.REFUSED) refused++
                if (v == Probe.OK) { com.ravi.musicspot.core.PlaybackLog.log("  NewPipe: OK"); synchronized(minted) { minted[r.url] = videoId to PlayerClient.forStreamUrl(r.url) }; return r }
                errors += "NewPipe: stream ${v.name.lowercase()}"
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                com.ravi.musicspot.core.PlaybackLog.log("  NewPipe: decipher/extract failed: ${e.javaClass.simpleName}: ${e.message}")
                errors += "NewPipe: ${e.message}"
            }
        }
        com.ravi.musicspot.core.PlaybackLog.step("YouTube", when { tried == 0 -> "all clients stood down"; refused > 0 -> "all clients refused (403)"; else -> "no playable stream" })
        com.ravi.musicspot.core.PlaybackLog.log("YouTube exhausted: ${errors.joinToString("; ").take(400)}")
        throw YtExhaustedException("YouTube: no playable stream (${errors.joinToString("; ").take(400)})")
    }

    /**
     * Playback got a 403/404/410 from a URL this resolver handed out: stand its client down for that track and stop
     * leading with it, so the next resolve walks on to another client.
     */
    fun onPlaybackRefused(url: String) {
        val known = synchronized(minted) { minted.remove(url) }
        if (known == null && url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") != true) return
        val client = known?.second ?: PlayerClient.forStreamUrl(url)
        known?.first?.let { standDown(it, client) }
        if (preferred == client) preferred = null
    }

    private suspend fun waitUntil(at: Long) {
        val ms = (at - now()).coerceIn(0, MAX_AVAILABILITY_WAIT_MS)
        if (ms > 0) sleep(ms)
    }

    /** Plain URL or solved signature cipher, then the `n` transform. Null when it cannot be unlocked. */
    private suspend fun unlock(videoId: String, a: YtAudio): String? {
        val base = a.url ?: run {
            val p = (a.cipher ?: return null).split('&').mapNotNull { part ->
                val i = part.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                URLDecoder.decode(part.substring(0, i), "UTF-8") to URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }.toMap()
            val u = p["url"] ?: return null
            val s = p["s"] ?: return null
            val solved = js.decipher(videoId, s) ?: run { com.ravi.musicspot.core.PlaybackLog.log("  NewPipe decipher (signature): failed"); return null }
            "$u${if ('?' in u) '&' else '?'}${p["sp"] ?: "signature"}=$solved"
        }
        return if (base.toHttpUrlOrNull()?.queryParameter("n").isNullOrBlank()) base else try { js.deobfuscateN(videoId, base) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            com.ravi.musicspot.core.PlaybackLog.log("  NewPipe decipher (n): failed ${e.javaClass.simpleName}: ${e.message}"); throw e
        }
    }

    /** 16 KB ranged GET with the player's exact headers: OK only for 2xx + an audio content type + bytes that actually arrive. */
    private suspend fun probe(url: String, headers: Map<String, String>, who: String = "probe"): Probe {
        // v2.0.5: the same request shape ExoPlayer's chunked googlevideo fetch sends (ChunkedDataSource): ranged,
        // the minting client's headers, and Accept-Encoding: identity (what OkHttpDataSource sends).
        val rb = Request.Builder().url(url).header("Range", "bytes=0-${PROBE_BYTES - 1}").header("Accept-Encoding", "identity")
        headers.forEach { (k, v) -> rb.header(k, v) }
        return try {
            prober.newCall(rb.build()).await().use { r ->
                com.ravi.musicspot.core.PlaybackLog.log("  $who: probe HTTP ${r.code} ${r.header("Content-Type") ?: "-"} ${url}")
                when {
                    r.code in REFUSAL_CODES -> Probe.REFUSED
                    r.code !in 200..299 -> Probe.UNREACHABLE
                    r.header("Content-Type")?.startsWith("audio/") != true -> Probe.REFUSED
                    runInterruptible(Dispatchers.IO) { r.body?.source()?.request(1) } != true -> Probe.UNREACHABLE
                    else -> Probe.OK
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            com.ravi.musicspot.core.PlaybackLog.log("  $who: probe failed ${e.javaClass.simpleName}: ${e.message}")
            Probe.UNREACHABLE
        }
    }

    private suspend fun newPipe(videoId: String, quality: Quality, metered: Boolean): YtResolved = runInterruptible(Dispatchers.IO) {
        NewPipeBridge.ensureInit(tube.region)
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val audios = info.audioStreams.map {
            YtAudio(it.itag, it.format?.mimeType ?: "audio/unknown", it.averageBitrate * 1000, it.content)
        }
        val best = YtFormats.rank(audios, quality, metered).firstOrNull { it.url != null } ?: throw java.io.IOException("no audio streams")
        YtResolved(best.url!!, PlayerClient.forStreamUrl(best.url).mediaHeaders(), best, "NewPipe")
    }

    /** Duration in ms from a player response, when present. */
    fun lengthMs(player: JsonObject): Long? = // v2.0.16: safe cast on videoDetails
        (player["videoDetails"] as? JsonObject)?.get("lengthSeconds")?.prim?.contentOrNull?.toLongOrNull()?.times(1000)

    companion object {
        /** OpenTune 1.0.4 StreamResolver.CLIENTS order. */
        val CLIENTS = listOf(
            PlayerClient.ANDROID_MUSIC, PlayerClient.TVHTML5, PlayerClient.ANDROID_VR, PlayerClient.ANDROID_VR_LEGACY,
            PlayerClient.IOS, PlayerClient.IOS_RECENT, PlayerClient.ANDROID,
        )
        /** v2.0.5: stand-downs expire after 2 minutes (was 10). */
        const val STAND_DOWN_MS = 2 * 60_000L
        const val PROBE_BYTES = 16 * 1024L
        const val MAX_AVAILABILITY_WAIT_MS = 8_000L
        private const val PROBE_TIMEOUT_S = 8L
        private val REFUSAL_CODES = setOf(403, 404, 410)

        /**
         * Field names an availability time may arrive under (on streamingData or on an audio format). Absolute
         * epoch values in ms (or µs/s, normalised by magnitude); "...InSeconds"/"...InMs" are relative to the fetch.
         * Best effort: YouTube does not document one, so the [availabilityWaitMs] default covers the usual case.
         */
        private val ABSOLUTE_KEYS = listOf("availableAtMs", "availabilityStartTimeMs", "availableAt", "availabilityStartTime", "readyAtMs")
        private val RELATIVE_S_KEYS = listOf("availableInSeconds", "readyInSeconds", "availabilityDelaySeconds")
        private val RELATIVE_MS_KEYS = listOf("availableInMs", "readyInMs", "availabilityDelayMs")

        fun availableAtMs(player: JsonObject, fetchedAt: Long): Long? {
            val sd = player["streamingData"] as? JsonObject ?: return null // v2.0.16: safe casts
            val objs = listOf(sd) + ((sd["adaptiveFormats"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.filter { it["mimeType"]?.prim?.contentOrNull?.startsWith("audio/") == true }.orEmpty())
            fun num(o: JsonObject, k: String) = o[k]?.prim?.contentOrNull?.toDoubleOrNull()
            return objs.mapNotNull { o ->
                ABSOLUTE_KEYS.firstNotNullOfOrNull { num(o, it) }?.let { v ->
                    when { v > 1e14 -> (v / 1000).toLong(); v > 1e11 -> v.toLong(); else -> (v * 1000).toLong() }
                } ?: RELATIVE_S_KEYS.firstNotNullOfOrNull { num(o, it) }?.let { fetchedAt + (it * 1000).toLong() }
                ?: RELATIVE_MS_KEYS.firstNotNullOfOrNull { num(o, it) }?.let { fetchedAt + it.toLong() }
            }.maxOrNull()
        }

        /** Align `cver` with the client that asked; googlevideo answers a mismatched version with 403. */
        fun patchClientVersion(url: String, clientVersion: String): String =
            if ("cver=" in url) url.replace(Regex("cver=[^&]+"), "cver=$clientVersion") else url
    }
}

// v2.0.16: safe primitive access; `.jsonPrimitive` throws IllegalArgumentException when YouTube sends an object/array.
private val JsonElement.prim: JsonPrimitive? get() = this as? JsonPrimitive
