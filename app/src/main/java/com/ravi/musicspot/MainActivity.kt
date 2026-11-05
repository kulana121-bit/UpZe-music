package com.ravi.musicspot

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ravi.musicspot.core.CrashGuard
import com.ravi.musicspot.ui.AppRoot

class MainActivity : ComponentActivity() {
    private var openPlayer by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always-dark UI: light system-bar icons over transparent bars, content drawn edge to edge.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setRecentsIcon()
        // A crash report saved by a previous run that the crash screen could not show: show it now, once.
        if (savedInstanceState == null && CrashGuard.pendingReport(this) != null) {
            runCatching { startActivity(Intent(this, CrashActivity::class.java)) }
        }
        Graph.init(application)
        openPlayer = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true
        // v2.0.15: the animated splash is gone (ui/Splash.kt stays, unused); the app opens straight away, no reveal layer.
        setContent {
            androidx.compose.foundation.layout.Box {
                // v2.0.15: first-time design setup. Shown after the Welcome flow (which lives inside AppRoot and sets
                // `onboarded`), only once settings have loaded (Graph.ready), until setupDone is set (Finish or Skip).
                val settings by Graph.settings.state.collectAsState()
                val ready by Graph.ready.collectAsState()
                // v2.0.16: the two collectors can deliver out of order (ready=true while `settings` still holds the
                // defaults), which flashed Welcome/AppRoot before Setup. Settings load before ready flips, so read the
                // flow's current value once ready; and draw nothing but the background until then.
                // 1.0.0: intentional; `settings` above already subscribes this scope, so the read is never stale.
                @android.annotation.SuppressLint("StateFlowValueCalledInComposition")
                val s = if (ready) Graph.settings.state.value else settings
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
                    AppRoot(openPlayer = openPlayer, onOpenPlayerConsumed = { openPlayer = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer = true
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

    companion object { const val EXTRA_OPEN_PLAYER = "open_player" }
}
