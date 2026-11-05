package com.ravi.musicspot.ui.account

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.account.Account
import com.ravi.musicspot.core.IoScope
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.settings.OptionList
import kotlinx.coroutines.launch

private const val STEPS = 5 // welcome, account, sources, quality, done

/**
 * First-run Welcome flow, shown once (AppSettings.onboarded). Every step is optional: the account step has
 * "Skip / use without account", and nothing here is needed for the app to work as a guest.
 */
@Composable
fun WelcomeFlow() {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var dir by remember { mutableIntStateOf(1) }
    var signingIn by rememberSaveable { mutableStateOf(false) }
    val still = reducedMotion()
    fun go(to: Int) { dir = if (to > step) 1 else -1; step = to.coerceIn(0, STEPS - 1) }
    fun finish() {
        val yt = Graph.accounts.get(Account.Service.YOUTUBE)
        Graph.settings.update { it.copy(onboarded = true, guest = yt == null) }
    }

    if (signingIn) {
        GoogleLoginScreen { ok -> signingIn = false; if (ok) go(2) }
        return
    }
    BackHandler(enabled = step > 0) { go(step - 1) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dots(step)
                Spacer(Modifier.weight(1f))
                if (step in 1..3) TextButton(onClick = { finish() }) { Text("Skip setup") }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (still) fadeIn() togetherWith fadeOut()
                    else (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
                },
                label = "welcome",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { s ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
                        when (s) {
                            0 -> WelcomeStep()
                            1 -> AccountStep(onSignIn = { signingIn = true })
                            2 -> SourcesStep()
                            3 -> QualityStep()
                            else -> DoneStep()
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0 && step < STEPS - 1) OutlinedButton(onClick = { go(step - 1) }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                when (step) {
                    0 -> Button(onClick = { go(1) }) { Text("Get started") }
                    1 -> {
                        val yt by Graph.accounts.accounts.collectAsState()
                        if (yt.any { it.service == Account.Service.YOUTUBE }) Button(onClick = { go(2) }) { Text("Continue") }
                        else OutlinedButton(onClick = { go(2) }) { Text("Skip / use without account") }
                    }
                    STEPS - 1 -> Button(onClick = { finish() }) { Text("Start listening") }
                    else -> Button(onClick = { go(step + 1) }) { Text("Next") }
                }
            }
        }
    }
}

