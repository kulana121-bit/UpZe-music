package com.ravi.musicspot.ui.player

import com.ravi.musicspot.ui.motion.pressScale
import com.ravi.musicspot.ui.motion.staggerIn
import androidx.compose.animation.animateContentSize
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.playback.EngineAudioProcessor
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.QualityLine
import com.ravi.musicspot.ui.components.formatTime
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.motion.rememberHaptics
import com.ravi.musicspot.ui.theme.Ms
import androidx.media3.common.Player as M3

enum class Pane(val label: String, val icon: ImageVector) {
    QUEUE("Up next", Icons.AutoMirrored.Rounded.QueueMusic),
    LYRICS("Lyrics", Icons.Rounded.Lyrics),
    RELATED("Related", Icons.Rounded.Explore),
    CREDITS("Credits", Icons.Rounded.Info),
}

/**
 * The Now Playing screen. It never scrolls: artwork (sized to the space left), title, quality line, seek bar,
 * transport and a bottom action row always fit. Lyrics/queue/related/credits open in a pane that slides up over
 * the lower part (the pane's own list may scroll; the player does not). Dragging down anywhere outside a pane
 * collapses the player to the mini player with the cover following the finger.
 */
@Composable
fun PlayerScreen(
    progress: () -> Float,
    onCollapse: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    coverVisible: Boolean,
    onCoverBounds: (Rect) -> Unit,
    onOpenEq: () -> Unit,
    onNavigate: (com.ravi.musicspot.ui.Route) -> Unit,
) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    var pane by rememberSaveable { mutableStateOf<Pane?>(null) }
    // 1.0.1: last full-screen page (Queue/Lyrics), reopened by a swipe up.
    var lastFull by rememberSaveable { mutableStateOf(Pane.QUEUE) }
    androidx.compose.runtime.LaunchedEffect(pane) { pane?.let { if (it.isFull()) lastFull = it } }
    var sleepSheet by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val c = MaterialTheme.colorScheme
    androidx.activity.compose.BackHandler(enabled = true) { if (pane != null) pane = null else onCollapse() }
    // v2.0.7: Spotify Canvas behind Now Playing (cover steps aside while it plays).
    val canvas = rememberCanvasUrl(t)
    val canvasOn = canvas != null && pane == null

    GlassLayer(
        Modifier.fillMaxSize(),
        background = c.background,
        content = {
            // v2.0.5: animated gradient from the artwork's own colours, gliding to the next song's colours.
            ArtGradient(t.artUrl)
            // v2.0.8: soft, blurred artwork wash (real blur on Android 12+, a faint plain wash below that).
            val blurOk = android.os.Build.VERSION.SDK_INT >= 31 && !Graph.liteFx // 1.0.1: no full-screen blur on lite devices
            Artwork(t.artUrl, Modifier.fillMaxSize().then(if (blurOk) Modifier.blur(56.dp) else Modifier)
                .graphicsLayer { alpha = if (blurOk) 0.42f else 0.16f; scaleX = 1.2f; scaleY = 1.2f }, androidx.compose.ui.graphics.RectangleShape)
            if (canvas != null) {
                CanvasVideo(canvas, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.background.copy(alpha = 0.25f), Color.Transparent, c.background.copy(alpha = if (canvas != null) 0.55f else 0.88f)))))
        },
        overlay = {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .playerVerticalGestures(enabled = pane == null, onDrag = onDrag, onDragEnd = onDragEnd, onSwipeUp = { pane = lastFull }), // 1.0.1: swipe up = full-screen pane
            ) {
                TopRow(onCollapse, more = {
                    // 1.0.1: secondary actions live in the ⋮ menu (Related, Credits, Sleep, Sound & EQ, Song options).
                    PlayerMoreButton(onSongOptions = { Menus.track = t }, onPane = { pane = it }, onOpenSleep = { sleepSheet = true }, onOpenEq = onOpenEq,
                        buttonModifier = Modifier.glass(CircleShape))
                }, sleepChip = { SleepChip({ sleepSheet = true }, Modifier.padding(end = 6.dp)) })
                if (landscape) {
                    Row(Modifier.weight(1f).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverBox(t.artUrl, coverVisible, onCoverBounds, Modifier.fillMaxHeight().weight(1f))
                        Spacer(Modifier.width(24.dp))
                        // v2.0.9: landscape now shows the panes too (fade-through between controls and each pane).
                        val stillLand = com.ravi.musicspot.ui.motion.reducedMotion()
                        AnimatedContent(pane.inPlayer(), transitionSpec = { com.ravi.musicspot.ui.motion.MotionSpec.fadeThrough(stillLand) }, label = "paneLand", modifier = Modifier.weight(1.2f).fillMaxHeight()) { p ->
                            if (p == null) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) { Controls(onOpenSleep = { sleepSheet = true }, onOpenEq = onOpenEq) }
                            else Surface(Modifier.fillMaxSize().padding(vertical = 8.dp), shape = Ms.r28, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                                Box(Modifier.fillMaxSize().glass(Ms.r28)) {
                                    when (p) {
                                        Pane.QUEUE -> QueuePane()
                                        Pane.LYRICS -> LyricsPane()
                                        Pane.RELATED -> RelatedPane(onNavigate = { onCollapse(); onNavigate(it) })
                                        Pane.CREDITS -> CreditsPane(t)
                                    }
                                }
                            }
                        }
                    }
                    PlayerEntryBar(onQueue = { pane = Pane.QUEUE }, onLyrics = { pane = Pane.LYRICS }) // 1.0.1: Up next / Lyrics entry points
                } else {
                    // v2.0.6: opening a pane rises on the surface spring (shared axis Y), closing sinks back; switching between
                    // panes (Up next / Lyrics / Related / Credits) is a fade-through. Instant with Reduce motion.
                    val stillPane = com.ravi.musicspot.ui.motion.reducedMotion()
                    AnimatedContent(pane.inPlayer(), transitionSpec = {
                        val ms = com.ravi.musicspot.ui.motion.MotionSpec
                        when {
                            // 1.0.1: Related / Credits (from the ⋮ menu) grow out of the tapped item and shrink back into it.
                            initialState == null && targetState != null -> com.ravi.musicspot.ui.motion.TapOrigin.windowOrigin().let { o -> com.ravi.musicspot.ui.motion.TapOrigin.save("playerPane", o); ms.containerGrow(stillPane, o) }
                            initialState != null && targetState == null -> ms.containerShrink(stillPane, com.ravi.musicspot.ui.motion.TapOrigin.take("playerPane"))
                            else -> ms.fadeThrough(stillPane)
                        }
                    }, label = "pane", modifier = Modifier.weight(1f)) { p ->
                        if (p == null) Column(Modifier.fillMaxSize()) {
                            val coverA by animateFloatAsState(if (canvasOn) 0f else 1f, if (stillPane) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(700), label = "coverAside")
                            CoverBox(t.artUrl, coverVisible, onCoverBounds, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp)
                                .graphicsLayer { alpha = coverA; scaleX = 0.85f + 0.15f * coverA; scaleY = 0.85f + 0.15f * coverA })
                            // v2.0.8: the mini lyrics capsule now sits right under the song name (inside Controls).
                            Controls(onOpenSleep = { sleepSheet = true }, onOpenEq = onOpenEq,
                                capsule = { LyricsCapsule(onOpen = { pane = Pane.LYRICS }, modifier = Modifier.padding(top = 6.dp)) })
                        } else Column(Modifier.fillMaxSize()) {
                            CompactHeader(onClose = { pane = null })
                            Surface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), shape = Ms.r28, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                                Box(Modifier.fillMaxSize().glass(Ms.r28)) {
                                    when (p) {
                                        Pane.QUEUE -> QueuePane()
                                        Pane.LYRICS -> LyricsPane()
                                        Pane.RELATED -> RelatedPane(onNavigate = { onCollapse(); onNavigate(it) })
                                        Pane.CREDITS -> CreditsPane(t)
                                    }
                                }
                            }
                        }
                    }
                    PlayerEntryBar(onQueue = { pane = Pane.QUEUE }, onLyrics = { pane = Pane.LYRICS }) // 1.0.1: Up next / Lyrics entry points
                }
            }
        },
    )
    // 1.0.1: Queue / Lyrics open full screen over the player (pager + tabs, swipe down or Back to close).
    FullScreenPane(pane, onSelect = { pane = it }, onClose = { pane = null })
    SleepSheet(sleepSheet) { sleepSheet = false }
}

