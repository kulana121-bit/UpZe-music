package com.ravi.musicspot

import com.ravi.musicspot.lyrics.LrcLib
import com.ravi.musicspot.lyrics.Lyrics
import com.ravi.musicspot.lyrics.LyricsQuery
import com.ravi.musicspot.source.JioSaavnSource
import com.ravi.musicspot.yt.Innertube
import com.ravi.musicspot.yt.YtMusic
import com.ravi.musicspot.yt.YtStreamResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Live network smoke tests. Skipped unless MUSICSPOT_LIVE=1 is set in the environment
 * (e.g. `MUSICSPOT_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*LiveSmokeTest*'`).
 */
class LiveSmokeTest {
    /** Google answers this sandbox's IP with its "Sorry…" automated-traffic page (HTTP 403); that is not an app result. */
    private fun ytBlocked(e: Throwable?): Boolean = generateSequence(e) { it.cause }.any { it.message?.contains("HTTP 403 from https://www.youtube.com") == true || it.message?.contains("HTTP 403 from https://music.youtube.com") == true }

    @Before fun gate() = assumeTrue("set MUSICSPOT_LIVE=1 to run live smoke tests", System.getenv("MUSICSPOT_LIVE") == "1")

    @Test fun innertubeSearch() = runBlocking {
        val tube = Innertube()
        val yt = YtMusic(tube, YtStreamResolver(tube)) { true }
        val r = withTimeout(30_000) { yt.search("blinding lights the weeknd", 5) }
        println("LIVE Innertube: ${r.size} songs; first=${r.firstOrNull()?.let { "${it.id} ${it.title} — ${it.artist}" }}")
        assertTrue(r.isNotEmpty())
    }

    @Test fun jioSaavnSearch() = runBlocking {
        val r = withTimeout(30_000) { JioSaavnSource { true }.search("tum hi ho", 5) }
        println("LIVE JioSaavn: ${r.size} songs; first=${r.firstOrNull()?.let { "${it.id} ${it.title} — ${it.artist}" }}")
        assertTrue(r.isNotEmpty())
    }

    @Test fun lrclibLookup() = runBlocking {
        val l = withTimeout(30_000) { LrcLib.lookup(LyricsQuery("Blinding Lights", "The Weeknd", "After Hours", 200_000)) }
        println("LIVE LRCLIB: ${when (l) { is Lyrics.Synced -> "synced, ${l.lines.size} lines, first='${l.lines.first().text}'"; is Lyrics.Plain -> "plain"; null -> "none" }}")
        assertTrue(l != null)
    }

    /**
     * v2.0.2: the full play path minus the decoder. SourceManager (router + failover) resolves a real track on each source,
     * then the first range is fetched with exactly the headers ExoPlayer's OkHttpDataSource sends after the fix
     * (default browser UA overridden by the resolver's per-request headers). Must be HTTP 200/206 with an audio type.
     */
    private fun fetchFirstRange(label: String, r: com.ravi.musicspot.source.Resolved, extraUa: String? = null): Pair<Int, String?> {
        val headers = mapOf("User-Agent" to com.ravi.musicspot.core.Http.BROWSER_UA) + r.headers
        val req = okhttp3.Request.Builder().url(r.uri).apply { headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=0-65535"); extraUa?.let { addHeader("User-Agent", it) } }.build()
        com.ravi.musicspot.core.Http.client.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val type = resp.header("Content-Type")
            println("LIVE $label stream${if (extraUa != null) " (with duplicate UA)" else ""}: HTTP ${resp.code} type=$type bytes=${bytes.size} head=${bytes.take(8).joinToString("") { "%02x".format(it) }} host=${r.uri.toHttpUrlHost()}")
            return resp.code to type
        }
    }
    private fun String.toHttpUrlHost() = runCatching { java.net.URI(this).host }.getOrNull()

    private fun manager(yt: YtMusic, js: JioSaavnSource) = com.ravi.musicspot.source.SourceManager(
        mapOf(com.ravi.musicspot.model.SourceKind.YOUTUBE to yt, com.ravi.musicspot.model.SourceKind.JIOSAAVN to js),
        priority = { listOf(com.ravi.musicspot.model.SourceKind.YOUTUBE, com.ravi.musicspot.model.SourceKind.JIOSAAVN) },
        enabled = { setOf(com.ravi.musicspot.model.SourceKind.YOUTUBE, com.ravi.musicspot.model.SourceKind.JIOSAAVN) },
        premium = { false }, downloaded = { null },
    )

