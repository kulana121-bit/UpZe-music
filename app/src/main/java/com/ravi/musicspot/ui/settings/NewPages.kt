package com.ravi.musicspot.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ravi.musicspot.Graph
import com.ravi.musicspot.ai.AiMix
import com.ravi.musicspot.logic.ListenStats
import com.ravi.musicspot.playback.SponsorBlock
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.screens.LocalNav
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- Playback extras (shown inside Playback) --------------------------------------------------------------------

@Composable
fun PlaybackExtrasGroups() {
    val s by Graph.settings.state.collectAsState()
    val ctx = LocalContext.current
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Graph.settings.update { it.copy(playOnBluetooth = true) } else Pills.show("Bluetooth permission is needed to notice your headphones")
    }
    Group("Smart playback") {
        SwitchRow("Play when Bluetooth connects", "Headphones, earbuds, speaker or car: your last queue resumes on its own", s.playOnBluetooth) { on ->
            if (on && Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else Graph.settings.update { it.copy(playOnBluetooth = on) }
        }
        RowDivider()
        SwitchRow("Pause at zero volume", "Turning the volume all the way down pauses; turning it up again resumes", s.pauseOnMute) { v -> Graph.settings.update { it.copy(pauseOnMute = v) } }
        RowDivider()
        SwitchRow("Skip songs that fail", "If a song still can't play after the automatic retries, go on to the next one (up to 5 in a row)", s.skipOnError) { v -> Graph.settings.update { it.copy(skipOnError = v) } }
    }
    Group("SponsorBlock", footer = "Community-marked parts of YouTube songs are jumped over. Lookups are anonymous (only a 4-character hash prefix is sent to sponsor.ajay.app).") {
        SwitchRow("Skip marked segments", "Works on songs played from YouTube Music", s.sponsorBlock) { v -> Graph.settings.update { it.copy(sponsorBlock = v) } }
        if (s.sponsorBlock) SponsorBlock.CATEGORIES.forEach { (id, label) ->
            RowDivider()
            SwitchRow(label, null, id in s.sponsorCategories) { on ->
                Graph.settings.update { it.copy(sponsorCategories = if (on) it.sponsorCategories + id else it.sponsorCategories - id) }
            }
        }
    }
}

// ---- Privacy ----------------------------------------------------------------------------------------------------

