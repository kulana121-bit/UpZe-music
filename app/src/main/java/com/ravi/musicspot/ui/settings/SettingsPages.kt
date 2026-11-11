package com.ravi.musicspot.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome // v2.0.15
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.ui.res.vectorResource
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.BuildConfig
import com.ravi.musicspot.Graph
import com.ravi.musicspot.account.Account
import com.ravi.musicspot.account.SyncManager
import com.ravi.musicspot.data.GlassStyle
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.screens.LocalNav
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Routes Route.SettingsPage(page) to its screen. */
@Composable
fun SettingsPageScreen(page: String) {
    when (page) {
        "accounts" -> AccountsPage()
        "sources" -> SourcesPage()
        "quality" -> QualityPage()
        "playback" -> PlaybackPage()
        "sound" -> SoundPage()
        "lyrics" -> LyricsPage()
        "appearance" -> AppearancePage()
        "storage" -> StoragePage()
        "backup" -> BackupPage() // 1.0.2
        "stats" -> ListenStatsPage() // 1.0.2
        "ai" -> AiPage() // 1.0.2
        "privacy" -> PrivacyPage() // 1.0.2
        "diagnostics" -> DiagnosticsPage()
        "about" -> AboutPage()
        "playbacklog" -> PlaybackLogPage()
        else -> SettingsScaffold("Settings") { item { EmptyState(Icons.Rounded.Construction, "Unknown page", "This settings page doesn't exist.") } }
    }
}

// ---- Accounts ----------------------------------------------------------------------------------------------------

