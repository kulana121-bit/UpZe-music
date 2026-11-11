package com.ravi.musicspot

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.ravi.musicspot.data.CachedSongs
import com.ravi.musicspot.data.HistoryEntry
import com.ravi.musicspot.data.LibraryData
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.PlayerConnection
import com.ravi.musicspot.ui.Navigator
import com.ravi.musicspot.ui.player.AuraPlayerScreen
import com.ravi.musicspot.ui.screens.CachedSongsScreen
import com.ravi.musicspot.ui.screens.HomeScreen
import com.ravi.musicspot.ui.screens.LibraryScreen
import com.ravi.musicspot.ui.screens.LocalNav
import com.ravi.musicspot.ui.screens.SearchScreen
import com.ravi.musicspot.ui.theme.MusicSpotTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 1.0.10 UI smoke tests: the five main screens (Home, Library, Search, Player, Cached songs) compose without
 * crashing, each with an empty and a populated state. State comes from local stores only (no network: YouTube and
 * JioSaavn are switched off so Home never fetches a feed). No screenshots; each test checks a few texts are on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, qualifiers = "w411dp-h1600dp")
class UiSmokeTest {
    @get:Rule val compose = createComposeRule()

    private val song = Track(id = "yt:smoke00001", title = "Kesariya Smoke", artist = "Arijit Singh", album = "Brahmastra", durationMs = 268_000)
    private val song2 = Track(id = "yt:smoke00002", title = "Deva Deva Smoke", artist = "Arijit Singh", durationMs = 279_000)

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, com.ravi.musicspot.playback.PlaybackService::class.java))
        com.ravi.musicspot.playback.PlaybackService.cacheEnabled = false
        Graph.init(app)
        val deadline = System.currentTimeMillis() + 20_000
        while (!Graph.ready.value && System.currentTimeMillis() < deadline) { idleMain(); Thread.sleep(10) }
        assertTrue("Graph.ready never completed", Graph.ready.value)
        // Deterministic, offline starting point for every test.
        Graph.settings.update {
            it.copy(onboarded = true, setupDone = true, recentSearches = emptyList(),
                enabledSources = it.enabledSources - SourceKind.YOUTUBE - SourceKind.JIOSAAVN)
        }
        Graph.library.replace(LibraryData())
        Graph.cached.publishForTest(emptyList())
        Graph.player.publishForTest(PlayerConnection.State())
    }

    // ---- harness -----------------------------------------------------------------------------------------------

    private fun idleMain() {
        val main = shadowOf(Looper.getMainLooper())
        main.idle(); main.idleFor(Duration.ofMillis(16)); main.idle()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false // infinite UI loops would otherwise never let the clock go idle
        compose.setContent {
            MusicSpotTheme(artUrl = null, dynamic = false) {
                CompositionLocalProvider(LocalNav provides Navigator()) { content() }
            }
        }
        settle()
    }

    private fun settle(frames: Int = 20) = repeat(frames) { compose.mainClock.advanceTimeBy(100); idleMain() }

    private fun has(t: String) = compose.onAllNodes(hasText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Waits (real time, for IO/Default work behind the screen) until [t] is on screen. */
    private fun waitFor(t: String, ms: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (has(t)) return true; settle(2); Thread.sleep(20) }
        return has(t)
    }

    private fun populateLibrary() {
        Graph.library.replace(LibraryData(liked = listOf(song), history = listOf(HistoryEntry(song, 2_000L), HistoryEntry(song2, 1_000L))))
        Graph.library.createPlaylist("Smoke Playlist", listOf(song, song2))
    }

    // ---- Home --------------------------------------------------------------------------------------------------

    @Test fun homeEmpty() {
        show { HomeScreen() }
        assertTrue("empty spotlight", waitFor("Find something to play"))
        assertFalse(has("CONTINUE LISTENING"))
    }

    @Test fun homePopulated() {
        populateLibrary()
        show { HomeScreen() }
        assertTrue("recent songs drive the spotlight", waitFor("CONTINUE LISTENING")) // the spotlight tag is upper-cased
        assertFalse(has("Find something to play"))
        assertTrue(waitFor("Kesariya Smoke"))
    }

    // ---- Library -----------------------------------------------------------------------------------------------

    @Test fun libraryEmpty() {
        show { LibraryScreen() }
        assertTrue(waitFor("Liked songs"))
        assertTrue(has("Cached songs"))
        assertTrue("empty counts", has("None yet"))
    }

    @Test fun libraryPopulated() {
        populateLibrary()
        show { LibraryScreen() }
        assertTrue(waitFor("Liked songs"))
        assertTrue("liked count", waitFor("1 song"))
        assertTrue("history count", has("2 songs"))
        assertTrue("own playlist listed", waitFor("Smoke Playlist"))
    }

    // ---- Search ------------------------------------------------------------------------------------------------

    @Test fun searchEmpty() {
        show { SearchScreen() }
        assertTrue(waitFor("Search everything"))
        assertTrue(has("Songs, artists, albums"))
    }

    @Test fun searchPopulated() {
        Graph.settings.update { it.copy(recentSearches = listOf("arijit smoke", "kesariya smoke")) }
        show { SearchScreen() }
        assertTrue(waitFor("Recent searches"))
        assertTrue(has("arijit smoke"))
        assertTrue(has("kesariya smoke"))
        assertFalse(has("Search everything"))
    }

    // ---- Player ------------------------------------------------------------------------------------------------

    @Composable private fun player() = AuraPlayerScreen(
        progress = { 1f }, onCollapse = {}, onDrag = {}, onDragEnd = {}, coverVisible = true,
        onCoverBounds = {}, onOpenEq = {}, onNavigate = {},
    )

    @Test fun playerEmpty() {
        show { player() }
        assertFalse("nothing playing: the player draws nothing", has("Kesariya Smoke"))
    }

    @Test fun playerPopulated() {
        Graph.player.publishForTest(PlayerConnection.State(queue = listOf(song, song2), index = 0, playing = false, durationMs = 268_000))
        show { player() }
        assertTrue(waitFor("Kesariya Smoke"))
        assertTrue(has("Arijit Singh"))
    }

    // ---- Cached songs ------------------------------------------------------------------------------------------

    @Test fun cachedSongsEmpty() {
        show { CachedSongsScreen() }
        assertTrue(waitFor("No cached songs yet"))
    }

    @Test fun cachedSongsPopulated() {
        show { CachedSongsScreen() }
        // The screen refreshes from the (empty) real cache first; then the populated list is published.
        assertTrue(waitFor("No cached songs yet"))
        Graph.cached.publishForTest(listOf(song, song2).map {
            CachedSongs.Entry(it, "${it.id}@smoke", "https://example.invalid/${it.id}", StreamInfo(SourceKind.YOUTUBE, "Opus", 160, null, it.id), 1_000L)
        })
        assertTrue(waitFor("Kesariya Smoke"))
        assertTrue(has("Deva Deva Smoke"))
        assertFalse(has("No cached songs yet"))
    }
}
