package com.ravi.musicspot

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState // v2.0.15
import androidx.compose.foundation.background // v2.0.16
import androidx.compose.foundation.layout.fillMaxSize // v2.0.16
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember // 1.0.1
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ravi.musicspot.core.CrashGuard
import com.ravi.musicspot.ui.AppRoot
import com.ravi.musicspot.ui.motion.trackTaps // 1.0.1

class MainActivity : ComponentActivity() {
    private var openPlayer by mutableStateOf(false)
    /** Pack 5 Part 2: a screen a widget asked for (EXTRA_OPEN_ROUTE), opened by AppRoot once, then cleared. */
    private var openRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always-dark UI: light system-bar icons over transparent bars, content drawn edge to edge.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 1.0.8 fast start: the Recents icon (a bitmap render) waits until after the first frame instead of delaying it.
        window.decorView.post { setRecentsIcon() }
        // A crash report saved by a previous run that the crash screen could not show: show it now, once.
        if (savedInstanceState == null && CrashGuard.pendingReport(this) != null) {
            runCatching { startActivity(Intent(this, CrashActivity::class.java)) }
        }
        Graph.init(application)
        openPlayer = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true
        if (savedInstanceState == null) playPinned(intent) // 1.0.2: a pinned song shortcut
        if (savedInstanceState == null) openFromWidget(intent) // Pack 5 Part 2: stats / shortcuts widgets
        // 1.0.2 Privacy › Block screenshots: FLAG_SECURE follows the setting live.
        lifecycleScope.launch {
            Graph.awaitReady()
            Graph.settings.state.collect { st ->
                if (st.blockScreenshots) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        // v2.0.15: the animated splash is gone (ui/Splash.kt stays, unused); the app opens straight away, no reveal layer.
        setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().trackTaps()) { // 1.0.1: tap origin for container transforms
                // v2.0.15: first-time design setup. Shown after the Welcome flow (which lives inside AppRoot and sets
                // `onboarded`), only once settings have loaded (Graph.ready), until setupDone is set (Finish or Skip).
                // 1.0.1: one combined, reactive (ready, settings) state. Up to 1.0.0 this read
                // `Graph.settings.state.value` once ready: a plain StateFlow read is not observed by Compose, and the
                // collected `settings` was only read while !ready, so after start-up this scope never recomposed on a
                // settings change. Finishing Welcome (onboarded = true) therefore never swapped in the setup flow
                // (Customize + Artists steps were skipped), Settings › Run setup again did nothing, and Finish/Skip in
                // a setup shown at launch could not leave it. combine() keeps the v2.0.16 guarantee (no defaults
                // flash): settings load before `ready` flips, so the first ready=true pair carries loaded settings.
                val gate by remember {
                    kotlinx.coroutines.flow.combine(Graph.ready, Graph.settings.state) { r, st -> r to st }
                }.collectAsState(initial = remember { Graph.ready.value to Graph.settings.state.value }) // 1.0.10 lint: initial snapshot read once, not on every recomposition
                val (ready, s) = gate
                if (!ready) {
                    com.ravi.musicspot.ui.theme.MusicSpotTheme(artUrl = null, dynamic = s.dynamicColor, mode = s.themeMode) {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                        )
                    }
                } else if (s.onboarded && !s.setupDone) {
                    com.ravi.musicspot.ui.theme.MusicSpotTheme(artUrl = null, dynamic = s.dynamicColor, mode = s.themeMode) {
                        com.ravi.musicspot.ui.setup.SetupFlow()
                    }
                } else {
                    AppRoot(openPlayer = openPlayer, onOpenPlayerConsumed = { openPlayer = false },
                        openRoute = openRoute, onOpenRouteConsumed = { openRoute = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer = true
        playPinned(intent)
        openFromWidget(intent)
    }

    /**
     * Pack 5 Part 2: widget taps. Screens (Your stats, Search, Downloads) go to AppRoot via [openRoute]; Shuffle and
     * Resume start playback here through the normal player entry points, then show the player.
     */
    private fun openFromWidget(intent: Intent?) {
        val r = intent?.getStringExtra(EXTRA_OPEN_ROUTE) ?: return
        intent.removeExtra(EXTRA_OPEN_ROUTE) // a rotation or re-delivery doesn't repeat it
        when (r) {
            ROUTE_SHUFFLE -> lifecycleScope.launch {
                Graph.awaitReady()
                val lib = Graph.library.data.value
                val tracks = lib.liked.ifEmpty { lib.history.map { it.track } }.distinctBy { it.id }
                if (tracks.isEmpty()) com.ravi.musicspot.ui.Pills.show("Nothing to shuffle yet. Like or play a few songs first.")
                else { Graph.player.play(tracks, tracks.indices.random(), shuffle = true); openPlayer = true }
            }
            ROUTE_RESUME -> lifecycleScope.launch {
                Graph.awaitReady()
                runCatching { Graph.player.connect() }
                // PlaybackService restores the saved queue (song + position) when it starts; wait for it, then play.
                val st = kotlinx.coroutines.withTimeoutOrNull(8_000) { Graph.player.state.first { it.current != null } }
                if (st == null) com.ravi.musicspot.ui.Pills.show("No previous session to resume yet")
                else { if (!st.playing) Graph.player.toggle(); openPlayer = true }
            }
            ROUTE_WRAPPED, ROUTE_SEARCH, ROUTE_DOWNLOADS -> openRoute = r
        }
    }

    /** 1.0.2: a home-screen song shortcut (player ⋮ › Pin to home screen) plays its song. */
    private fun playPinned(intent: Intent?) {
        com.ravi.musicspot.core.TrackShortcuts.playlistFrom(intent)?.let { pid ->
            intent?.action = null
            // The library loads in the background at start-up: wait for it, then play the playlist.
            com.ravi.musicspot.core.IoScope.launch {
                Graph.awaitReady()
                val tracks = Graph.library.playlist(pid)?.tracks.orEmpty()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (tracks.isEmpty()) com.ravi.musicspot.ui.Pills.show("That playlist is gone or empty") else Graph.player.play(tracks)
                }
            }
            return
        }
        val t = com.ravi.musicspot.core.TrackShortcuts.trackFrom(intent) ?: return
        try { Graph.player.play(listOf(t)) } catch (e: Exception) { CrashGuard.nonFatal(this, "pinned.play", e) }
        intent?.action = null // a rotation or re-delivery doesn't restart it
    }

    override fun onStart() {
        super.onStart()
        // Binding the playback service must never take the UI down with it; the player retries on first use.
        try { Graph.player.connect() } catch (e: Exception) { CrashGuard.nonFatal(this, "player.connect", e) }
    }

    /**
     * v2.0.7: some Android 10 launchers (Lenovo tablets) draw a blank Recents header for apps whose only icon is an
     * adaptive vector. Give the task an explicit bitmap icon, label and colour.
     */
    private fun setRecentsIcon() = runCatching {
        val d = androidx.core.content.ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return@runCatching
        val px = (48 * resources.displayMetrics.density).toInt().coerceAtLeast(96)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, px, px); d.draw(canvas)
        val color = 0xFF140A0B.toInt()
        @Suppress("DEPRECATION")
        val td = if (android.os.Build.VERSION.SDK_INT >= 33)
            android.app.ActivityManager.TaskDescription.Builder().setLabel("UpZe").setPrimaryColor(color).build()
        else android.app.ActivityManager.TaskDescription("UpZe", bmp, color)
        setTaskDescription(td)
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        /** Pack 5 Part 2: widget → screen or action (one of the ROUTE_ values). */
        const val EXTRA_OPEN_ROUTE = "open_route"
        const val ROUTE_WRAPPED = "wrapped"
        const val ROUTE_SEARCH = "search"
        const val ROUTE_DOWNLOADS = "downloads"
        const val ROUTE_SHUFFLE = "shuffle"
        const val ROUTE_RESUME = "resume"
    }
}
