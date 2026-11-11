package com.ravi.musicspot.ui.player

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Explore
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
import com.ravi.musicspot.lyrics.instrumentalGaps
import com.ravi.musicspot.lyrics.introGap
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlin.math.roundToInt

@Composable
fun QueuePane() {
    val st by Graph.player.state.collectAsState()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = st.index.coerceAtLeast(0))
    var dragging by remember { mutableIntStateOf(-1) }
    // 1.0.1: a finger on a row's reorder handle: its long-press drags, so the row's long-press menu must not open.
    var handleDown by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 68.dp.toPx() }
    val haptics = rememberHaptics()
    val clock = LocalPaneEntrance.current // 1.0.2: cascade clock from the full-screen pane (1 elsewhere)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp).cascade(clock, 2f), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next · ${(st.queue.size - st.index - 1).coerceAtLeast(0)} songs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            // 1.0.10: save the whole queue (every track, in order) through the existing Add to playlist dialog.
            IconButton(onClick = { Menus.addToPlaylist = st.queue.toList() }, enabled = st.queue.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Save queue as playlist")
            }
            TextButton(onClick = { Graph.player.clearUpcoming() }, enabled = st.index < st.queue.lastIndex) { Text("Clear") }
        }
        // v2.0.5 Infinite play: persisted (AppSettings.autoplay, default on).
        val settings by Graph.settings.state.collectAsState()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp).cascade(clock, 2.6f), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Rounded.AllInclusive, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Infinite play", style = MaterialTheme.typography.labelLarge)
                Text("Keeps about 20 similar songs lined up. Duplicates leave the queue; played songs stay above the one playing.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = settings.autoplay, onCheckedChange = { on -> haptics.tick(); Graph.settings.update { it.copy(autoplay = on) } })
        }
        Text("Long-press the handle and drag to reorder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp).cascade(clock, 3f))
        // v2.0.7b: the playing song and the next three sit as a flat band in the middle of the list; rows above and
        // below tilt away in 3D. The band glides (slow, smooth) to the centre whenever the song changes.
        val stillQ = com.ravi.musicspot.ui.motion.reducedMotion()
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val vhPx = constraints.maxHeight.toFloat()
        // v2.0.11: no big empty gap above the list; the band still glides towards the centre when it can.
        val bandPad = 8.dp
        val flatBand = if (vhPx > 0) ((2f * rowPx) / (vhPx / 2f)).coerceIn(0f, 0.9f) else 0.4f
        // 1.0.7 (Raj): the playing song glides to the TOP of the list; played songs sit above it (scroll up to see
        // them) and the upcoming ones follow. The flat 3D band now starts at the playing song.
        LaunchedEffect(st.index) {
            val target = st.index.coerceAtLeast(0)
            if (target >= st.queue.size) return@LaunchedEffect
            if (list.layoutInfo.visibleItemsInfo.none { it.index == target }) { list.scrollToItem(target); return@LaunchedEffect }
            if (stillQ) list.scrollToItem(target)
            else {
                val item = list.layoutInfo.visibleItemsInfo.first { it.index == target }
                list.animateScrollBy((item.offset - list.layoutInfo.viewportStartOffset).toFloat(),
                    androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)))
            }
        }
        // 1.0.7 smoothness: keys no longer contain the row index ("$i-id"), which changed EVERY row's key whenever a
        // song was added/removed/moved above it (all rows torn down and rebuilt = the hitch). Now id + occurrence.
        val rowKeys = remember(st.queue) { val seen = HashMap<String, Int>(); st.queue.map { t -> val n = seen.merge(t.id, 1, Int::plus)!!; if (n == 1) t.id else "${t.id}#$n" } }
        // 1.0.7: the open cascade counts from the row that was first visible when the pane opened; reading
        // list.firstVisibleItemIndex here recomposed every row on every scroll step.
        val openFirst = remember { list.firstVisibleItemIndex }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = bandPad)) {
            itemsIndexed(st.queue, key = { i, _ -> rowKeys.getOrElse(i) { "q$i" } }, contentType = { _, _ -> "queueTrack" }) { i, t -> // 1.0.1: contentType
                if (i == st.autoplayFrom) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
                    Text("  Autoplay  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
                }
                val isDragged = i == dragging
                val isNow = i == st.index
                val played = i < st.index
                // 1.0.2: rows cascade in from the first visible one, 45 ms apart, when the pane opens.
                Box(Modifier.cascade(clock, 3.5f + (i - openFirst).coerceIn(0, 9).toFloat()).zIndex(if (isDragged) 1f else 0f).then(if (dragging < 0) Modifier.scroll3d(list, i, focus = 0.08f /* 1.0.7: focus line at the playing song, now at the top */, maxTilt = 26f, minScale = 0.88f, minAlpha = 0.5f, flat = flatBand, flatRows = com.ravi.musicspot.ui.motion.Depth.FLAT_TRACKS,
                    below = 0.3f /* 1.0.8: much softer tilt for the upcoming songs at the bottom (≈8°, 96% size, 85% opacity at the edge) */) else Modifier).graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 12f else 0f }
                    .background(if (isDragged) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                    .then(if (isNow) Modifier.nowPlayingHighlight() else if (played) Modifier.graphicsLayer { alpha = 0.6f } else Modifier)) {
                    TrackRow(t, onClick = { Graph.player.skipTo(i) }, playing = i == st.index, onLongPress = { if (!handleDown && dragging < 0) com.ravi.musicspot.ui.openSongMenu(t) }, trailing = {
                        if (i != st.index) IconButton(onClick = { Graph.player.remove(i) }) { Icon(Icons.Rounded.Close, "Remove ${t.title} from queue") }
                        Icon(Icons.Rounded.DragHandle, "Reorder ${t.title}", Modifier.size(48.dp).padding(12.dp).pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false); handleDown = true; waitForUpOrCancellation(); handleDown = false }
                        }.pointerInput(i, st.queue.size) {
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
    // 1.0.2: the position State is passed down unread, so a position tick no longer recomposes the whole lyrics list.
    // 1.0.8: a frame-accurate clock (was the raw 250 ms samples), so lines, words and letters light on time.
    val pos = rememberSmoothPosition()
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
                l is Lyrics.Synced -> SyncedLyrics(t, l, pos, settings.lyricsOffsetMs, settings.romanize)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(t: com.ravi.musicspot.model.Track, l: Lyrics.Synced, pos: androidx.compose.runtime.State<Long>, offsetMs: Int, romanize: Boolean) {
    val list = rememberLazyListState()
    // 1.0.2: only a change of active line recomposes the list; the word highlight reads the position in its own scope.
    val activeState = remember(l, offsetMs) { androidx.compose.runtime.derivedStateOf { l.lines.activeIndex(pos.value + offsetMs + LYRIC_LINE_LEAD_MS) } } // 1.0.8: switch slightly early (see LYRIC_LINE_LEAD_MS)
    val active by activeState
    // 1.0.2: instrumental stretches (intro, beat, solo) show bouncing "• • •" instead of a lit line.
    val gaps = remember(l) { listOfNotNull(l.lines.introGap()) + l.lines.instrumentalGaps() }
    val gapState = remember(gaps, offsetMs) { androidx.compose.runtime.derivedStateOf { (pos.value + offsetMs + LYRIC_LINE_LEAD_MS).let { p -> gaps.firstOrNull { p in it } } } }
    val openActive = remember(l) { activeState.value.coerceAtLeast(0) } // 1.0.7: cascade origin, read once
    val typo = MaterialTheme.typography.headlineMedium
    val glowColor = MaterialTheme.colorScheme.primary
    val baseStyle = remember(typo) { typo.copy(fontSize = 26.sp, lineHeight = 34.sp) }
    // 1.0.8 lighter glow: 14 px (was 28 px); the shadow is drawn every frame the letter sweep moves.
    val glowStyle = remember(baseStyle, glowColor) { baseStyle.copy(shadow = androidx.compose.ui.graphics.Shadow(glowColor.copy(alpha = 0.5f), blurRadius = 14f)) }
    val clock = LocalPaneEntrance.current
    val glow = !com.ravi.musicspot.Graph.liteFx
    val scope = rememberCoroutineScope()
    // 1.0.6: romanization (ICU transliteration of every line) is computed on Dispatchers.Default, or read from the
    // download's saved copy, instead of inside composition on the main thread; lines appear the moment it's ready.
    val roman by produceState(emptyList<String?>(), t.id, l, romanize) {
        value = if (romanize && Romanizer.available) runCatching { Graph.romanizedLines(t, l) }.getOrDefault(emptyList()) else emptyList()
    }
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
        // 1.0.8: 520 ms (was 1260 ms). The long glide left the new line below centre for over a second, which read as
        // the lyrics lagging behind the song.
        else list.animateScrollBy(d, androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)))
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
            itemsIndexed(l.lines, key = { i, _ -> "ly:$i" }, contentType = { _, _ -> "lyric-line" }) { i, line -> // 1.0.6: key + contentType
                // 1.0.7 smoothness: each row derives only what it shows, so a line change recomposes the two rows that
                // changed (old and new active), not every visible line; the gap slot likewise only for its own row.
                val isActive by remember(i) { androidx.compose.runtime.derivedStateOf { activeState.value == i && gapState.value?.afterIndex != i } }
                val isPast by remember(i) { androidx.compose.runtime.derivedStateOf { i < activeState.value } }
                val myGap by remember(i) { androidx.compose.runtime.derivedStateOf { gapState.value?.takeIf { it.afterIndex == i || (i == 0 && it.afterIndex == -1) } } }
                val color by animateColorAsState(if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isPast) 0.55f else 0.75f), androidx.compose.animation.core.tween(220), label = "lc") // 1.0.8: was 980 ms
                // v2.0.2: the active line grows in with a soft spring; past/future lines settle back (off with Reduce motion).
                val still = com.ravi.musicspot.ui.motion.reducedMotion()
                val scale by androidx.compose.animation.core.animateFloatAsState(if (isActive || still) 1f else 0.94f,
                    androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 260f), label = "ls") // 1.0.8: was 56
                // 1.0.2: lines ripple in outward from the playing line when the pane opens.
                Column(Modifier.fillMaxWidth().cascade(clock, 2f + kotlin.math.abs(i - openActive).coerceAtMost(9).toFloat()).scroll3d(list, i, focus = 0.5f, maxTilt = 18f /* 1.0.8 lighter drum: was 30° */, minScale = 0.92f, minAlpha = 0.35f, allowOnLite = !Graph.liteFx).graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.clickable(onClickLabel = "Seek to this line") { Graph.player.seek((line.startMs - Graph.settings.value.lyricsOffsetMs).coerceAtLeast(0)) }.padding(vertical = 8.dp)) {
                    // 1.0.2: the playing line gets a soft glow in the song colour (skipped on lite devices).
                    val lineStyle = if (isActive && glow) glowStyle else baseStyle // 1.0.7: two remembered styles, not new copies per row
                    if (i == 0) GapDotsSlot(myGap?.takeIf { it.afterIndex == -1 }, pos, offsetMs)
                    // 1.0.8: word-synced lines now light letter by letter (each word's time shared across its letters).
                    if (isActive && line.wordSynced && line.words.isNotEmpty()) LetterSyncedLine(line, pos, offsetMs + LYRIC_LETTER_LEAD_MS, color, MaterialTheme.colorScheme.primary, baseStyle, glow)
                    // 1.0.8 smoothness: one weight for every line (was Black when active, ExtraBold otherwise). The
                    // weight change re-wrapped the line and changed its height exactly while the list glided to it,
                    // which made the glide jump; the active line stands out by colour, scale and glow instead.
                    else Text(line.text, style = lineStyle, fontWeight = FontWeight.ExtraBold, color = color)
                    roman.getOrNull(i)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    line.background?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    GapDotsSlot(myGap?.takeIf { it.afterIndex == i }, pos, offsetMs)
                }
            }
        }
        }
    }
}