@Composable
fun PrivacyPage() {
    val s by Graph.settings.state.collectAsState()
    SettingsScaffold("Privacy") {
        item(key = "newpages-1", contentType = "static") {
            Group("Incognito", footer = "While on, songs you play aren't added to history, listen stats, On repeat, cached songs or recent searches. Music still plays and lyrics still load.") {
                SwitchRow("Incognito mode", if (s.incognito) "On: nothing you play is remembered" else "Off", s.incognito) { v ->
                    Graph.settings.update { it.copy(incognito = v) }; Pills.show(if (v) "Incognito on" else "Incognito off")
                }
            }
        }
        item(key = "newpages-2", contentType = "static") {
            Group("Screen", footer = "Screenshots and screen recording show a black screen, and UpZe is blank in Recents.") {
                SwitchRow("Block screenshots", null, s.blockScreenshots) { v -> Graph.settings.update { it.copy(blockScreenshots = v) } }
            }
        }
        item(key = "newpages-3", contentType = "static") {
            var confirm by remember { mutableStateOf(false) }
            Group("Clear") {
                NavRow(null, "Clear listen stats", "Play counts, On repeat and the stats pages start from zero") { confirm = true }
                RowDivider()
                NavRow(null, "Clear recent searches", null) { Graph.settings.update { it.copy(recentSearches = emptyList()) }; Pills.show("Recent searches cleared") }
            }
            com.ravi.musicspot.ui.motion.MotionAlertDialog(visible = confirm, onDismissRequest = { confirm = false }, title = { Text("Clear listen stats?") },
                confirmButton = { TextButton(onClick = { Graph.playStats.clear(); confirm = false; Pills.show("Listen stats cleared") }) { Text("Clear") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
        }
    }
}

// ---- AI Integration ---------------------------------------------------------------------------------------------

@Composable
fun AiPage() {
    val s by Graph.settings.state.collectAsState()
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val preset = AiMix.preset(s.aiPreset)
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(AiMix.hasKey(ctx)) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var mood by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(25) }
    var working by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<AiMix.Result?>(null) }

    SettingsScaffold("AI Integration") {
        item(key = "newpages-4", contentType = "static") {
            Group("Provider", footer = "Any OpenAI-compatible API works. Pick Custom and paste the base URL for others. Only song and artist names are sent to the provider; your key is stored encrypted on this phone and is never in backups.") {
                ChoiceRow(null, AiMix.PRESETS, preset, { it.label }) { p -> Graph.settings.update { it.copy(aiPreset = p.id, aiBaseUrl = p.baseUrl, aiModel = p.model) }; status = null }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.aiBaseUrl, { v -> Graph.settings.update { it.copy(aiBaseUrl = v.trim()) } }, singleLine = true,
                        label = { Text("Base URL") }, placeholder = { Text(preset.baseUrl.ifBlank { "https://…/v1" }) }, shape = Ms.r12, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(s.aiModel, { v -> Graph.settings.update { it.copy(aiModel = v.trim()) } }, singleLine = true,
                        label = { Text("Model") }, placeholder = { Text(preset.model.ifBlank { "model name" }) }, shape = Ms.r12, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it }, singleLine = true, label = { Text(if (hasKey) "API key (saved, type to replace)" else "API key") },
                        placeholder = { Text(preset.keyHint) }, shape = Ms.r12, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide" else "Show") } })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            if (key.isNotBlank()) { AiMix.saveKey(ctx, key); key = ""; hasKey = true }
                            testing = true; status = null
                            scope.launch {
                                status = runCatching { AiMix.test(ctx) }.fold({ "Connected · ${preset.label} answered “$it”" }, { "Not working: ${it.message}" })
                                testing = false
                            }
                        }, enabled = !testing && (hasKey || key.isNotBlank())) { Text(if (key.isNotBlank()) "Save & test" else "Test") }
                        if (hasKey) TextButton(onClick = { AiMix.saveKey(ctx, null); hasKey = false; status = "Key removed" }) { Text("Remove key") }
                        if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                }
            }
        }
        item(key = "newpages-5", contentType = "static") {
            Group("AI mix", footer = "Builds a playlist from your top artists, most played and liked songs, plus new songs that match. It's saved to Library like any playlist.") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(mood, { mood = it.take(80) }, singleLine = true, label = { Text("Mood or idea (optional)") },
                        placeholder = { Text("e.g. late-night drive, Punjabi workout, rainy chill") }, shape = Ms.r12, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Chill", "Workout", "Romantic", "Party", "Focus", "Old classics").forEach { m ->
                            Chip(m, mood.equals(m, true)) { mood = if (mood.equals(m, true)) "" else m }
                        }
                    }
                    ChoiceRow("Songs", listOf(15, 25, 40), count, { "$it" }) { count = it }
                    Button(onClick = {
                        made = null; working = "Starting…"
                        scope.launch {
                            runCatching { AiMix.makeMix(ctx, mood, count) { step -> working = step } }
                                .onSuccess { made = it; Pills.show("${it.name} saved") }
                                .onFailure { Pills.show(it.message ?: "AI mix failed"); status = "Mix failed: ${it.message}" }
                            working = null
                        }
                    }, enabled = working == null && (hasKey || key.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Make my mix")
                    }
                    working?.let { w -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(w, style = MaterialTheme.typography.bodySmall) } }
                    made?.let { r ->
                        Text("${r.name}: found ${r.found.size} of ${r.asked} songs", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = { Graph.player.play(r.found) }) { Text("Play") }
                            OutlinedButton(onClick = { nav.push(Route.LocalPlaylist(r.playlistId)) }) { Text("Open playlist") }
                        }
                    }
                    if (!hasKey && key.isBlank()) Text("Add an API key above first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

// ---- Listen stats -----------------------------------------------------------------------------------------------

@Composable
fun ListenStatsPage() {
    val data by Graph.playStats.data.collectAsState()
    var period by remember { mutableStateOf(ListenStats.Period.WEEK) }
    var tab by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<ListenStats.Report?>(null) }
    LaunchedEffect(data, period) { report = withContext(Dispatchers.Default) { ListenStats.report(data, period) } }
    val reduced = reducedMotion()

    SettingsScaffold("Listen stats") {
        item(key = "newpages-6", contentType = "static") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ListenStats.Period.entries.forEach { p -> Chip(p.label, p == period) { period = p } }
            }
        }
        val r = report
        if (r == null) item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (r.plays == 0) item { EmptyState(Icons.Rounded.AutoAwesome, "Nothing yet for ${period.label.lowercase()}", "Songs count after about 30 seconds of listening. Come back after some music!") }
        else {
            item(key = "hero") {
                AnimatedContent(r, transitionSpec = {
                    if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 6 }) togetherWith fadeOut(tween(160))
                }, contentKey = { it.period }, label = "statsHero") { rep -> Hero(rep, reduced) }
            }
            item(key = "chart") { Chart(r, reduced) }
            item(key = "tabs") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Songs", "Artists", "Albums").forEachIndexed { i, t -> Chip(t, tab == i) { tab = i } }
                }
            }
            when (tab) {
                0 -> r.songs.take(30).forEachIndexed { i, row ->
                    item(key = "s-${period}-${row.track.id}") {
                        RankRow(i, row.track.artUrl, row.track.title, row.track.artist, row.plays, r.songs.first().plays, reduced, Ms.r12,
                            onClick = { Graph.player.play(r.songs.map { it.track }, i) }, onLong = { Menus.track = row.track })
                    }
                }
                1 -> r.artists.take(30).forEachIndexed { i, a ->
                    item(key = "a-${period}-${a.name}") {
                        RankRow(i, a.art, a.name, if (a.songs == 1) "1 song" else "${a.songs} songs", a.plays, r.artists.first().plays, reduced, CircleShape,
                            onClick = { Graph.player.play(r.songs.filter { ListenStats.mainArtist(it.track.artist) == a.name }.map { it.track }) }, onLong = null)
                    }
                }
                else -> if (r.albums.isEmpty()) item { EmptyState(Icons.Rounded.AutoAwesome, "No albums yet", "Songs with album info show up here.") }
                    else r.albums.take(30).forEachIndexed { i, a ->
                        item(key = "al-${period}-${a.name}-${a.artist}") {
                            RankRow(i, a.art, a.name, a.artist, a.plays, r.albums.first().plays, reduced, Ms.r12,
                                onClick = { Graph.player.play(r.songs.filter { it.track.album?.trim().equals(a.name, true) }.map { it.track }) }, onLong = null)
                        }
                    }
            }
        }
    }
}