/**
 * Two colours from the artwork (Palette vibrant + dark muted), each animated over 900 ms when the song changes, drawn
 * as a gradient whose direction drifts slowly (20 s loop). Reduce motion: colours snap and nothing drifts. Plain
 * gradients only (no blur), so it is cheap on Android 10.
 */
@Composable
private fun ArtGradient(url: String?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val c = MaterialTheme.colorScheme
    val still = com.ravi.musicspot.ui.motion.reducedMotion()
    var colors by remember { mutableStateOf<Pair<Color, Color>?>(null) }
    androidx.compose.runtime.LaunchedEffect(url) { colors = runCatching { com.ravi.musicspot.ui.theme.artPalette(ctx, url) }.getOrNull() ?: colors }
    val spec = if (still) androidx.compose.animation.core.snap<Color>() else androidx.compose.animation.core.tween(1260)
    val a by androidx.compose.animation.animateColorAsState(colors?.first ?: c.primaryContainer, spec, label = "artA")
    val b by androidx.compose.animation.animateColorAsState(colors?.second ?: c.background, spec, label = "artB")
    // v2.0.14: unconditional loop; 0..1 linear ping-pong over 28s.
    val driftLoop by com.ravi.musicspot.ui.motion.rememberLoopPhase(running = !still, periodMs = 28_000)
    val drift = if (still) 0f else driftLoop
    Box(Modifier.fillMaxSize().androidxDrawGradient(a.copy(alpha = 0.62f), b.copy(alpha = 0.45f), c.background, drift))
}

