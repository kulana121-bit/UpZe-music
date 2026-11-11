package com.ravi.musicspot

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.media3.session.MediaSessionService
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.PlaybackService
import com.ravi.musicspot.playback.toMediaItem
import com.ravi.musicspot.source.Resolved
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.Collections

/**
 * v2.0.2: regression test for the device crash
 * "Player is accessed on the wrong thread. Current thread: 'DefaultDispatcher-worker-5'" in PlaybackService.maybeAutoplay.
 *
 * The real PlaybackService/ExoPlayer is built on Robolectric's main looper (its application thread). The test plays a
 * track, which triggers onMediaItemTransition → maybeAutoplay (radio fetched on Dispatchers.IO) and the
 * ResolvingDataSource → resolver on ExoPlayer's loader thread, and asserts that the radio is appended and that no
 * thread anywhere threw (ExoPlayer's verifyApplicationThread throws IllegalStateException on a wrong-thread access).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [29], application = MusicSpotApp::class)
class PlaybackThreadingTest {
    private val errors = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previous: Thread.UncaughtExceptionHandler? = null
    private val resolveThreads = Collections.synchronizedList(mutableListOf<String>())
    private val radioThreads = Collections.synchronizedList(mutableListOf<String>())

    private fun t(id: String) = Track("yt:$id", "Song $id", "Artist", durationMs = 2_000)

    private fun idle(ms: Long = 16) {
        val main = shadowOf(Looper.getMainLooper())
        main.idle(); main.idleFor(Duration.ofMillis(ms)); main.idle()
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for $what")
            idle(); Thread.sleep(10)
        }
    }

    @Before fun setUp() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> errors += e }
        val app = RuntimeEnvironment.getApplication()
        Graph.init(app)
        waitFor("Graph.ready") { Graph.ready.value }
        Graph.settings.update { it.copy(autoplay = true, onboarded = true) }
        PlaybackService.cacheEnabled = false
        val wav = sineWav(File(app.cacheDir, "tone.wav"))
        PlaybackService.radioProvider = { seed ->
            radioThreads += Thread.currentThread().name
            kotlinx.coroutines.delay(30)            // a real network call would suspend here (on IO)
            listOf(t("r1"), t("r2"), t("r3"), seed)
        }
        PlaybackService.resolver = { track, _ ->
            resolveThreads += Thread.currentThread().name
            Resolved(android.net.Uri.fromFile(wav).toString(), StreamInfo(SourceKind.YOUTUBE, "PCM", 1411, 44_100, track.id), mapOf("User-Agent" to "test"), "test:${track.id}")
        }
    }

    private var service: org.robolectric.android.controller.ServiceController<PlaybackService>? = null

    @After fun tearDown() {
        runCatching { service?.destroy() }; service = null; idle()
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    private fun assertNoThreadErrors() {
        val wrong = errors.filter { generateSequence(it) { c -> c.cause }.any { c -> c.message?.contains("wrong thread") == true } }
        if (wrong.isNotEmpty()) throw AssertionError("Wrong-thread player access", wrong.first())
        if (errors.isNotEmpty()) throw AssertionError("Uncaught exception on a background thread", errors.first())
    }

    @Test fun playPlusAutoplayNeverTouchesPlayerOffMainThread() {
        val sc = Robolectric.buildService(PlaybackService::class.java).create().also { service = it }
        idle(50)
        val player = PlaybackService.current!!.playerForTest
        assertEquals("player must live on the main looper", Looper.getMainLooper(), player.applicationLooper)
        // Sanity: the wrong-thread check is active, so the test would catch the v2.0.1 bug.
        var threw: Throwable? = null
        Thread { threw = runCatching { player.mediaItemCount }.exceptionOrNull() }.apply { start(); join() }
        assertTrue("ExoPlayer should reject off-thread access (got $threw)", threw is IllegalStateException)

        player.setMediaItems(listOf(t("seed")).map { it.toMediaItem() })
        player.prepare(); player.play()
        waitFor("autoplay radio appended") { player.mediaItemCount >= 4 }
        waitFor("stream resolved on the loader thread") { resolveThreads.isNotEmpty() }
        repeat(20) { idle(16) }
        assertEquals(listOf("yt:seed", "yt:r1", "yt:r2", "yt:r3"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
        assertTrue("radio fetched on a background thread: $radioThreads", radioThreads.none { it == "main" || it.startsWith("Test worker") })
        assertTrue("resolver must not run on main: $resolveThreads", resolveThreads.none { it == Thread.currentThread().name })
        assertNoThreadErrors()
        println("API 29 play+autoplay OK; radio on $radioThreads, resolve on $resolveThreads, state=${player.playbackState} error=${player.playerError?.errorCodeName}")
    }

    /** The same flow driven the way the UI does it: Graph.player (PlayerConnection → MediaController → MediaSession). */
    @Test fun playViaPlayerConnection() {
        val app = RuntimeEnvironment.getApplication()
        val sc = Robolectric.buildService(PlaybackService::class.java).create().also { service = it }
        idle(50)
        val cn = ComponentName(app, PlaybackService::class.java)
        val binder = sc.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE).setComponent(cn))
        shadowOf(app).setComponentNameAndServiceForBindService(cn, binder)
        Graph.player.play(listOf(t("seed")))
        val player = PlaybackService.current!!.playerForTest
        waitFor("controller connected and item set") { player.mediaItemCount >= 1 }
        waitFor("autoplay radio appended") { player.mediaItemCount >= 4 }
        waitFor("UI state sees the queue") { Graph.player.state.value.queue.size >= 4 }
        waitFor("stream resolved") { resolveThreads.isNotEmpty() }
        repeat(20) { idle(16) }
        assertEquals("yt:seed", Graph.player.state.value.current?.id)
        // UI commands from a background thread are marshalled to main instead of touching the controller there.
        Thread { Graph.player.next() }.apply { start(); join() }
        waitFor("next via background call") { player.currentMediaItem?.mediaId == "yt:r1" }
        // 1.0.7 smart queue: the played song stays in the queue above the playing one (no longer removed), exactly once.
        waitFor("played song kept above the playing one") { player.currentMediaItemIndex >= 1 && player.getMediaItemAt(0).mediaId == "yt:seed" && (0 until player.mediaItemCount).count { player.getMediaItemAt(it).mediaId == "yt:seed" } == 1 }
        assertNoThreadErrors()
        assertFalse(player.playWhenReady && player.playerError?.message?.contains("wrong thread") == true)
        println("API 29 PlayerConnection play OK: queue=${Graph.player.state.value.queue.map { it.id }}")
    }


    private fun sineWav(f: File): File {
        // 60 s of audio: every wait idles the main looper and advances Robolectric's virtual clock, so a 2 s tone could
        // play out (auto-advancing to r1, r2…) before the "current is seed" / next() assertions ran.
        val rate = 44_100; val n = rate * 60
        val data = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) data.putShort((Math.sin(2 * Math.PI * 440 * i / rate) * 8000).toInt().toShort())
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
        f.parentFile?.mkdirs(); f.writeBytes(h.array() + data.array()); return f
    }
}
