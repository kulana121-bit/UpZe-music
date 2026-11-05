package com.ravi.musicspot.ui.player

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.clip
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import com.ravi.musicspot.ui.glass.glass
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.ravi.musicspot.ui.motion.scroll3d
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ravi.musicspot.Graph
import com.ravi.musicspot.dsp.EnhanceLevel
import com.ravi.musicspot.lyrics.Lyrics
import com.ravi.musicspot.lyrics.Romanizer
import com.ravi.musicspot.lyrics.activeIndex
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.EngineAudioProcessor
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.components.CollectionCard
import com.ravi.musicspot.ui.components.EmptyState
import com.ravi.musicspot.ui.components.HorizontalShelf
import com.ravi.musicspot.ui.components.Loading
import com.ravi.musicspot.ui.components.SectionHeader
import com.ravi.musicspot.ui.components.TrackCard
import com.ravi.musicspot.ui.components.TrackRow
import com.ravi.musicspot.ui.components.formatTime
import com.ravi.musicspot.ui.glass.glassSheet
import com.ravi.musicspot.ui.motion.rememberHaptics
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun QueuePane() {
    val st by Graph.player.state.collectAsState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = st.index.coerceAtLeast(0))
    var dragging by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 68.dp.toPx() }
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next · ${(st.queue.size - st.index - 1).coerceAtLeast(0)} songs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { Graph.player.clearUpcoming() }, enabled = st.index < st.queue.lastIndex) { Text("Clear") }
        }
        // v2.0.5 Infinite play: persisted (AppSettings.autoplay, default on).
        val settings by Graph.settings.state.collectAsState()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Rounded.AllInclusive, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Infinite play", style = MaterialTheme.typography.labelLarge)
                Text("Adds similar songs you'll like when the queue is ending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = settings.autoplay, onCheckedChange = { on -> haptics.tick(); Graph.settings.update { it.copy(autoplay = on) } })
        }
        Text("Long-press the handle and drag to reorder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        // v2.0.7b: the playing song and the next three sit as a flat band in the middle of the list; rows above and
        // below tilt away in 3D. The band glides (slow, smooth) to the centre whenever the song changes.
        val stillQ = com.ravi.musicspot.ui.motion.reducedMotion()
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val vhPx = constraints.maxHeight.toFloat()
        // v2.0.11: no big empty gap above the list; the band still glides towards the centre when it can.
        val bandPad = 8.dp
        val flatBand = if (vhPx > 0) ((2f * rowPx) / (vhPx / 2f)).coerceIn(0f, 0.9f) else 0.4f
        LaunchedEffect(st.index) {
            val target = st.index.coerceAtLeast(0)
            if (list.layoutInfo.visibleItemsInfo.none { it.index == target }) list.scrollToItem(target)
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return@LaunchedEffect
            // Centre of the 4-song band (current + next 3) to the viewport centre.
            val bandCentre = item.offset + 2f * item.size
            val d = bandCentre - (info.viewportStartOffset + info.viewportEndOffset) / 2f
            if (stillQ) list.scrollBy(d) else list.animateScrollBy(d, androidx.compose.animation.core.tween(1120, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = bandPad)) {
            itemsIndexed(st.queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
                if (i == st.autoplayFrom) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
                    Text("  Autoplay  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
                }
                val isDragged = i == dragging
                Box(Modifier.zIndex(if (isDragged) 1f else 0f).then(if (dragging < 0) Modifier.scroll3d(list, i, maxTilt = 26f, minScale = 0.88f, minAlpha = 0.5f, flat = flatBand) else Modifier).graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 12f else 0f }
                    .background(if (isDragged) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)) {
                    TrackRow(t, onClick = { Graph.player.skipTo(i) }, playing = i == st.index, trailing = {
                        if (i != st.index) IconButton(onClick = { Graph.player.remove(i) }) { Icon(Icons.Rounded.Close, "Remove ${t.title} from queue") }
                        Icon(Icons.Rounded.DragHandle, "Reorder ${t.title}", Modifier.size(48.dp).padding(12.dp).pointerInput(i, st.queue.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = i; dragOffset = 0f; haptics.confirm() },
                                onDragEnd = {
                                    // v2.0.14: guard empty queue (coerceIn(0, -1) throws).
                                    // v2.0.16: queue can shrink mid-drag; don't move a row that no longer exists.
                                    if (dragging in st.queue.indices) {
                                        val to = (dragging + (dragOffset / rowPx).roundToInt()).coerceIn(0, st.queue.lastIndex)
                                        if (to != dragging) Graph.player.move(dragging, to)
                                    }
                                    dragging = -1; dragOffset = 0f
                                },
                                onDragCancel = { dragging = -1; dragOffset = 0f },
                            ) { change, amount -> change.consume(); dragOffset += amount.y }
                        })
                    })
                }
            }
        }
        }
    }
}