/** 1.0.2: expands under a line while its instrumental gap plays, and folds away when the next line starts. */
@Composable
private fun GapDotsSlot(gap: com.ravi.musicspot.lyrics.LyricGap?, pos: androidx.compose.runtime.State<Long>, offsetMs: Int) {
    var shown by remember { mutableStateOf<com.ravi.musicspot.lyrics.LyricGap?>(null) }
    if (gap != null) shown = gap
    androidx.compose.animation.AnimatedVisibility(visible = gap != null,
        enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 300f)) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(260)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))) {
        shown?.let { GapDots(it, pos, offsetMs) }
    }
}

/**
 * 1.0.2: OpenTune-style waiting dots. The three dots hop one after another and light up in turn as the gap passes,
 * then swell just before the next line. Position and the hop are read in the draw phase only (no recomposition);
 * no hop on lite devices or with Reduce motion.
 */
@Composable
private fun GapDots(gap: com.ravi.musicspot.lyrics.LyricGap, pos: androidx.compose.runtime.State<Long>, offsetMs: Int) {
    val dot = MaterialTheme.colorScheme.onSurface
    val hop = !com.ravi.musicspot.Graph.liteFx && !com.ravi.musicspot.ui.motion.reducedMotion()
    val loop = com.ravi.musicspot.ui.motion.rememberLoopPhase(running = hop, periodMs = 1200, reverse = false)
    val phase = loop.takeIf { hop }
    Row(Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { k ->
            Box(Modifier.size(12.dp).graphicsLayer {
                val p = gap.progress(pos.value + offsetMs)
                val lit = (p * 3f - k).coerceIn(0f, 1f)
                val swell = ((p - 0.9f) / 0.1f).coerceIn(0f, 1f)
                alpha = 0.25f + 0.75f * lit
                val s = 0.8f + 0.2f * lit + 0.25f * swell
                scaleX = s; scaleY = s
                phase?.let { ph ->
                    val t = ((ph.value - k * 0.14f) % 1f + 1f) % 1f
                    translationY = if (t < 0.35f) -kotlin.math.sin(t / 0.35f * Math.PI).toFloat() * 7.dp.toPx() else 0f
                }
            }.background(dot, androidx.compose.foundation.shape.CircleShape))
        }
    }
}