private fun Modifier.androidxDrawGradient(a: Color, b: Color, bg: Color, drift: Float): Modifier = this.then(
    Modifier.drawBehind {
        drawRect(bg)
        val w = size.width; val h = size.height
        drawRect(Brush.linearGradient(listOf(a, b, Color.Transparent),
            start = androidx.compose.ui.geometry.Offset(w * drift, 0f), end = androidx.compose.ui.geometry.Offset(w * (1f - drift), h * 0.75f)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, bg), startY = h * 0.45f, endY = h))
    })

@Composable
private fun TopRow(onCollapse: () -> Unit, more: @Composable () -> Unit, sleepChip: @Composable () -> Unit = {}) {
    val stream by Playback.stream.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val colI = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val stillTop = com.ravi.musicspot.ui.motion.reducedMotion()
        IconButton(onClick = onCollapse, interactionSource = colI, modifier = Modifier.pressScale(colI, 0.88f).glass(CircleShape)) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player") }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(Modifier.glass(Ms.pill).padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                AnimatedContent(stream?.info?.source?.label ?: "Finding the best source…", transitionSpec = { com.ravi.musicspot.ui.motion.MotionSpec.fadeThrough(stillTop) }, label = "srcLbl") {
                    Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        sleepChip()
        more()
    }
}

