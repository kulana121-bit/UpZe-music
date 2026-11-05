package com.ravi.musicspot.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.account.Account
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.screens.LocalNav
import com.ravi.musicspot.ui.theme.Ms

private data class Entry(val page: String, val icon: ImageVector, val title: String, val keywords: String, val route: Route? = null) // 1.0.1: route

private val ENTRIES = listOf(
    Entry("accounts", Icons.Rounded.AccountCircle, "Accounts", "google youtube music spotify sign in sign out sync login"),
    Entry("sources", Icons.Rounded.Hub, "Sources", "priority order jiosaavn audius soundcloud jamendo archive navidrome subsonic jellyfin server client id"),
    Entry("quality", Icons.Rounded.HighQuality, "Quality", "auto high max lossless wifi mobile data download bitrate flac"),
    Entry("playback", Icons.Rounded.PlayCircle, "Playback", "crossfade gapless autoplay radio sleep timer usb dac bit-perfect hi-res"),
    Entry("sound", Icons.Rounded.Equalizer, "Sound", "equalizer eq parametric preset autoeq headphone preamp bass widener enhance upscale resampler bypass"),
    Entry("lyrics", Icons.Rounded.Lyrics, "Lyrics", "provider order lrclib kugou netease sync offset romanize"),
    // 1.0.1: the Customize flow and the artist picker are reachable straight from Settings (and its search).
    Entry("customize", Icons.Rounded.AutoAwesome, "Customize", "setup design look theme glass player dock home artists motion wizard run again onboarding", Route.Customize),
    Entry("artists", Icons.Rounded.Person, "Your artists", "artists favourite pick seed for you home recommendations tune", Route.Artists),
    Entry("appearance", Icons.Rounded.Palette, "Appearance", "dynamic colour color theme glass blur reduce effects motion animation haptics"),
    Entry("storage", Icons.Rounded.Storage, "Storage", "cache downloads clear space size delete"),
    Entry("diagnostics", Icons.Rounded.MonitorHeart, "Diagnostics", "test probe latency reachability network source status"),
    Entry("about", Icons.Rounded.Info, "About & licenses", "version gpl license credits opentune newpipe backdrop haze media3 update updates github developer ravi"),
)

@Composable
fun SettingsHome() {
    val nav = LocalNav.current
    val s by Graph.settings.state.collectAsState()
    val accounts by Graph.accounts.accounts.collectAsState()
    val seeds by com.ravi.musicspot.data.SeedArtists.state.collectAsState() // 1.0.1
    var query by rememberSaveable { mutableStateOf("") }

    fun subtitle(page: String): String = when (page) {
        "accounts" -> listOfNotNull(
            accounts.firstOrNull { it.service == Account.Service.YOUTUBE }?.let { "YouTube Music: ${it.name ?: it.email ?: "signed in"}" },
            accounts.firstOrNull { it.service == Account.Service.SPOTIFY }?.let { "Spotify: ${it.name ?: "signed in"}" },
        ).joinToString(" · ").ifBlank { "Not signed in" }
        "sources" -> "${s.enabledSources.size} on · ${s.sourcePriority.filter { it in s.enabledSources }.take(3).joinToString(" › ") { it.label }}"
        "quality" -> "Wi-Fi ${s.quality.label} · Mobile ${s.mobileQuality.label}"
        "playback" -> listOf(if (s.gapless) "Gapless" else if (s.crossfadeSec > 0) "Crossfade ${s.crossfadeSec}s" else "No crossfade",
            if (s.autoplay) "Autoplay on" else "Autoplay off").joinToString(" · ")
        "sound" -> when {
            s.pureHiRes || s.bitPerfectUsb -> "Bypassed (pure / bit-perfect output)"
            !s.eq.enabled || s.eq.bypassB -> "EQ off"
            else -> "EQ: ${s.eq.preset}"
        } + if (s.upscaleRate > 0 || s.bwe != com.ravi.musicspot.dsp.EnhanceLevel.OFF) " · Enhance on" else ""
        "lyrics" -> s.lyricsOrder.filter { it in s.lyricsEnabled }.take(3).joinToString(" › ")
        "appearance" -> listOfNotNull(if (s.dynamicColor) "Dynamic colour" else null, s.glass.label, if (s.reduceMotion) "Reduced motion" else null).joinToString(" · ")
        "storage" -> "Cache limit ${if (s.cacheLimitMb >= 1024) "${s.cacheLimitMb / 1024} GB" else "${s.cacheLimitMb} MB"}"
        "diagnostics" -> "Test every source"
        "customize" -> "Theme, glass, player, dock, home, artists and motion"
        "artists" -> if (seeds.isEmpty()) "Tune Home to artists you love" else "${seeds.size} picked"
        else -> "UpZe ${com.ravi.musicspot.BuildConfig.VERSION_NAME} · GPL-3.0"
    }

    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) ENTRIES else ENTRIES.filter { e -> q.split(' ').filter { it.isNotBlank() }.all { t -> e.title.lowercase().contains(t) || e.keywords.contains(t) } }

    SettingsScaffold("Settings", back = false) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, shape = Ms.pill,
                placeholder = { Text("Search settings") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (shown.isEmpty()) item { EmptyState(Icons.Rounded.Search, "No matching settings", "Try a word like “crossfade”, “EQ” or “cache”.") }
        else item {
            Group {
                shown.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    NavRow(e.icon, e.title, subtitle(e.page)) { nav.push(e.route ?: Route.SettingsPage(e.page)) }
                }
            }
        }
        item { Box(Modifier.padding(8.dp)) { } }
    }
}