/** 1.0.2: the word-synced playing line; the only lyrics composable that reads the position on every tick. */
@Suppress("unused") // 1.0.8: kept for reference; the pane uses LetterSyncedLine now
@Composable
private fun ActiveWords(line: com.ravi.musicspot.lyrics.LyricLine, pos: androidx.compose.runtime.State<Long>, offsetMs: Int, rest: Color, style: androidx.compose.ui.text.TextStyle) {
    // 1.0.7 smoothness: was rebuilt and re-laid-out (with its 28 px glow) on EVERY position tick; now only when the
    // number of sung words changes, i.e. once per word.
    val sungCount by remember(line, offsetMs) { androidx.compose.runtime.derivedStateOf { val p = pos.value + offsetMs; line.words.count { it.startMs <= p } } }
    val sung = MaterialTheme.colorScheme.primary
    val text = remember(line, sungCount, sung, rest) {
        buildAnnotatedString { line.words.forEachIndexed { k, w -> withStyle(SpanStyle(color = if (k < sungCount) sung else rest)) { append(w.text) } } }
    }
    Text(text, style = style, fontWeight = FontWeight.Black)
}

/**
 * 1.0.8 letter-by-letter karaoke for the playing word-synced line. The line is laid out ONCE (two identical Texts:
 * the resting colour underneath, the sung colour on top); only the top one's clip moves, letter by letter with a soft
 * wipe through the letter being sung. The position is read in the draw phase only, so the sweep costs one clipped
 * redraw of one Text per frame: no recomposition, no re-layout, no new strings.
 */