@Composable
private fun CoverBox(url: String?, visible: Boolean, onBounds: (Rect) -> Unit, modifier: Modifier) {
    val st by Graph.player.state.collectAsState()
    // Artwork breathes up while playing, settles back when paused; instant with Reduce motion.
    val stillCover = com.ravi.musicspot.ui.motion.reducedMotion()
    val scale by animateFloatAsState(if (st.playing) 1f else 0.9f, if (stillCover) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.7f, stiffness = 112f), label = "coverScale")
    // v2.0.5: the shadow deepens while playing and flattens when paused; a soft pulse while buffering.
    val shadow by animateFloatAsState(if (st.playing) 28f else 6f, if (stillCover) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.8f, stiffness = 82f), label = "coverShadow")
    // v2.0.14: unconditional loop; phase 0..1 maps to 1f..0.55f, ping-pong over 980ms.
    val pulseLoop by com.ravi.musicspot.ui.motion.rememberLoopPhase(running = !stillCover && st.buffering, periodMs = 980, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val pulse = if (stillCover || !st.buffering) 1f else 1f + (0.55f - 1f) * pulseLoop
    // v2.0.7b gestures on the artwork: swipe left/right = next/previous (the cover follows the finger and springs
    // back), double-tap = like with a heart burst, long-press = song options. Swipe down still collapses the player.
    val haptics = rememberHaptics()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dx = remember { androidx.compose.animation.core.Animatable(0f) }
    val heart = remember { androidx.compose.animation.core.Animatable(0f) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) { side.toPx() }
        // v2.0.8: a soft halo in the song's colour behind the cover, brighter while playing.
        val glowC = MaterialTheme.colorScheme.primary
        Box(Modifier.size(side).graphicsLayer { scaleX = 1.18f * scale; scaleY = 1.18f * scale; alpha = 0.35f + 0.4f * ((shadow - 6f) / 22f) }
            .background(Brush.radialGradient(listOf(glowC.copy(alpha = 0.45f), Color.Transparent))))
        Artwork(url, Modifier.size(side).onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .pointerInput(sidePx) { // v2.0.14: keyed on sidePx
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = dx.value
                        scope.launch {
                            if (kotlin.math.abs(v) > sidePx * 0.28f) {
                                haptics.click()
                                dx.animateTo(if (v < 0) -sidePx else sidePx, androidx.compose.animation.core.tween(224))
                                if (v < 0) Graph.player.next() else Graph.player.previous()
                                dx.snapTo(if (v < 0) sidePx * 0.6f else -sidePx * 0.6f)
                            }
                            dx.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 153f))
                        }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 153f)) } },
                ) { change, amount -> change.consume(); scope.launch { dx.snapTo(dx.value + amount) } }
            }
            .pointerInput(st.current?.id) {
                detectTapGestures(
                    onDoubleTap = {
                        val t = st.current ?: return@detectTapGestures
                        haptics.confirm()
                        if (Graph.library.data.value.liked.none { it.id == t.id }) Graph.library.setLiked(t, true)
                        scope.launch { heart.snapTo(0f); heart.animateTo(1f, androidx.compose.animation.core.tween(980)) ; heart.snapTo(0f) }
                    },
                    onLongPress = { st.current?.let { haptics.confirm(); Menus.track = it } },
                )
            }
            .graphicsLayer {
                val f = (if (sidePx > 0f) dx.value / sidePx else 0f).coerceIn(-1f, 1f) // v2.0.14
                translationX = dx.value; rotationZ = f * 8f; cameraDistance = 12f * density
                rotationY = -f * 18f
                val sc = scale * (1f - 0.08f * kotlin.math.abs(f))
                scaleX = sc; scaleY = sc; alpha = (if (visible) pulse else 0f) * (1f - 0.4f * kotlin.math.abs(f)); shadowElevation = shadow.dp.toPx(); shape = Ms.r28; clip = true
                spotShadowColor = glowC; ambientShadowColor = glowC
            }, Ms.r28, "Album art. Swipe for next or previous, double-tap to like, long-press for options")
        val h = heart.value
        if (h > 0f) Icon(Icons.Rounded.Favorite, null, Modifier.size(side / 3).graphicsLayer {
            val pop = if (h < 0.3f) h / 0.3f * 1.2f else 1.2f - 0.2f * ((h - 0.3f) / 0.7f)
            scaleX = pop; scaleY = pop; alpha = if (h < 0.7f) 1f else (1f - (h - 0.7f) / 0.3f)
        }, tint = Color.White)
    }
}