@Composable
fun AccountsPage() {
    val nav = LocalNav.current
    val accounts by Graph.accounts.accounts.collectAsState()
    val ytStatus by Graph.sync.yt.collectAsState()
    val spStatus by Graph.sync.spotify.collectAsState()
    var confirm by remember { mutableStateOf<Account.Service?>(null) }
    SettingsScaffold("Accounts") {
        item(key = "settingspages-1", contentType = "static") {
            AccountCard("YouTube Music (Google)", accounts.firstOrNull { it.service == Account.Service.YOUTUBE }, ytStatus,
                signedOutText = "Sign in to sync your likes, playlists, albums and history, and to get Premium-quality streams when your account has them.",
                onSignIn = { nav.push(Route.GoogleLogin) }, onSync = { Graph.sync.syncYouTube() }, onSignOut = { confirm = Account.Service.YOUTUBE })
        }
        item(key = "settingspages-2", contentType = "static") {
            AccountCard("Spotify", accounts.firstOrNull { it.service == Account.Service.SPOTIFY }, spStatus,
                signedOutText = "Sign in to import your Liked Songs and playlists. Spotify songs are matched to sources that can play them; nothing streams from Spotify.",
                onSignIn = { nav.push(Route.SpotifyLogin) }, onSync = { Graph.sync.syncSpotify() }, onSignOut = { confirm = Account.Service.SPOTIFY })
        }
        item(key = "settingspages-3", contentType = "static") { InfoText("Sign-in cookies are encrypted with a key kept in the Android Keystore, never leave this device and are excluded from backups. Music servers (Navidrome, Jellyfin) are set up under Sources.") }
    }
    com.ravi.musicspot.ui.motion.MotionAlertDialog(item = confirm, onDismissRequest = { confirm = null },
        title = { svc -> Text("Sign out of ${if (svc == Account.Service.YOUTUBE) "YouTube Music" else "Spotify"}?") },
        text = { Text("Synced playlists from this account are removed from UpZe. Your liked songs, history and own playlists stay.") },
        confirmButton = { svc -> TextButton(onClick = {
            if (svc == Account.Service.YOUTUBE) Graph.sync.signOutYouTube() else Graph.sync.signOutSpotify()
            confirm = null
        }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
}

@Composable
private fun AccountCard(title: String, account: Account?, status: SyncManager.Status, signedOutText: String, onSignIn: () -> Unit, onSync: () -> Unit, onSignOut: () -> Unit) {
    Group(title) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (account?.avatarUrl != null) Artwork(account.avatarUrl, Modifier.size(48.dp), CircleShape)
            else Icon(Icons.Rounded.AccountCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (account != null) {
                    Text(account.name ?: "Signed in", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    account.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    Text("Not signed in", style = MaterialTheme.typography.titleMedium)
                    Text(signedOutText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (account != null) {
            if (status.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (status.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account == null) Button(onClick = onSignIn) { Text("Sign in") }
            else {
                FilledTonalButton(onClick = onSync, enabled = !status.running) { Text(if (status.running) "Syncing…" else "Sync now") }
                OutlinedButton(onClick = onSignOut) { Text("Sign out") }
            }
        }
    }
}

// ---- Quality -----------------------------------------------------------------------------------------------------

@Composable
fun QualityPage() {
    val s by Graph.settings.state.collectAsState()
    val q = Quality.entries
    SettingsScaffold("Quality") {
        item(key = "settingspages-4", contentType = "static") { Group("On Wi-Fi") { OptionList(q, s.quality, { it.label }, { it.detail }) { v -> Graph.settings.update { it.copy(quality = v) } } } }
        item(key = "settingspages-5", contentType = "static") {
            Group("On mobile data", footer = "Used whenever the connection is metered (mobile data or a metered hotspot).") {
                OptionList(q, s.mobileQuality, { it.label }, { it.detail }) { v -> Graph.settings.update { it.copy(mobileQuality = v) } }
            }
        }
        item(key = "settingspages-6", contentType = "static") {
            Group("Downloads") {
                ChoiceRow("Download quality", q, s.downloadQuality, { it.label }) { v -> Graph.settings.update { it.copy(downloadQuality = v) } }
                SwitchRow("Download on Wi-Fi only", "Queued downloads wait for an unmetered network", s.wifiOnlyDownloads) { v -> Graph.settings.update { it.copy(wifiOnlyDownloads = v) } }
            }
        }
        item(key = "settingspages-7", contentType = "static") { InfoText("YouTube Music and JioSaavn top out at lossy 256–320 kbps. Lossless comes from your own files and servers (Navidrome, Jellyfin) and free catalogues that offer FLAC (Jamendo, Internet Archive). The player shows the real codec and bitrate of what is playing.") }
    }
}

// ---- Playback ----------------------------------------------------------------------------------------------------

@Composable
fun PlaybackPage() {
    val s by Graph.settings.state.collectAsState()
    val bitPerfect by Playback.bitPerfectStatus.collectAsState()
    SettingsScaffold("Playback") {
        item(key = "settingspages-8", contentType = "static") {
            Group("Transitions") {
                SwitchRow("Gapless playback", "Albums play without a gap between songs. Turn off to use crossfade.", s.gapless) { v -> Graph.settings.update { it.copy(gapless = v, crossfadeSec = if (!v && it.crossfadeSec == 0) 4 else it.crossfadeSec) } }
                RowDivider()
                SliderRow("Crossfade", s.crossfadeSec.toFloat(), 0f..12f, if (s.gapless || s.crossfadeSec == 0) "Off" else "${s.crossfadeSec} s", steps = 11, enabled = !s.gapless,
                    subtitle = if (s.gapless) "Off while gapless is on" else "Fades out the end of a song and fades in the next") { v -> Graph.settings.update { it.copy(crossfadeSec = v.roundToInt()) } }
                RowDivider()
                // Pack 6 Part 2: DJ Mode. Turning it on switches gapless off and makes sure there is a crossfade window
                // (8 s when it was shorter than 6 s); turning it off leaves gapless and crossfade as they are.
                SwitchRow("DJ Mode",
                    when {
                        s.djMode && (s.gapless || s.crossfadeSec == 0) -> "Waiting: needs crossfade on (gapless off)"
                        else -> "Beat-matched transitions: the next song starts on the beat, tempo matched within ±6%. Songs without a clear beat get the normal crossfade."
                    }, s.djMode) { v ->
                    Graph.settings.update { if (v) it.copy(djMode = true, gapless = false, crossfadeSec = if (it.crossfadeSec < 6) 8 else it.crossfadeSec) else it.copy(djMode = false) }
                }
            }
        }
        item(key = "settingspages-9", contentType = "static") {
            Group("Queue") {
                SwitchRow("Autoplay", "When the queue ends, keep playing similar songs (radio)", s.autoplay) { v -> Graph.settings.update { it.copy(autoplay = v) } }
            }
        }
        item(key = "settingspages-10", contentType = "static") { PlaybackExtrasGroups() } // 1.0.2
        item(key = "settingspages-11", contentType = "static") {
            Group("Sleep timer defaults") {
                ChoiceRow("Default duration", listOf(10, 15, 20, 30, 45, 60, 90), s.sleepDefaultMin, { "$it min" }, subtitle = "Offered in the player's sleep timer") { v -> Graph.settings.update { it.copy(sleepDefaultMin = v) } }
                RowDivider()
                SwitchRow("Fade out", "Lower the volume over the last 30 seconds", s.sleepFade) { v -> Graph.settings.update { it.copy(sleepFade = v) } }
                RowDivider()
                SwitchRow("Finish the current song", "Wait for the song to end instead of stopping mid-song", s.sleepFinishSong) { v -> Graph.settings.update { it.copy(sleepFinishSong = v) } }
            }
        }
        item(key = "settingspages-12", contentType = "static") {
            val usbOk = Build.VERSION.SDK_INT >= 34
            Group("Hi-res output", footer = "Both options bypass the software engine (EQ, Enhance) so samples reach the output untouched. They take effect the next time the player starts (close and reopen the app).") {
                SwitchRow("Pure hi-res output", "Float output path: 24-bit / 96 kHz+ files are not truncated", s.pureHiRes) { v -> Graph.settings.update { it.copy(pureHiRes = v) } }
                RowDivider()
                SwitchRow("Bit-perfect USB DAC", if (usbOk) (bitPerfect ?: "Asks Android for bit-perfect mixing on a connected USB DAC") else "Needs Android 14 or newer",
                    s.bitPerfectUsb, enabled = usbOk) { v -> Graph.settings.update { it.copy(bitPerfectUsb = v) } }
            }
        }
    }
}

// ---- Lyrics ------------------------------------------------------------------------------------------------------

@Composable
fun LyricsPage() {
    val s by Graph.settings.state.collectAsState()
    val labels = remember { Graph.lyricsProviders().associate { it.id to it.label } }
    val order = (s.lyricsOrder + labels.keys).distinct().filter { it in labels }
    SettingsScaffold("Lyrics") {
        item(key = "settingspages-13", contentType = "static") {
            Group("Provider order", footer = "Long-press the handle and drag. The first provider that has lyrics for a song wins; synced lyrics are preferred.") {
                ReorderList(order, onReorder = { l -> Graph.settings.update { it.copy(lyricsOrder = l) } }, label = { labels[it] ?: it }) { id, index, handle ->
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        DragHandle(handle)
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                        Text(labels[id] ?: id, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = id in s.lyricsEnabled, onCheckedChange = { on ->
                            Graph.settings.update { st -> st.copy(lyricsEnabled = if (on) st.lyricsEnabled + id else st.lyricsEnabled - id) }
                        })
                    }
                }
            }
        }
        item(key = "settingspages-14", contentType = "static") {
            Group("Sync") {
                SliderRow("Sync offset", s.lyricsOffsetMs.toFloat(), -3000f..3000f, "%+.2f s".format(s.lyricsOffsetMs / 1000f), steps = 119,
                    subtitle = "Positive shows lines earlier; also adjustable from the lyrics panel") { v -> Graph.settings.update { it.copy(lyricsOffsetMs = (v / 50).roundToInt() * 50) } }
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { TextButton(onClick = { Graph.settings.update { it.copy(lyricsOffsetMs = 0) } }, enabled = s.lyricsOffsetMs != 0) { Text("Reset offset") } }
                RowDivider()
                SwitchRow("Romanize", "Show Latin-script lines for Japanese, Korean, Hindi and other scripts", s.romanize) { v -> Graph.settings.update { it.copy(romanize = v) } }
                RowDivider()
                // 1.0.8: also switchable from the player's ⋯ menu.
                SwitchRow("Motion Lyrics", "Lyrics move over the artwork on Now Playing, with the beat and tone of the song", s.liveLyrics) { v -> Graph.settings.update { it.copy(liveLyrics = v) } }
            }
        }
        item(key = "settingspages-15", contentType = "static") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { Graph.lyricsCache.clear(); com.ravi.musicspot.ui.Pills.show("Lyrics cache cleared") }) { Text("Clear lyrics cache") }
            }
        }
    }
}

// ---- Appearance --------------------------------------------------------------------------------------------------

@Composable
fun AppearancePage() {
    val s by Graph.settings.state.collectAsState()
    SettingsScaffold("Appearance") {
        item(key = "settingspages-16", contentType = "static") {
            Group("Colour") {
                ChoiceRow("Theme", com.ravi.musicspot.data.ThemeMode.entries, s.themeMode, { it.label }) { v -> Graph.settings.update { it.copy(themeMode = v) } }
                RowDivider()
                SwitchRow("Dynamic colour", "Tint the app with soft colours from the playing song's artwork", s.dynamicColor) { v -> Graph.settings.update { it.copy(dynamicColor = v) } }
            }
        }
        item(key = "settingspages-17", contentType = "static") {
            Group("Dock") {
                ChoiceRow("Dock design", com.ravi.musicspot.data.DockStyle.entries, s.dockStyle, { it.label },
                    subtitle = "Classic: full-width bar with the mini player · Orb: glass tab pill with a floating cover") { v -> Graph.settings.update { it.copy(dockStyle = v) } }
            }
        }
        item(key = "settingspages-18", contentType = "static") {
            Group("Glass", footer = if (Graph.lowEnd) "This device reports low memory, so solid surfaces are always used." else "Liquid needs Android 13+, frosted blur Android 12+; older versions use a tinted surface.") {
                SwitchRow("Reduce effects", "Solid surfaces instead of blur and lens glass (faster, easier to read)", s.glass == GlassStyle.SOLID, enabled = !Graph.lowEnd) { v ->
                    Graph.settings.update { it.copy(glass = if (v) GlassStyle.SOLID else GlassStyle.LIQUID) }
                }
                RowDivider()
                ChoiceRow("Glass style", GlassStyle.entries, s.glass, { it.label }, enabled = !Graph.lowEnd) { v -> Graph.settings.update { it.copy(glass = v) } }
                RowDivider()
                // 1.0.2: blur strength for the dock, mini player, player glass and sheets (0 = no blur, cheapest).
                val blurOn = !Graph.lowEnd && s.glass != GlassStyle.SOLID && s.glass != GlassStyle.WATER
                SliderRow("Blur intensity", s.blurIntensity, 0f..1f,
                    valueLabel = if (s.blurIntensity < 0.02f) "Off" else "${(s.blurIntensity * 100).roundToInt()}%",
                    steps = 9, enabled = blurOn,
                    subtitle = if (blurOn) "Lower is lighter on older phones" else "Not used by this glass style") { v ->
                    Graph.settings.update { it.copy(blurIntensity = (v * 10f).roundToInt() / 10f) }
                }
            }
        }
        item(key = "settingspages-19", contentType = "static") {
            Group("Motion & touch") {
                SwitchRow("Reduce motion", "Fades instead of slides and springs. Also follows the system “Remove animations” setting.", s.reduceMotion) { v -> Graph.settings.update { it.copy(reduceMotion = v) } }
                RowDivider()
                ChoiceRow("Navigation bar on scroll", com.ravi.musicspot.data.NavBarMode.entries, s.navMode, { it.label },
                    subtitle = "While scrolling down, Mini shrinks the bottom bar to a small pill, Hide slides it away; scrolling up brings it back. Tablets keep the side rail.") { v ->
                    Graph.settings.update { it.copy(navBarMode = v, hideNavOnScroll = v != com.ravi.musicspot.data.NavBarMode.ALWAYS) }
                }
                RowDivider()
                SwitchRow("Haptics", "Light taps on controls and gestures", s.haptics) { v -> Graph.settings.update { it.copy(haptics = v) } }
            }
        }
        // v2.0.15: reopen the first-time design setup (theme, glass, player, dock, home, motion, audio).
        // 1.0.1: these rows used to flip `setupDone` off and rely on MainActivity swapping the whole UI, which never
        // recomposed (see MainActivity), so nothing opened. They now open the screens in-app, above the dock, and
        // Back / Close / Done return here.
        item(key = "settingspages-20", contentType = "static") {
            val nav = LocalNav.current
            val seeds by com.ravi.musicspot.data.SeedArtists.state.collectAsState()
            Group("Setup") {
                NavRow(Icons.Rounded.AutoAwesome, "Customize", "Walk through theme, glass, player, dock, home, artists and motion with a live preview") {
                    nav.push(com.ravi.musicspot.ui.Route.Customize)
                }
                RowDivider()
                NavRow(Icons.Rounded.Person, "Your artists", if (seeds.isEmpty()) "Pick artists to tune Home's “For you”" else "${seeds.size} picked · ${seeds.take(3).joinToString { it.name }}") {
                    nav.push(com.ravi.musicspot.ui.Route.Artists)
                }
            }
        }
    }
}

// ---- About / licenses --------------------------------------------------------------------------------------------

private data class Credit(val name: String, val license: String, val what: String, val url: String)

private val CREDITS = listOf(
    Credit("OpenTune", "GPL-3.0", "Innertube parser, player panes, pill notifications and ideas (bit-perfect USB, AutoEq) ported from it", "https://github.com/Arturo254/OpenTune"),
    Credit("NewPipe Extractor", "GPL-3.0", "YouTube stream extraction fallback and SoundCloud", "https://github.com/TeamNewPipe/NewPipeExtractor"),
    Credit("Kyant Backdrop", "Apache-2.0", "Liquid glass lens refraction (Android 13+)", "https://github.com/Kyant0/AndroidLiquidGlass"),
    Credit("Haze", "Apache-2.0", "Backdrop blur (Android 12)", "https://github.com/chrisbanes/haze"),
    Credit("AndroidX Media3 / ExoPlayer", "Apache-2.0", "Playback, media session, caching", "https://github.com/androidx/media"),
    Credit("AutoEq", "MIT", "Headphone correction profiles (fetched on demand)", "https://github.com/jaakkopasanen/AutoEq"),
    Credit("LRCLIB", "Open data", "Synced lyrics", "https://lrclib.net"),
    Credit("Coil, OkHttp, kotlinx", "Apache-2.0", "Images, networking, serialization and coroutines", "https://github.com/coil-kt/coil"),
    Credit("Rhino, jsoup, nanojson", "MPL-2.0 / MIT", "Used by NewPipe Extractor", "https://github.com/mozilla/rhino"),
)

@Composable
fun AboutPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings by Graph.settings.state.collectAsState()
    val checking by com.ravi.musicspot.update.Updater.checking.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SettingsScaffold("About & licenses") {
        item(key = "settingspages-21", contentType = "static") {
            Group {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("UpZe", style = MaterialTheme.typography.headlineSmall)
                    Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " · debug" else ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(10.dp))
                    Text("UpZe is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License version 3 (GPL-3.0) as published by the Free Software Foundation. It is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { com.ravi.musicspot.core.Links.open(ctx, "https://www.gnu.org/licenses/gpl-3.0.html") }) { Text("Read the GPL-3.0") }
                }
            }
        }
        item(key = "settingspages-22", contentType = "static") {
            // 1.0.0: in-app updates from GitHub Releases.
            Group("Updates") {
                NavRow(androidx.compose.material.icons.Icons.Rounded.SystemUpdate, "Check for updates", if (checking) "Checking…" else "Looks for a newer UpZe release on GitHub") {
                    if (!checking) scope.launch {
                        when (com.ravi.musicspot.update.Updater.check(ctx, manual = true)) {
                            com.ravi.musicspot.update.Updater.Result.UPDATE -> {}
                            com.ravi.musicspot.update.Updater.Result.UP_TO_DATE -> com.ravi.musicspot.ui.Pills.show("UpZe is up to date")
                            com.ravi.musicspot.update.Updater.Result.FAILED -> com.ravi.musicspot.ui.Pills.show("Couldn't check for updates")
                        }
                    }
                }
                RowDivider()
                SwitchRow("Check for updates automatically", "When the app starts, at most every 12 hours", settings.autoUpdateCheck) { on ->
                    Graph.settings.update { it.copy(autoUpdateCheck = on) }
                }
            }
        }
        item(key = "settingspages-23", contentType = "static") {
            // 1.0.0: developer and source code.
            Group("Developer") {
                NavRow(androidx.compose.material.icons.Icons.Rounded.Person, "Developer", "Ravi") { com.ravi.musicspot.core.Links.open(ctx, com.ravi.musicspot.core.Links.GITHUB) }
                RowDivider()
                NavRow(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.ravi.musicspot.R.drawable.ic_brand_github), "GitHub", "kulana121-bit/UpZe-music · source code, releases and issues") {
                    com.ravi.musicspot.core.Links.open(ctx, com.ravi.musicspot.core.Links.GITHUB)
                }
                RowDivider()
                NavRow(androidx.compose.ui.graphics.vector.ImageVector.vectorResource(com.ravi.musicspot.R.drawable.ic_brand_instagram), "Instagram", "@jus7_rv") {
                    com.ravi.musicspot.core.Links.open(ctx, com.ravi.musicspot.core.Links.INSTAGRAM)
                }
            }
        }
        item(key = "settingspages-24", contentType = "static") {
            val nav = LocalNav.current
            Group("Troubleshooting") {
                NavRow(null, "Playback log", "Every step of the last play attempts (YouTube clients, JioSaavn fallback, player errors). Copy or share it with the developer") {
                    nav.push(Route.SettingsPage("playbacklog"))
                }
                RowDivider()
                NavRow(null, "Last crash report", "Shows the saved error report so you can copy or share it with the developer") {
                    com.ravi.musicspot.CrashActivity.showPrevious(ctx)
                }
            }
        }
        item(key = "settingspages-25", contentType = "static") { InfoText("No music is hosted by UpZe. Streams come from the services you enable, your own files and your own servers.") }
    }
}

/** v2.0.4: Settings › About › Playback log (ring buffer of the last ~300 lines, redacted). */
@Composable
fun PlaybackLogPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val version by com.ravi.musicspot.core.PlaybackLog.version.collectAsState()
    val text = remember(version) { com.ravi.musicspot.core.PlaybackLog.text() }
    var copied by remember { mutableStateOf(false) }
    SettingsScaffold("Playback log") {
        item(key = "settingspages-26", contentType = "static") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(text)); copied = true }) { Text(if (copied) "Copied" else "Copy") }
                FilledTonalButton(onClick = { runCatching { ctx.startActivity(com.ravi.musicspot.core.PlaybackLog.shareIntent()) } }) { Text("Share") }
                OutlinedButton(onClick = { com.ravi.musicspot.core.PlaybackLog.clear(); copied = false }) { Text("Clear") }
            }
        }
        item(key = "settingspages-27", contentType = "static") { InfoText("Cookies, tokens and URL query strings are removed. Play a song that fails, then share this log.") }
        item(key = "settingspages-28", contentType = "static") {
            Group {
                if (text.isBlank()) Text("Nothing logged yet. Play a song first.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }
            }
        }
    }
}