@Composable
private fun LetterSyncedLine(
    line: com.ravi.musicspot.lyrics.LyricLine,
    pos: androidx.compose.runtime.State<Long>,
    offsetMs: Int,
    rest: Color,
    sung: Color,
    style: androidx.compose.ui.text.TextStyle,
    glow: Boolean,
) {
    val timing = remember(line) { LetterTiming.of(line) }
    var layout by remember(line) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val sungStyle = remember(style, sung, glow) {
        if (glow) style.copy(shadow = androidx.compose.ui.graphics.Shadow(sung.copy(alpha = 0.55f), blurRadius = 10f)) else style
    }
    Box {
        Text(timing.text, style = style, fontWeight = FontWeight.ExtraBold, color = rest, onTextLayout = { layout = it })
        Text(timing.text, style = sungStyle, fontWeight = FontWeight.ExtraBold, color = sung,
            modifier = Modifier.graphicsLayer { }.drawWithContent {
                val l = layout ?: return@drawWithContent
                val p = pos.value + offsetMs
                if (p >= (timing.end.lastOrNull() ?: 0L)) { drawContent(); return@drawWithContent }
                if (p <= (timing.start.firstOrNull() ?: Long.MAX_VALUE)) return@drawWithContent
                // 1.0.8 lighter sweep: find the letter being sung (letters light in order), then draw the sung copy
                // once per wrapped text line with a plain rectangle clip: every earlier line fully, the current line
                // up to the sung edge. Was a Path of one rect per letter (path clipping is the slow, aliased path on
                // the GPU) rebuilt with a bounding-box query per letter on every frame.
                val n = minOf(timing.text.length, l.layoutInput.text.length)
                var cur = -1
                for (i in 0 until n) { if (timing.progress(i, p) <= 0f) break; cur = i }
                if (cur < 0) return@drawWithContent
                while (cur > 0 && timing.text[cur].isWhitespace()) cur--
                val f = timing.progress(cur, p)
                val pad = 6.dp.toPx()
                val curLine = l.getLineForOffset(cur)
                val w = size.width
                for (ln in 0..curLine) {
                    val top = l.getLineTop(ln) - pad
                    val bottom = l.getLineBottom(ln) + pad
                    if (ln < curLine) { clipRect(-pad, top, w + pad, bottom) { this@drawWithContent.drawContent() }; continue }
                    val box = l.getBoundingBox(cur)
                    val rtl = l.getBidiRunDirection(cur) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
                    if (rtl) clipRect(box.right - box.width * f, top, w + pad, bottom) { this@drawWithContent.drawContent() }
                    else clipRect(-pad, top, box.left + box.width * f, bottom) { this@drawWithContent.drawContent() }
                }
            })
    }
}