@Composable
fun LyricsPane() {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    val settings by Graph.settings.state.collectAsState()
    var force by remember(t.id) { mutableIntStateOf(0) }
    val lyrics by produceState<Result<Lyrics?>?>(null, t.id, force) { value = null; value = runCatching { Graph.lyricsFor(t, force > 0) } }
    val pos by Graph.player.position.collectAsState()
    when (val r = lyrics) {
        null -> Loading()
        else -> {
            val l = r.getOrNull()
            when {
                r.isFailure -> EmptyState(Icons.Rounded.Lyrics, "Lyrics unavailable", r.exceptionOrNull()?.message ?: "Couldn't reach the lyrics services.", "Try again") { force++ }
                l == null -> EmptyState(Icons.Rounded.Lyrics, "No lyrics found", "None of your lyrics providers has this song. You can change providers in Settings → Lyrics.", "Search again") { force++ }
                l is Lyrics.Plain -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text(l.text, style = MaterialTheme.typography.titleMedium, lineHeight = MaterialTheme.typography.titleLarge.lineHeight)
                    Spacer(Modifier.height(16.dp))
                    Text("Lyrics: ${l.source} (not time-synced)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                l is Lyrics.Synced -> SyncedLyrics(l, pos + settings.lyricsOffsetMs, settings.romanize)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(l: Lyrics.Synced, posMs: Long, romanize: Boolean) {
    val list = rememberLazyListState()
    val active = l.lines.activeIndex(posMs)
    val scope = rememberCoroutineScope()
    val roman = remember(l, romanize) { if (romanize && Romanizer.available) Romanizer.lines(l.lines.map { it.text }) else emptyList() }
    val stillScroll = com.ravi.musicspot.ui.motion.reducedMotion()
    LaunchedEffect(active) { if (active >= 0) scope.launch {
        // v2.0.7b: the playing line glides to the exact vertical centre (slow, smooth ease), not the top.
        fun centreDelta(): Float? {
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == active } ?: return null
            val viewCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            return item.offset + item.size / 2f - viewCentre
        }
        if (centreDelta() == null) list.scrollToItem(active)
        val d = centreDelta() ?: return@launch
        if (stillScroll) list.scrollBy(d)
        else list.animateScrollBy(d, androidx.compose.animation.core.tween(1260, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)))
    } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${l.source}${if (l.lines.any { it.wordSynced }) " · word-synced" else " · line-synced"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { Graph.settings.update { it.copy(lyricsOffsetMs = it.lyricsOffsetMs - 250) } }) { Text("−¼s") }
            TextButton(onClick = { Graph.settings.update { it.copy(lyricsOffsetMs = it.lyricsOffsetMs + 250) } }) { Text("+¼s") }
            if (Romanizer.available) IconButton(onClick = { Graph.settings.update { it.copy(romanize = !it.romanize) } }) {
                Icon(Icons.Rounded.Translate, if (romanize) "Hide romanisation" else "Show romanisation", tint = if (romanize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Half-viewport padding so the first and last lines can also sit in the centre.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = maxHeight / 2)) {
            itemsIndexed(l.lines) { i, line ->
                val isActive = i == active
                val color by animateColorAsState(if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (i < active) 0.55f else 0.75f), androidx.compose.animation.core.tween(980), label = "lc")
                // v2.0.2: the active line grows in with a soft spring; past/future lines settle back (off with Reduce motion).
                val still = com.ravi.musicspot.ui.motion.reducedMotion()
                val scale by androidx.compose.animation.core.animateFloatAsState(if (isActive || still) 1f else 0.94f,
                    androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 56f), label = "ls")
                Column(Modifier.fillMaxWidth().scroll3d(list, i, focus = 0.5f, maxTilt = 30f, minScale = 0.9f, minAlpha = 0.35f).graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.clickable(onClickLabel = "Seek to this line") { Graph.player.seek((line.startMs - Graph.settings.value.lyricsOffsetMs).coerceAtLeast(0)) }.padding(vertical = 8.dp)) {
                    if (isActive && line.wordSynced) {
                        Text(buildAnnotatedString {
                            line.words.forEach { w -> withStyle(SpanStyle(color = if (w.startMs <= posMs) MaterialTheme.colorScheme.primary else color)) { append(w.text) } }
                        }, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 34.sp), fontWeight = FontWeight.ExtraBold)
                    } else Text(line.text, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 34.sp), fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold, color = color)
                    roman.getOrNull(i)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    line.background?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        }
    }
}

@Composable
fun RelatedPane(onNavigate: (Route) -> Unit) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    val data by produceState<Result<List<com.ravi.musicspot.model.Shelf>>?>(null, t.id) {
        value = runCatching {
            if (t.source == SourceKind.YOUTUBE) Graph.yt.related(t.nativeId)
            else {
                val match = Graph.yt.search("${t.title} ${t.artist}", 5).let { com.ravi.musicspot.source.TrackMatcher.best(t, it) }
                match?.let { listOf(com.ravi.musicspot.model.Shelf("Radio from this song", Graph.yt.radio(it.nativeId))) } ?: emptyList()
            }
        }
    }
    when (val r = data) {
        null -> Loading()
        else -> {
            val shelves = r.getOrDefault(emptyList())
            if (shelves.isEmpty()) EmptyState(Icons.Rounded.Lyrics, "Nothing related yet", if (r.isFailure) "Couldn't reach YouTube Music." else "YouTube Music has no related picks for this song.")
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                shelves.forEach { s ->
                    item { SectionHeader(s.title) }
                    if (s.tracks.isNotEmpty()) item { HorizontalShelf(s.tracks, { it.id }) { x -> TrackCard(x, 128.dp, onClick = { Graph.player.playNext(x); Graph.player.next() }) } }
                    if (s.collections.isNotEmpty()) item {
                        HorizontalShelf(s.collections, { it.id }) { c ->
                            CollectionCard(c, 128.dp) { onNavigate(if (c.kind == com.ravi.musicspot.model.Collection.Kind.ARTIST) Route.Artist(c.id, c.title) else Route.Remote(c)) }
                        }
                    }
                }
            }
        }
    }
}