@Composable
private fun Hero(r: ListenStats.Report, reduced: Boolean) {
    val top = r.artists.firstOrNull()
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(c.primary.copy(alpha = 0.55f), c.tertiary.copy(alpha = 0.35f), c.surfaceContainerLow)))) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(r.period.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = c.onSurface.copy(alpha = 0.8f))
            Row(verticalAlignment = Alignment.Bottom) {
                CountUp(r.minutes, reduced, MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black))
                Text("  minutes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Stat("${r.plays}", "plays"); Stat("${r.songs.size}", "songs"); Stat("${r.artists.size}", "artists"); Stat("${r.activeDays}", "days")
            }
            top?.let { a ->
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(a.art, Modifier.size(52.dp), CircleShape)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Your top artist", style = MaterialTheme.typography.labelMedium, color = c.onSurface.copy(alpha = 0.75f))
                        Text(a.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            r.songs.firstOrNull()?.let { s -> Text("On repeat: ${s.track.title} · ${s.plays} plays", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp), color = c.onSurface.copy(alpha = 0.85f)) }
        }
    }
}


@Composable
private fun Stat(value: String, label: String) {
    Column { Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun CountUp(target: Long, reduced: Boolean, style: androidx.compose.ui.text.TextStyle) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { if (reduced) anim.snapTo(target.toFloat()) else { anim.snapTo(0f); anim.animateTo(target.toFloat(), tween(900, easing = FastOutSlowInEasing)) } }
    Text("%,d".format(anim.value.toLong()), style = style)
}

@Composable
private fun Chart(r: ListenStats.Report, reduced: Boolean) {
    val max = (r.chart.maxOrNull() ?: 0).coerceAtLeast(1)
    val c = MaterialTheme.colorScheme
    val n = r.chart.size
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(Ms.r20).background(c.surfaceContainerLow).padding(16.dp)) {
        Text(when (r.period) { ListenStats.Period.YEAR -> "Plays per month"; ListenStats.Period.ALL -> "Plays per year"; else -> "Plays per day" },
            style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(if (n > 14) 2.dp else 6.dp), verticalAlignment = Alignment.Bottom) {
            r.chart.forEachIndexed { i, v ->
                var shown by remember(r.period) { mutableStateOf(reduced) }
                LaunchedEffect(r.period) { if (!reduced) { delay(i * 18L); shown = true } }
                val h by animateFloatAsState(if (shown) v.toFloat() / max else 0f, tween(if (reduced) 0 else 520, easing = FastOutSlowInEasing), label = "bar")
                val best = v == max && v > 0
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(h.coerceAtLeast(if (v > 0) 0.04f else 0.015f)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (best) Brush.verticalGradient(listOf(c.tertiary, c.primary)) else Brush.verticalGradient(listOf(c.primary.copy(alpha = 0.75f), c.primary.copy(alpha = 0.35f)))))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(r.chartLabels.firstOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(r.chartLabels.lastOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun RankRow(i: Int, art: String?, title: String, sub: String, plays: Int, top: Int, reduced: Boolean, shape: androidx.compose.ui.graphics.Shape,
                    onClick: () -> Unit, onLong: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { if (!reduced) { delay((i.coerceAtMost(12)) * 35L); shown = true } }
    val p by animateFloatAsState(if (shown) 1f else 0f, tween(if (reduced) 0 else 420, easing = FastOutSlowInEasing), label = "row")
    Row(Modifier.fillMaxWidth().graphicsLayer { alpha = p; translationY = (1f - p) * 24f }
        .combinedClick(onClick, onLong).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (i < 3) c.primary else c.onSurfaceVariant, modifier = Modifier.width(30.dp))
        Artwork(art, Modifier.size(48.dp), shape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.surfaceContainerHighest)) {
                Box(Modifier.fillMaxWidth(p * plays / top.coerceAtLeast(1).toFloat()).height(4.dp).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(c.primary, c.tertiary))))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("$plays", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClick(onClick: () -> Unit, onLong: (() -> Unit)?): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))