@Composable
fun RelatedPane(onNavigate: (Route) -> Unit) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    val data by produceState<Result<List<com.ravi.musicspot.model.Shelf>>?>(null, t.id) {
        value = runCatching {
            // 1.0.7: when YouTube Music has no related tab for the song, fall back to its radio so the pane is never empty.
            if (t.source == SourceKind.YOUTUBE) Graph.yt.related(t.nativeId).ifEmpty {
                Graph.yt.radio(t.nativeId).take(25).takeIf { it.isNotEmpty() }?.let { listOf(com.ravi.musicspot.model.Shelf("Radio from this song", it)) } ?: emptyList()
            }
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
            if (shelves.isEmpty()) EmptyState(Icons.Rounded.Explore, "Nothing related yet", if (r.isFailure) "Couldn't reach YouTube Music." else "YouTube Music has no related picks for this song.")
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                shelves.forEachIndexed { n, s -> // 1.0.6: keyed + typed items
                    item(key = "rel-h$n", contentType = "section-header") { SectionHeader(s.title) }
                    if (s.tracks.isNotEmpty()) item(key = "rel-t$n", contentType = "track-shelf") { HorizontalShelf(s.tracks, { it.id }) { x -> TrackCard(x, 128.dp, onClick = { Graph.player.playNext(x); Graph.player.next() }) } }
                    if (s.collections.isNotEmpty()) item(key = "rel-c$n", contentType = "collection-shelf") {
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
        CreditRow("3D Sound", if (engine.spatial) "On (virtual speakers, stereo)" else "Off") // 1.0.8
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

/** 1.0.10: playback speed (0.5x–2x) and pitch, shown while [visible]. Applies at once; kept for the session. */
@Composable
fun SpeedSheet(visible: Boolean, onDismiss: () -> Unit) = com.ravi.musicspot.ui.motion.MotionSheet(if (visible) Unit else null, onDismiss = onDismiss) {
    val sp by Graph.player.speed.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().glassSheet(Ms.r28).navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Playback speed · ${com.ravi.musicspot.playback.speedLabel(sp.speed)}", style = MaterialTheme.typography.titleLarge)
            // 0.05x steps from 0.5x to 2x (30 intervals → 29 inner steps).
            androidx.compose.material3.Slider(
                value = sp.speed,
                onValueChange = { v -> Graph.player.setSpeed(Math.round(v * 20f) / 20f) },
                valueRange = com.ravi.musicspot.playback.MIN_SPEED..com.ravi.musicspot.playback.MAX_SPEED,
                steps = 29,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                    FilterChip(sp.speed == v, { Graph.player.setSpeed(v) }, label = { Text(com.ravi.musicspot.playback.speedLabel(v)) })
                }
            }
            FilterChip(sp.keepPitch, { Graph.player.setSpeed(sp.speed, keepPitch = !sp.keepPitch) },
                label = { Text("Keep natural voice (preserve pitch)") })
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
    // 1.0.6: the position is no longer read at the top of the capsule (it recomposed the whole capsule, its glass and
    // AnimatedContent on every 250 ms tick). The active line is derived state (recomposes only when the line changes)
    // and the word highlight reads the position in its own small leaf.
    val posState = rememberSmoothPosition() // 1.0.8: frame-accurate (was the 250 ms samples)
    val offsetMs = settings.lyricsOffsetMs
    val active by remember(l, offsetMs) { androidx.compose.runtime.derivedStateOf { l.lines.activeIndex(posState.value + offsetMs) } }
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
                    if (ln.wordSynced && i == active) CapsuleWords(ln, posState, offsetMs)
                    else Text(ln.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}


/**
 * 1.0.6: the capsule's word-by-word highlight as a leaf. Only the number of lit words is derived from the position, so
 * this Text recomposes once per word (not on every position tick) and nothing above it recomposes at all.
 */
@Composable
private fun CapsuleWords(ln: com.ravi.musicspot.lyrics.LyricLine, pos: androidx.compose.runtime.State<Long>, offsetMs: Int) {
    val c = MaterialTheme.colorScheme
    val lit by remember(ln, offsetMs) { androidx.compose.runtime.derivedStateOf {
        val p = pos.value + offsetMs
        var n = 0
        for (w in ln.words) { if (w.startMs <= p) n++ else break }
        n
    } }
    val text = remember(ln, lit, c.primary, c.onSurface) {
        buildAnnotatedString {
            ln.words.forEachIndexed { k, w -> withStyle(SpanStyle(color = if (k < lit) c.primary else c.onSurface.copy(alpha = 0.6f))) { append(w.text) } }
        }
    }
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/**
 * 1.0.7: the queue row that is playing: a rounded band in the artwork's primary colour with a softly breathing
 * outline (alpha only, in the draw phase; steady with reduced motion).
 */
@Composable
private fun Modifier.nowPlayingHighlight(): Modifier {
    val c = MaterialTheme.colorScheme
    val still = com.ravi.musicspot.ui.motion.reducedMotion()
    val pulse = if (still) null else androidx.compose.animation.core.rememberInfiniteTransition(label = "qNow")
        .animateFloat(0.35f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "qNowA")
    val fill = c.primary.copy(alpha = 0.16f)
    val edge = c.primary
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    return this.padding(horizontal = 6.dp).drawWithCache {
        val r = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())
        onDrawBehind {
            drawRoundRect(fill, cornerRadius = r)
            drawRoundRect(edge.copy(alpha = pulse?.value ?: 0.7f), cornerRadius = r, style = stroke)
        }
    }.clip(shape)
}