    @Test fun ytResolverWalk() = runBlocking {
        val tube = Innertube(); val res = YtStreamResolver(tube)
        val t0 = System.currentTimeMillis()
        val r = runCatching { withTimeout(120_000) { res.resolve("BddP6PYo2gs", com.ravi.musicspot.model.Quality.HIGH, false) } }
        println("LIVE YT resolver walk (${System.currentTimeMillis() - t0} ms): ${r.getOrNull()?.let { "${it.client} itag ${it.audio.itag} ${it.audio.mime}" } ?: r.exceptionOrNull()?.message}")
        for (c in listOf(com.ravi.musicspot.yt.PlayerClient.ANDROID_VR, com.ravi.musicspot.yt.PlayerClient.IOS, com.ravi.musicspot.yt.PlayerClient.TVHTML5, com.ravi.musicspot.yt.PlayerClient.ANDROID_MUSIC)) {
            val s = System.currentTimeMillis()
            val p = runCatching { withTimeout(30_000) { tube.player("BddP6PYo2gs", c, authenticated = false) } }
            println("  client ${c.clientName}: ${System.currentTimeMillis() - s} ms → ${p.getOrNull()?.let { j -> "status=" + (j["playabilityStatus"] as? kotlinx.serialization.json.JsonObject)?.get("status") + " formats=" + com.ravi.musicspot.yt.YtFormats.parse(j).count { it.url != null } } ?: p.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message }}")
        }
        val charts = runCatching { withTimeout(30_000) { YtMusic(tube, res) { true }.charts("IN") } }
        println("  charts IN: ${charts.getOrNull()?.size ?: charts.exceptionOrNull()}")
    }

    @Test fun ytStreamReturnsAudio() = runBlocking {
        val tube = Innertube(); val yt = YtMusic(tube, YtStreamResolver(tube)) { true }; val js = JioSaavnSource { false }
        val t = withTimeout(30_000) { yt.search("tum hi ho arijit singh", 5) }.first()
        // Direct resolver call first, to tell "Google blocked this IP" apart from an app failure.
        val direct = runCatching { withTimeout(120_000) { YtStreamResolver(tube).resolve(t.nativeId, com.ravi.musicspot.model.Quality.HIGH, false) } }
        assumeTrue("YouTube blocks this sandbox's IP (403 'Sorry' page): ${direct.exceptionOrNull()?.message?.take(200)}", !(direct.isFailure && ytBlocked(direct.exceptionOrNull()) || direct.exceptionOrNull()?.message?.contains("HTTP 403") == true))
        val r = withTimeout(60_000) { manager(yt, js).resolve(t, com.ravi.musicspot.model.Quality.HIGH, false) { c, e -> println("  failover from ${c.id}: ${e.message}") } }
        println("LIVE YT resolve ${t.id} '${t.title}' → ${r.info.codec} ${r.info.bitrateKbps} kbps via ${r.info.source}; headers=${r.headers.keys}")
        val (code, type) = fetchFirstRange("YT", r)
        assertTrue("YT stream HTTP $code", code == 200 || code == 206)
        assertTrue("YT content-type $type", type?.startsWith("audio/") == true || type == "application/octet-stream")
        runCatching { fetchFirstRange("YT", r, extraUa = com.ravi.musicspot.core.Http.BROWSER_UA) }.onFailure { println("  duplicate-UA probe failed: ${it.message}") }
        Unit
    }

    @Test fun jioSaavnStreamReturnsAudio() = runBlocking {
        val tube = Innertube(); val yt = YtMusic(tube, YtStreamResolver(tube)) { false }; val js = JioSaavnSource { true }
        val t = withTimeout(30_000) { js.search("tum hi ho", 5) }.first()
        val r = withTimeout(60_000) { manager(yt, js).resolve(t, com.ravi.musicspot.model.Quality.HIGH, false) { c, e -> println("  failover from ${c.id}: ${e.message}") } }
        println("LIVE JioSaavn resolve ${t.id} '${t.title}' → ${r.info.codec} ${r.info.bitrateKbps} kbps; headers=${r.headers.keys}")
        val (code, type) = fetchFirstRange("JioSaavn", r)
        assertTrue("JioSaavn stream HTTP $code", code == 200 || code == 206)
        assertTrue("JioSaavn content-type $type", type?.startsWith("audio/") == true || type?.startsWith("video/mp4") == true || type == "application/octet-stream")
        Unit
    }