@Composable
private fun Dots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp)) {
        repeat(STEPS) { i ->
            Box(Modifier.size(width = if (i == step) 22.dp else 8.dp, height = 8.dp).clip(CircleShape)
                .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

@Composable
private fun ColumnScope.Header(icon: ImageVector, title: String, body: String) {
    Spacer(Modifier.height(24.dp))
    Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 4.dp), content = content)
}

@Composable
private fun ColumnScope.WelcomeStep() {
    Header(Icons.Rounded.MusicNote, "Welcome to UpZe",
        "Search and play from YouTube Music, JioSaavn, free catalogues, your own files and your music servers — in the best quality each one offers.")
    Card {
        Bullet(Icons.Rounded.LibraryMusic, "Works without an account", "Everything plays as a guest. Your likes, playlists and history stay on this phone.")
        Bullet(Icons.Rounded.AccountCircle, "Optional sign-in", "Connect YouTube Music or Spotify later to bring your library along.")
        Bullet(Icons.Rounded.GraphicEq, "Real audio engine", "EQ, AutoEq headphone profiles and an honest upscaler.")
    }
}

@Composable
private fun Bullet(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.AccountStep(onSignIn: () -> Unit) {
    val accounts by Graph.accounts.accounts.collectAsState()
    val yt = accounts.firstOrNull { it.service == Account.Service.YOUTUBE }
    Header(Icons.Rounded.AccountCircle, "Bring your YouTube Music",
        "Sign in with Google to sync your likes, playlists, library, history and subscriptions, and to get Premium-quality streams if your account has them.")
    Card {
        if (yt == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
                Text("Optional. You sign in on Google's own page; only the sign-in cookie is kept, encrypted on this phone. You can sign in or out any time in Settings › Accounts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Bullet(Icons.Rounded.CheckCircle, "Signed in" + (yt.name?.let { " as $it" } ?: ""), "Your library is syncing in the background.")
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("Spotify can be connected later from Settings › Accounts to import your playlists.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.SourcesStep() {
    val s by Graph.settings.state.collectAsState()
    var asking by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        asking = false
        if (ok) {
            Graph.settings.update { it.copy(enabledSources = it.enabledSources + SourceKind.LOCAL) }
            IoScope.launch { runCatching { Graph.local.scan() } }
        } else Pills.show("Permission denied — device music stays off")
    }
    fun toggle(k: SourceKind, on: Boolean) {
        if (on && k == SourceKind.LOCAL && !Graph.local.hasPermission()) { asking = true; perm.launch(Graph.local.permission); return }
        Graph.settings.update { it.copy(enabledSources = if (on) it.enabledSources + k else it.enabledSources - k) }
        if (on && k == SourceKind.LOCAL) IoScope.launch { runCatching { Graph.local.scan() } }
    }
    Header(Icons.Rounded.Tune, "Where should music come from?", "Pick any you like — you can change this and the order later in Settings › Sources.")
    Card {
        SourceRow("Music on this device", "Your own files, including FLAC/ALAC/WAV. Asks for permission only if you turn it on.", SourceKind.LOCAL in s.enabledSources, !asking) { toggle(SourceKind.LOCAL, it) }
        SourceRow(SourceKind.YOUTUBE.label, "The main catalogue", SourceKind.YOUTUBE in s.enabledSources) { toggle(SourceKind.YOUTUBE, it) }
        SourceRow(SourceKind.JIOSAAVN.label, "Indian & international music, 320 kbps", SourceKind.JIOSAAVN in s.enabledSources) { toggle(SourceKind.JIOSAAVN, it) }
        SourceRow(SourceKind.AUDIUS.label, "Independent artists, up to 320 kbps", SourceKind.AUDIUS in s.enabledSources) { toggle(SourceKind.AUDIUS, it) }
        SourceRow(SourceKind.SOUNDCLOUD.label, "Remixes and uploads", SourceKind.SOUNDCLOUD in s.enabledSources) { toggle(SourceKind.SOUNDCLOUD, it) }
        SourceRow(SourceKind.ARCHIVE.label, "Live recordings and public domain, often FLAC", SourceKind.ARCHIVE in s.enabledSources) { toggle(SourceKind.ARCHIVE, it) }
    }
    Spacer(Modifier.height(12.dp))
    Text("Navidrome/Subsonic, Jellyfin and Jamendo can be added in Settings › Sources.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SourceRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ColumnScope.QualityStep() {
    val s by Graph.settings.state.collectAsState()
    Header(Icons.Rounded.GraphicEq, "Sound quality", "What to aim for on Wi-Fi. Mobile data uses its own setting (High by default) in Settings › Quality.")
    Card { OptionList(Quality.entries, s.quality, { it.label }, { it.detail }) { v -> Graph.settings.update { it.copy(quality = v) } } }
}

@Composable
private fun ColumnScope.DoneStep() {
    val s by Graph.settings.state.collectAsState()
    val accounts by Graph.accounts.accounts.collectAsState()
    val yt = accounts.firstOrNull { it.service == Account.Service.YOUTUBE }
    Header(Icons.Rounded.CheckCircle, "You're all set", "Everything here can be changed any time in Settings.")
    Card {
        Bullet(Icons.Rounded.AccountCircle, if (yt != null) "YouTube Music connected" else "Using UpZe as a guest",
            if (yt != null) "Your library syncs in the background." else "Sign in later from Settings › Accounts if you want your library.")
        Bullet(Icons.Rounded.Tune, "Sources", s.sourcePriority.filter { it in s.enabledSources }.joinToString(" · ") { it.label }.ifBlank { "None turned on" })
        Bullet(Icons.Rounded.GraphicEq, "Quality", s.quality.label)
    }
    Spacer(Modifier.height(12.dp))
    Text("Tip: swipe up on the mini player for Now Playing.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