@Composable
private fun CompactHeader(onClose: () -> Unit) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Back to artwork", onClick = onClose).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(t.artUrl, Modifier.size(52.dp), Ms.r12)
        Spacer(Modifier.width(12.dp))
        val stillHdr = com.ravi.musicspot.ui.motion.reducedMotion()
        AnimatedContent(t, contentKey = { it.id }, transitionSpec = { com.ravi.musicspot.ui.motion.MotionSpec.sharedAxisX(true, stillHdr) }, label = "hdrSwap", modifier = Modifier.weight(1f)) { tt ->
            Column(Modifier.fillMaxWidth()) {
                Text(tt.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tt.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        FilledIconButton(onClick = { Graph.player.toggle() }) { com.ravi.musicspot.ui.motion.PlayPauseIcon(st.playing) }
    }
}

/** v2.0.14b: Controls' seek slider and time row, split out so only this recomposes on position ticks. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ControlsSeek(durationMs: Long) {
    val pos by Graph.player.position.collectAsState()
    var scrub by remember { mutableFloatStateOf(-1f) }
    val dur = durationMs.coerceAtLeast(1)
    // v2.0.5: the thumb grows while dragged (spring; instant with Reduce motion).
    val seekI = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val dragged by seekI.collectIsDraggedAsState()
    val pressedSeek by seekI.collectIsPressedAsState()
    val stillSeek = com.ravi.musicspot.ui.motion.reducedMotion()
    val thumb by androidx.compose.animation.core.animateDpAsState(if (dragged || pressedSeek || scrub >= 0) 22.dp else 12.dp,
        if (stillSeek) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.55f, stiffness = 255f), label = "thumb")
    Slider(
        value = if (scrub >= 0) scrub else (pos.toFloat() / dur).coerceIn(0f, 1f),
        onValueChange = { scrub = it }, onValueChangeFinished = { if (scrub >= 0f && durationMs > 0) Graph.player.seek((scrub * durationMs).toLong().coerceIn(0L, durationMs)); scrub = -1f } /* v2.0.14 */,
        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
        interactionSource = seekI,
        thumb = { Box(Modifier.size(thumb).graphicsLayer { shadowElevation = 6.dp.toPx(); shape = CircleShape; clip = true }.background(Color.White, CircleShape)) },
        modifier = Modifier.fillMaxWidth().height(32.dp).staggerIn(1),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatTime(if (scrub >= 0) (scrub * dur).toLong() else pos), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Controls(onOpenSleep: () -> Unit, onOpenEq: () -> Unit, capsule: @Composable () -> Unit = {}) {
    val st by Graph.player.state.collectAsState()
    // v2.0.14b: position is collected inside ControlsSeek.
    val lib by Graph.library.data.collectAsState()
    val stream by Playback.stream.collectAsState()
    val engine by EngineAudioProcessor.status.collectAsState()
    val err by Playback.error.collectAsState()
    val t = st.current ?: return
    val haptics = rememberHaptics()
    val liked = lib.liked.any { it.id == t.id }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(Modifier.staggerIn(0), verticalAlignment = Alignment.CenterVertically) {
            // v2.0.5: title + artist slide in the direction of the skip (crossfade only with Reduce motion).
            val stillText = com.ravi.musicspot.ui.motion.reducedMotion()
            AnimatedContent(targetState = st.index to t, modifier = Modifier.weight(1f), label = "titleSwap", contentKey = { it.second.id },
                transitionSpec = {
                    if (stillText) fadeIn(androidx.compose.animation.core.tween(210)) togetherWith fadeOut(androidx.compose.animation.core.tween(210))
                    else {
                        val dir = if (targetState.first >= initialState.first) 1 else -1
                        (androidx.compose.animation.slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 194f)) { w -> dir * w / 3 } + fadeIn()) togetherWith
                            (androidx.compose.animation.slideOutHorizontally(spring(dampingRatio = 0.85f, stiffness = 194f)) { w -> -dir * w / 3 } + fadeOut())
                    }
                }) { (_, tt) ->
                Column(Modifier.fillMaxWidth()) {
                    Text(tt.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarqueeSafe())
                    Text(tt.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.basicMarqueeSafe())
                }
            }
            val likePop by animateFloatAsState(if (liked) 1.15f else 1f, spring(dampingRatio = 0.4f, stiffness = 255f), label = "likePop")
            IconButton(onClick = { haptics.confirm(); Graph.library.setLiked(t, !liked) }, modifier = Modifier.glass(CircleShape).graphicsLayer { scaleX = likePop; scaleY = likePop }) {
                val stillLike = com.ravi.musicspot.ui.motion.reducedMotion()
                AnimatedContent(liked, transitionSpec = { com.ravi.musicspot.ui.motion.iconSwap(stillLike) }, label = "likeSwap") { on ->
                    Icon(if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (on) "Unlike" else "Like", tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        capsule()
        Spacer(Modifier.height(6.dp))
        val stillErr = com.ravi.musicspot.ui.motion.reducedMotion()
        AnimatedContent(err, transitionSpec = { com.ravi.musicspot.ui.motion.MotionSpec.fadeThrough(stillErr) }, contentKey = { it != null }, label = "errSwap") { e ->
        if (e != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Couldn't play: ${e.take(140)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f), maxLines = 3)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick = { runCatching { ctx.startActivity(com.ravi.musicspot.core.PlaybackLog.shareIntent()) } }) { Text("Share log") }
            TextButton(onClick = { Graph.player.retry() }) { Text("Retry") }
        } else QualityLine(stream?.takeIf { it.trackId == t.id }, engine.badge, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        ControlsSeek(st.durationMs) // v2.0.14b: seek slider + times read position in their own scope
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).staggerIn(2), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val stillRep2 = com.ravi.musicspot.ui.motion.reducedMotion()
            val shufI = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ModeButton(st.shuffle, shufI, onClick = { haptics.tick(); Graph.player.setShuffle(!st.shuffle) }) {
                AnimatedContent(st.shuffle, transitionSpec = { com.ravi.musicspot.ui.motion.iconSwap(stillRep2) }, label = "shufSwap") { on ->
                    Icon(Icons.Rounded.Shuffle, if (on) "Shuffle on" else "Shuffle off", tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val prevI = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val playI = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val nextI = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            // v2.0.5: prev/next nudge towards their direction while pressed; the play button morphs circle ↔ rounded
            // square with a spring (playing = squarer). Static under Reduce motion.
            val stillBtn = com.ravi.musicspot.ui.motion.reducedMotion()
            val prevDown by prevI.collectIsPressedAsState()
            val nextDown by nextI.collectIsPressedAsState()
            val nudgeSpec = if (stillBtn) androidx.compose.animation.core.snap<Float>() else spring(dampingRatio = 0.45f, stiffness = 306f)
            val prevX by animateFloatAsState(if (prevDown && !stillBtn) -6f else 0f, nudgeSpec, label = "prevNudge")
            val nextX by animateFloatAsState(if (nextDown && !stillBtn) 6f else 0f, nudgeSpec, label = "nextNudge")
            val corner by animateFloatAsState(if (st.playing) 30f else 50f, if (stillBtn) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.5f, stiffness = 204f), label = "playMorph")
            IconButton(onClick = { haptics.click(); Graph.player.previous() }, interactionSource = prevI, modifier = Modifier.graphicsLayer { translationX = prevX.dp.toPx() }.pressScale(prevI, 0.9f).size(60.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(40.dp)) }
            Box(contentAlignment = Alignment.Center) {
                val playGlow = MaterialTheme.colorScheme.primary
                FilledIconButton(onClick = { haptics.click(); Graph.player.toggle() }, interactionSource = playI, modifier = Modifier.pressScale(playI, 0.92f).size(78.dp)
                    .graphicsLayer { shadowElevation = (if (st.playing) 22 else 10).dp.toPx(); shape = androidx.compose.foundation.shape.RoundedCornerShape(corner.toInt().coerceIn(0, 50)); spotShadowColor = playGlow; ambientShadowColor = playGlow },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(corner.toInt().coerceIn(0, 50)),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    com.ravi.musicspot.ui.motion.PlayPauseIcon(st.playing, Modifier.size(40.dp))
                }
                if (st.buffering) CircularProgressIndicator(Modifier.size(80.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = { haptics.click(); Graph.player.next() }, interactionSource = nextI, modifier = Modifier.graphicsLayer { translationX = nextX.dp.toPx() }.pressScale(nextI, 0.9f).size(60.dp)) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(40.dp)) }
            val repI = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val stillRep = com.ravi.musicspot.ui.motion.reducedMotion()
            ModeButton(st.repeat != M3.REPEAT_MODE_OFF, repI, onClick = { haptics.tick(); Graph.player.cycleRepeat() }) {
                AnimatedContent(st.repeat, transitionSpec = { com.ravi.musicspot.ui.motion.iconSwap(stillRep) }, label = "repSwap") { r ->
                    Icon(if (r == M3.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        when (r) { M3.REPEAT_MODE_ONE -> "Repeat one"; M3.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" },
                        tint = if (r != M3.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // 1.0.1: Sleep and Sound moved to the ⋮ menu; the transport row gets the room.
        Spacer(Modifier.height(4.dp))
    }
}


/** Marquee for long titles; skipped under reduced motion. */
@Composable
private fun Modifier.basicMarqueeSafe(): Modifier = if (com.ravi.musicspot.ui.motion.reducedMotion()) this else this.basicMarquee(initialDelayMillis = 2000)


/** v2.0.9: shuffle / repeat button with an M3 active pill that grows in behind the icon, plus press scale. */
@Composable
private fun ModeButton(active: Boolean, interaction: androidx.compose.foundation.interaction.MutableInteractionSource, onClick: () -> Unit, content: @Composable () -> Unit) {
    val still = com.ravi.musicspot.ui.motion.reducedMotion()
    val on by animateFloatAsState(if (active) 1f else 0f, if (still) androidx.compose.animation.core.snap() else com.ravi.musicspot.ui.motion.MotionSpec.popSpring(), label = "modeOn")
    val primary = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 52.dp, height = 36.dp).graphicsLayer { alpha = on; scaleX = 0.5f + 0.5f * on; scaleY = 0.5f + 0.5f * on }.clip(Ms.pill).background(primary.copy(alpha = 0.18f)))
        IconButton(onClick = onClick, interactionSource = interaction, modifier = Modifier.pressScale(interaction, 0.86f)) { content() }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).size(4.dp).graphicsLayer { alpha = on; scaleX = on; scaleY = on }.clip(CircleShape).background(primary))
    }
}