/** Real credits: catalogue metadata, the resolved stream as decoded, and source-provided roles (JioSaavn). */
@Composable
fun CreditsPane(t: Track) {
    val stream by Playback.stream.collectAsState()
    val engine by EngineAudioProcessor.status.collectAsState()
    val roles by produceState<List<Pair<String, String>>>(emptyList(), t.id) {
        value = runCatching {
            when (t.source) {
                SourceKind.JIOSAAVN -> Graph.jiosaavn.credits(t)
                else -> {
                    // A JioSaavn match of the same recording carries real role credits (singer, music, lyricist).
                    if (SourceKind.JIOSAAVN in Graph.settings.value.enabledSources) {
                        val m = Graph.jiosaavn.search("${t.title} ${t.artist}", 6).let { com.ravi.musicspot.source.TrackMatcher.best(t, it, 0.8) }
                        m?.let { Graph.jiosaavn.credits(it).map { (k, v) -> "$k (via JioSaavn)" to v } } ?: emptyList()
                    } else emptyList()
                }
            }
        }.getOrDefault(emptyList())
    }
    val s = stream?.takeIf { it.trackId == t.id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CreditRow("Title", t.title)
        CreditRow("Artist", t.artist.ifBlank { "—" })
        t.album?.let { CreditRow("Album", it) }
        t.year?.let { CreditRow("Year", it) }
        if (t.durationMs > 0) CreditRow("Length", formatTime(t.durationMs))
        t.isrc?.let { CreditRow("ISRC", it) }
        roles.forEach { (k, v) -> CreditRow(k, v) }
        Text("STREAM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        if (s == null) Text("Not playing this song yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) else {
            CreditRow("Source", s.info.source.label)
            CreditRow("Codec", s.info.codec ?: "Unknown")
            s.info.bitrateKbps?.let { CreditRow("Bitrate", "$it kbps") }
            s.info.sampleRateHz?.let { CreditRow("Sample rate", "%.1f kHz".format(it / 1000.0)) }
            CreditRow("Lossless", if (s.lossless) (if (s.hiRes) "Yes (hi-res)" else "Yes") else "No (lossy source)")
            if (s.failovers.isNotEmpty()) CreditRow("Tried first", s.failovers.joinToString("\n"))
        }
        Text("SOUND ENGINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        CreditRow("EQ", if (engine.eqActive) "On" else "Flat / off")
        CreditRow("Enhance", buildString {
            append(engine.badge ?: "Off")
            if (engine.bwe != EnhanceLevel.OFF) engine.detectedCutoffHz?.let { append(" · source cutoff ≈ ${it / 1000} kHz") }
            if (engine.disabledForCpu) append(" · paused (CPU)")
        })
        Text("Upscaling and enhancement can't restore data a lossy codec removed, so they never make a stream \"lossless\".",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreditRow(k: String, v: String) {
    Column { Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyLarge) }
}

/** Credits & stream info (quality/source) sheet. v2.0.6: MotionSheet (spring up, scrim fade, reverses on close). */
@Composable
fun CreditsSheet() = com.ravi.musicspot.ui.motion.MotionSheet(Menus.credits, onDismiss = { Menus.credits = null }) { t ->
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(Modifier.fillMaxWidth().height(560.dp).glassSheet(Ms.r28).navigationBarsPadding()) { CreditsPane(t) }
    }
}

/** Sleep timer sheet, shown while [visible]. v2.0.6: MotionSheet (spring up, scrim fade, reverses on close). */
@Composable
fun SleepSheet(visible: Boolean, onDismiss: () -> Unit) = com.ravi.musicspot.ui.motion.MotionSheet(if (visible) Unit else null, onDismiss = onDismiss) {
    val tick by Playback.sleepTick.collectAsState()
    val settings by Graph.settings.state.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().glassSheet(Ms.r28).navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
            if (tick >= 0 && Graph.player.sleepActive) {
                Text("Active: ${sleepLabelPublic()}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { Graph.player.sleepAdjust(-5) }, label = { Text("−5 min") })
                    AssistChip(onClick = { Graph.player.sleepAdjust(5) }, label = { Text("+5 min") })
                    AssistChip(onClick = { Graph.player.sleepCancel(); onDismiss() }, label = { Text("Turn off") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(10, 15, 30, 45, 60) + settings.sleepDefaultMin).distinct().sorted().forEach { m -> AssistChip(onClick = { Graph.player.sleepFor(m); onDismiss() }, label = { Text("$m min") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { Graph.player.sleepEndOfTrack(); onDismiss() }, label = { Text("End of song") })
                AssistChip(onClick = { Graph.player.sleepEndOfQueue(); onDismiss() }, label = { Text("End of queue") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(settings.sleepFade, { Graph.settings.update { it.copy(sleepFade = !it.sleepFade) } }, label = { Text("Fade out (last 30 s)") })
                FilterChip(settings.sleepFinishSong, { Graph.settings.update { it.copy(sleepFinishSong = !it.sleepFinishSong) } }, label = { Text("Finish the song") })
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}

fun sleepLabelPublic(): String {
    val s = Playback.sleep
    return when (s.mode) {
        com.ravi.musicspot.logic.SleepTimer.Mode.DURATION -> com.ravi.musicspot.logic.SleepTimer.format(s.remainingMs(System.currentTimeMillis())) + " left"
        com.ravi.musicspot.logic.SleepTimer.Mode.END_OF_TRACK -> "at the end of this song"
        com.ravi.musicspot.logic.SleepTimer.Mode.END_OF_QUEUE -> "at the end of the queue"
        else -> "off"
    }
}

@Suppress("unused") private fun overflow() = TextOverflow.Ellipsis

/**
 * v2.0.7 live lyrics capsule on Now Playing: for line- or word-synced lyrics, the current line plays in a small
 * glass capsule (words light up as they're sung when word-synced); each new line rolls up into place. Tap opens
 * the full lyrics pane. Hidden when the song has no synced lyrics.
 */
@Composable
fun LyricsCapsule(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    val settings by Graph.settings.state.collectAsState()
    val lyrics by produceState<Lyrics?>(null, t.id) { value = null; value = runCatching { Graph.lyricsFor(t, false) }.getOrNull() }
    val l = lyrics as? Lyrics.Synced ?: return
    val pos by Graph.player.position.collectAsState()
    val posMs = pos + settings.lyricsOffsetMs
    val active = l.lines.activeIndex(posMs)
    val line = l.lines.getOrNull(active)
    val still = com.ravi.musicspot.ui.motion.reducedMotion()
    val c = MaterialTheme.colorScheme
    androidx.compose.animation.AnimatedVisibility(
        visible = line != null && line.text.isNotBlank(),
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(308)) + androidx.compose.animation.scaleIn(com.ravi.musicspot.ui.motion.MotionSpec.popSpring(), initialScale = 0.85f),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(224)) + androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(224), targetScale = 0.9f),
        modifier = modifier,
    ) {
        Box(
            Modifier.padding(horizontal = 24.dp, vertical = 6.dp).clip(com.ravi.musicspot.ui.theme.Ms.pill)
                .glass(com.ravi.musicspot.ui.theme.Ms.pill).clickable(onClickLabel = "Open lyrics", onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedContent(active, label = "capsuleLine", transitionSpec = {
                if (still) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(168)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(112))
                else (androidx.compose.animation.slideInVertically(com.ravi.musicspot.ui.motion.MotionSpec.surfaceSpring(IntOffset.VisibilityThreshold)) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280))) togetherWith
                    (androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(252)) { -it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(196)))
            }) { i ->
                val ln = l.lines.getOrNull(i) ?: return@AnimatedContent
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lyrics, null, Modifier.size(14.dp), tint = c.primary)
                    Spacer(Modifier.width(8.dp))
                    if (ln.wordSynced && i == active) Text(buildAnnotatedString {
                        ln.words.forEach { w -> withStyle(SpanStyle(color = if (w.startMs <= posMs) c.primary else c.onSurface.copy(alpha = 0.6f))) { append(w.text) } }
                    }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    else Text(ln.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
