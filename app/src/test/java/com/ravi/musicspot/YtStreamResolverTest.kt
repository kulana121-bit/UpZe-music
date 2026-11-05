package com.ravi.musicspot

import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.MusicSource
import com.ravi.musicspot.source.Resolved
import com.ravi.musicspot.source.SourceManager
import com.ravi.musicspot.source.SourceUnavailableException
import com.ravi.musicspot.yt.Innertube
import com.ravi.musicspot.yt.PlayerClient
import com.ravi.musicspot.yt.YtJsPlayer
import com.ravi.musicspot.yt.YtStreamResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** v2.0.3 YouTube 403 fix: the client walk, the availability wait, playback refusals, and the JioSaavn fallback. */
class YtStreamResolverTest {
    private lateinit var server: MockWebServer
    private val playerCalls = Collections.synchronizedList(mutableListOf<String>())
    private val media = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val playerAt = ConcurrentHashMap<String, Long>()
    /** Clients whose media URL always answers 403. */
    private val refusing = Collections.synchronizedSet(mutableSetOf<String>())
    /** A media request earlier than this many ms after its player response answers 403 (fresh-URL behaviour). */
    @Volatile private var liveAfterMs = 0L
    @Volatile private var extraFormatFields = ""

    private object FakeJs : YtJsPlayer {
        override suspend fun signatureTimestamp(videoId: String) = 20000
        override suspend fun decipher(videoId: String, signature: String) = "solved"
        override suspend fun deobfuscateN(videoId: String, url: String) = url.replace("n=raw", "n=fixed")
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.contains("/player")) {
                    val client = Regex("\"clientName\":\"([A-Z0-9_]+)\"").find(request.body.readUtf8())!!.groupValues[1]
                    playerCalls += client
                    playerAt[client] = System.currentTimeMillis()
                    val url = server.url("/media/$client").toString() + "?c=$client&cver=STALE&n=raw"
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
                           {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","bitrate":160000,"url":"$url"$extraFormatFields}]}}""")
                }
                if (path.startsWith("/media/")) {
                    media += request
                    val client = path.removePrefix("/media/").substringBefore('?')
                    val age = System.currentTimeMillis() - (playerAt[client] ?: 0L)
                    if (client in refusing || age < liveAfterMs) return MockResponse().setResponseCode(403).setBody("Forbidden")
                    return MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/webm").setBody(Buffer().write(ByteArray(2048)))
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun tube() = Innertube(OkHttpClient(), ytBase = server.url("/yt").toString().trimEnd('/'),
        musicBase = server.url("/music").toString().trimEnd('/'), visitorUrl = null)

    @Volatile private var clock: Long? = null

    private fun resolver(waitMs: Long, slept: MutableList<Long>? = null) = YtStreamResolver(
        tube(), FakeJs, OkHttpClient(), availabilityWaitMs = waitMs,
        sleep = { slept?.add(it); kotlinx.coroutines.delay(it) }, newPipeFallback = false,
        now = { clock ?: System.currentTimeMillis() },
    )

    // ---- v2.0.5 stand-down policy -----------------------------------------------------------------------------
    @Test fun standDownExpiresAfterTwoMinutes() = runBlocking {
        refusing += "ANDROID_MUSIC"
        val res = resolver(waitMs = 5)
        res.resolve("vidX", Quality.HIGH, false)
        assertTrue(res.isStoodDown("vidX", PlayerClient.ANDROID_MUSIC))
        clock = System.currentTimeMillis() + YtStreamResolver.STAND_DOWN_MS - 5_000
        assertTrue("still stood down just before 2 min", res.isStoodDown("vidX", PlayerClient.ANDROID_MUSIC))
        clock = System.currentTimeMillis() + YtStreamResolver.STAND_DOWN_MS + 1_000
        assertFalse("stand-down expired after 2 min", res.isStoodDown("vidX", PlayerClient.ANDROID_MUSIC))
        assertEquals(2 * 60_000L, YtStreamResolver.STAND_DOWN_MS)
    }

    @Test fun playbackRefusalStandsDownOnlyTheMintingClient() = runBlocking {
        val res = resolver(waitMs = 5)
        val r = res.resolve("vidY", Quality.HIGH, false)
        assertEquals("ANDROID_MUSIC", r.client)
        res.onPlaybackRefused(r.url)
        assertTrue(res.isStoodDown("vidY", PlayerClient.ANDROID_MUSIC))
        YtStreamResolver.CLIENTS.filter { it != PlayerClient.ANDROID_MUSIC }.forEach { assertFalse(it.clientName, res.isStoodDown("vidY", it)) }
        assertEquals("TVHTML5", res.resolve("vidY", Quality.HIGH, false).client)
    }

    @Test fun whenEveryClientIsStoodDownTheyAreClearedOnceAndTheWalkRunsAgain() = runBlocking {
        refusing += YtStreamResolver.CLIENTS.map { it.clientName }
        val res = resolver(waitMs = 5)
        runCatching { res.resolve("vidZ", Quality.HIGH, false) }.also { assertTrue(it.isFailure) }
        assertTrue(YtStreamResolver.CLIENTS.all { res.isStoodDown("vidZ", it) })
        // YouTube recovers: v2.0.4 failed instantly here ("stood down for this track") for 10 minutes.
        refusing.clear(); playerCalls.clear()
        val r = res.resolve("vidZ", Quality.HIGH, false)
        assertEquals("ANDROID_MUSIC", r.client)
        assertEquals(listOf("ANDROID_MUSIC"), playerCalls)
    }

    @Test fun stillFailingAfterTheClearedWalkFailsInsteadOfLooping() = runBlocking {
        refusing += YtStreamResolver.CLIENTS.map { it.clientName }
        val res = resolver(waitMs = 5)
        runCatching { res.resolve("vidW", Quality.HIGH, false) }
        playerCalls.clear()
        val second = runCatching { res.resolve("vidW", Quality.HIGH, false) }
        assertTrue(second.isFailure)
        // Exactly one extra walk over every client, not an endless loop.
        assertEquals(YtStreamResolver.CLIENTS.size, playerCalls.size)
    }

    @Test fun formatRejectionIsExplained() {
        val j = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"https://x/hls.m3u8","adaptiveFormats":[{"itag":136,"mimeType":"video/mp4"}]}}"""
        ) as kotlinx.serialization.json.JsonObject
        val why = com.ravi.musicspot.yt.YtFormats.describe(j)
        assertTrue(why, why.contains("audio 0") && why.contains("HLS manifest only") && why.contains("video/mp4"))
    }

    private fun mediaFor(c: String) = media.filter { it.path!!.startsWith("/media/$c?") }

    @Test fun walkSkipsRefusedClientAndUsesTheMintingClientsHeaders() = runBlocking {
        refusing += "ANDROID_MUSIC"
        val slept = mutableListOf<Long>()
        val res = resolver(waitMs = 40, slept = slept)
        val r = res.resolve("vid1", Quality.HIGH, false)

        assertEquals("TVHTML5", r.client)
        assertEquals(listOf("ANDROID_MUSIC", "TVHTML5"), playerCalls)
        // A 403 on a fresh URL is probed again after the wait before the client is dropped.
        assertEquals(2, mediaFor("ANDROID_MUSIC").size)
        assertTrue(res.isStoodDown("vid1", PlayerClient.ANDROID_MUSIC))
        val am = mediaFor("ANDROID_MUSIC").first()
        assertEquals(PlayerClient.ANDROID_MUSIC.userAgent, am.getHeader("User-Agent"))
        assertNull("app clients send no Origin", am.getHeader("Origin"))
        val tv = mediaFor("TVHTML5").single()
        assertEquals(PlayerClient.TVHTML5.userAgent, tv.getHeader("User-Agent"))
        assertEquals("https://www.youtube.com", tv.getHeader("Origin"))
        assertEquals("https://www.youtube.com/", tv.getHeader("Referer"))
        assertEquals("bytes=0-16383", tv.getHeader("Range"))
        // n-param deobfuscated and cver patched to the asking client's version.
        assertTrue(r.url, r.url.contains("n=fixed") && r.url.contains("cver=${PlayerClient.TVHTML5.clientVersion}"))
        assertEquals(r.headers, PlayerClient.TVHTML5.mediaHeaders())
        assertTrue("waited before probing", slept.isNotEmpty())

        // The client that worked leads the next walk.
        playerCalls.clear()
        res.resolve("vid2", Quality.HIGH, false)
        assertEquals(listOf("TVHTML5"), playerCalls)
    }

    @Test fun waitsForAvailabilityBeforeTheFirstByteRequest() = runBlocking {
        liveAfterMs = 250
        val r = resolver(waitMs = 300).resolve("vid1", Quality.HIGH, false)
        assertEquals("ANDROID_MUSIC", r.client)
        assertEquals("no premature (false 403) probe", 1, media.size)

        // An availability time in the response wins over the default wait.
        media.clear(); playerAt.clear(); liveAfterMs = 400; extraFormatFields = ",\"availableInMs\":\"500\""
        val r2 = resolver(waitMs = 0).resolve("vid2", Quality.HIGH, false)
        assertEquals("ANDROID_MUSIC", r2.client)
        assertEquals(1, media.size)
    }

    @Test fun playbackRefusalStandsTheClientDown() = runBlocking {
        val res = resolver(waitMs = 0)
        val first = res.resolve("vid1", Quality.HIGH, false)
        assertEquals("ANDROID_MUSIC", first.client)
        res.onPlaybackRefused(first.url)
        assertTrue(res.isStoodDown("vid1", PlayerClient.ANDROID_MUSIC))
        assertNull(res.preferred)
        val second = res.resolve("vid1", Quality.HIGH, false)
        assertEquals("TVHTML5", second.client)
        assertFalse(res.isStoodDown("vid2", PlayerClient.ANDROID_MUSIC))
    }
}

private class Fake(override val kind: SourceKind, private val results: List<Track> = emptyList(), private val fail: Boolean = false) : MusicSource {
    val resolved = mutableListOf<String>()
    override fun available() = true
    override suspend fun search(query: String, limit: Int) = results
    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        resolved += track.id
        if (fail) throw SourceUnavailableException("${kind.label} down")
        return Resolved("https://example.invalid/${track.id}", StreamInfo(kind), cacheKey = track.id)
    }
    override suspend fun probe() = "ok"
}

class JioSaavnFallbackTest {
    private fun track(id: String) = Track(id, "Tum Hi Ho", "Arijit Singh", durationMs = 262_000)
    private fun mgr(vararg s: Fake) = SourceManager(s.associateBy { it.kind }, { listOf(SourceKind.YOUTUBE, SourceKind.AUDIUS, SourceKind.JIOSAAVN) },
        { s.map { it.kind }.toSet() }, { false }, { null })

    @Test fun youTubeExhaustedFallsBackToJioSaavnRegardlessOfPriority() = runTest {
        val yt = Fake(SourceKind.YOUTUBE, fail = true)
        val js = Fake(SourceKind.JIOSAAVN, results = listOf(track("js:m")))
        val failed = mutableListOf<String>()
        val r = mgr(yt, js).resolve(track("yt:a"), Quality.AUTO, false) { c, _ -> failed += c.id }
        assertEquals(listOf("yt:a"), yt.resolved)
        assertEquals(listOf("yt:a"), failed)
        assertEquals("js:m", r.cacheKey)
        assertEquals(SourceKind.YOUTUBE, r.fallbackFrom)
    }

    @Test fun excludedYouTubeIsNotAskedAtAll() = runTest {
        val yt = Fake(SourceKind.YOUTUBE)
        val js = Fake(SourceKind.JIOSAAVN, results = listOf(track("js:m")))
        val r = mgr(yt, js).resolve(track("yt:a"), Quality.HIGH, false, exclude = setOf(SourceKind.YOUTUBE))
        assertTrue(yt.resolved.isEmpty())
        assertEquals("js:m", r.cacheKey)
        assertEquals(SourceKind.YOUTUBE, r.fallbackFrom)
    }

    @Test fun healthyYouTubeHasNoFallbackFlag() = runTest {
        val js = Fake(SourceKind.JIOSAAVN, results = listOf(track("js:m")))
        val r = mgr(Fake(SourceKind.YOUTUBE), js).resolve(track("yt:a"), Quality.HIGH, false)
        assertEquals("yt:a", r.cacheKey); assertNull(r.fallbackFrom); assertTrue(js.resolved.isEmpty())
    }
}