    @Test fun homeFeedsLoad() = runBlocking {
        val tube = Innertube(); val yt = YtMusic(tube, YtStreamResolver(tube)) { true }
        val home = runCatching { withTimeout(30_000) { yt.home() } }
        assumeTrue("YouTube blocks this sandbox's IP: ${home.exceptionOrNull()?.message}", !ytBlocked(home.exceptionOrNull()))
        val (shelves, _) = home.getOrThrow()
        println("LIVE YT home (guest): ${shelves.size} shelves: ${shelves.take(8).joinToString { "${it.title}(${it.tracks.size}t/${it.collections.size}c)" }}")
        assertTrue(shelves.isNotEmpty())
        val moods = runCatching { withTimeout(30_000) { yt.moods() } }.getOrNull()
        println("LIVE YT moods: ${moods?.size} sections, first=${moods?.firstOrNull()?.moods?.take(6)?.map { it.title }}")
        val charts = runCatching { withTimeout(30_000) { yt.charts("IN") } }.getOrNull()
        println("LIVE YT charts IN: ${charts?.map { "${it.title}(${it.tracks.size}t/${it.collections.size}c)" }}")
        val js = runCatching { withTimeout(30_000) { JioSaavnSource { true }.trending() } }
        println("LIVE JioSaavn trending: ${js.getOrNull()?.let { "${it.tracks.size}t/${it.collections.size}c" } ?: js.exceptionOrNull()?.message}")
    }
}

/**
 * v2.0.5 live checks (MUSICSPOT_LIVE=1): JioSaavn latency for the exact queries from Ravi's failing log, the
 * autocomplete fallback endpoint, JioSaavn recommendations, and NewPipe's SoundCloud client-id discovery on a JVM.
 */
class LiveV205Test {
    @Before fun gate() = assumeTrue("set MUSICSPOT_LIVE=1 to run live smoke tests", System.getenv("MUSICSPOT_LIVE") == "1")

    @Test fun jioSaavnLatencyForKoyal() = runBlocking {
        val js = JioSaavnSource { true }
        for (q in listOf("Koyal Duryodhan Rana", "Koyal")) repeat(2) { n ->
            val t0 = System.nanoTime()
            val r = withTimeout(20_000) { js.search(q, 10) }
            println("LIVE JioSaavn search \"$q\" #${n + 1}: ${r.size} results in ${(System.nanoTime() - t0) / 1_000_000} ms; " +
                "Koyal by Duryodhan Rana present=${r.any { it.title == "Koyal" && it.artist.contains("Duryodhan") }}")
            assertTrue(r.isNotEmpty())
        }
        val t1 = System.nanoTime()
        val ac = withTimeout(20_000) { js.autocomplete("Koyal Duryodhan Rana", 10) }
        println("LIVE JioSaavn autocomplete.get: ${ac.size} songs in ${(System.nanoTime() - t1) / 1_000_000} ms; ${ac.joinToString { "${it.title} — ${it.artist}" }}")
        assertTrue(ac.isNotEmpty())
        val seed = com.ravi.musicspot.model.Track("yt:PvFlZJk01hU", "Koyal", "Duryodhan Rana", durationMs = 136_000)
        val t2 = System.nanoTime()
        val reco = withTimeout(30_000) { js.recommendationsFor(seed) }
        println("LIVE JioSaavn reco.getreco for Koyal: ${reco.size} songs in ${(System.nanoTime() - t2) / 1_000_000} ms; first=${reco.take(3).joinToString { it.title }}")
        assertTrue(reco.isNotEmpty())
    }

    @Test fun soundCloudClientIdOnJvm() = runBlocking {
        val r = runCatching { withTimeout(30_000) { com.ravi.musicspot.source.SoundCloudSource { true }.search("lofi", 3) } }
        println("LIVE SoundCloud (NewPipe) search: ${r.map { "${it.size} results" }.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }}")
    }
}
