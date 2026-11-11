package com.ravi.musicspot.ui.player

import androidx.compose.ui.draw.clipToBounds
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.playback.EngineAudioProcessor
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.formatTime
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.glass.liquidGlass
import com.ravi.musicspot.ui.motion.MotionSpec
import com.ravi.musicspot.ui.motion.iconSwap
import com.ravi.musicspot.ui.motion.pressScale
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.rememberHaptics
import com.ravi.musicspot.ui.motion.staggerIn
import kotlinx.coroutines.launch
import androidx.media3.common.Player as M3

private val DECK = RoundedCornerShape(30.dp)
private val ART = RoundedCornerShape(32.dp)
private val PILL = RoundedCornerShape(percent = 50)

/**
 * v2.0.10 "Aura" Now Playing (1.0.6: the only Now Playing design): Canvas, panes, sleep, sound, like, shuffle/repeat,
 * gestures and the cover morph, laid out as:
 *  - a slow aura of the artwork's soft colours drifting behind a blurred artwork wash;
 *  - floating artwork with a mirrored reflection, breathing while playing; swipe = skip, double-tap = like;
 *  - one liquid-glass control deck: title, lyrics capsule, a thick expressive seek bar, a morphing play button and a
 *    row of glass chips. Panes open with an M3 container transform (artwork shrinks into the header, pane rises).
 * Every motion uses the app-wide slow MotionSpec and turns instant with Reduce motion.
 */
@Composable
fun AuraPlayerScreen(
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
    val reveal = rememberPaneReveal() // 1.0.2: interactive swipe-up to Queue / Lyrics
    // 1.0.1: last full-screen page (Queue/Lyrics), reopened by a swipe up.
    var lastFull by rememberSaveable { mutableStateOf(Pane.QUEUE) }
    androidx.compose.runtime.LaunchedEffect(pane) { pane?.let { if (it.isFull()) lastFull = it } }
    var sleepSheet by remember { mutableStateOf(false) }
    var soundSheet by remember { mutableStateOf(false) } // 1.0.7: EQ / Sound opens over the player
    var speedSheet by remember { mutableStateOf(false) } // 1.0.10: playback speed + pitch
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val still = reducedMotion()
    val c = MaterialTheme.colorScheme
    androidx.activity.compose.BackHandler(enabled = true) { if (pane != null) pane = null else onCollapse() }
    val canvas = rememberCanvasUrl(t)
    val canvasOn = canvas != null && pane == null
    // 1.0.8: Motion Lyrics (player ⋯ › Motion Lyrics) plays kinetic lyrics over the artwork; the artwork stays.
    val playerSettings by Graph.settings.state.collectAsState()
    val liveOn = playerSettings.liveLyrics
    // 1.0.8: true while Motion Lyrics are actually playing (synced lyrics found): the background turns into the
    // full-screen blurred artwork.
    var motionBg by remember { mutableStateOf(false) }
    // 1.0.8 (user): Motion Lyrics run on every song. Without a Canvas the artwork goes full-screen and blurred behind
    // them; with a Spotify Canvas the video stays clear (no blur, no dark wash) and the lyrics play over it.
    val motionOn = liveOn
    val motionShowing = motionOn && motionBg

    GlassLayer(
        Modifier.fillMaxSize().paneDepth(reveal, pane.isFull()), // 1.0.2: the player sinks back under the pane
        background = c.background,
        content = {
            AuraBackground(t.artUrl, motion = motionShowing && canvas == null)
            if (canvas != null) {
                CanvasVideo(canvas, Modifier.fillMaxSize(), visible = { progress() > 0.3f && !pane.isFull() }) // 1.0.8: pauses while hidden, recovers on return
                val canvasScrim = remember { Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent, Color.Black.copy(alpha = 0.50f))) } // 1.0.5: remembered
                Box(Modifier.fillMaxSize().background(canvasScrim))
            }
        },
        overlay = {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .playerVerticalGestures(enabled = pane == null, onDrag = onDrag, onDragEnd = onDragEnd, onSwipeUp = { pane = lastFull }, reveal = reveal), // 1.0.1: swipe up = full-screen pane
            ) {
                AuraTopBar(onCollapse, onMore = { Menus.track = t })
                if (landscape) {
                    Row(Modifier.weight(1f).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (motionOn) LiveLyricsStage(Modifier.fillMaxHeight().weight(1f), onOpenLyrics = { pane = Pane.LYRICS }, onActive = { motionBg = it }, onVideo = canvasOn) {
                            AuraCover(t.artUrl, coverVisible && !canvasOn, onCoverBounds, Modifier.fillMaxSize(), reflection = false)
                        }
                        else AuraCover(t.artUrl, coverVisible && !canvasOn, onCoverBounds, Modifier.fillMaxHeight().weight(1f), reflection = false)
                        Spacer(Modifier.width(20.dp))
                        AnimatedContent(pane.inPlayer(), transitionSpec = { MotionSpec.fadeThrough(still) }, label = "auraLand", modifier = Modifier.weight(1.2f).fillMaxHeight()) { p ->
                            if (p == null) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                                AuraDeck(onOpenSleep = { sleepSheet = true }, onOpenEq = { soundSheet = true }, onPane = { pane = it }, openPane = pane, hideCapsule = motionShowing, onOpenSpeed = { speedSheet = true })
                            } else AuraPaneCard(p, t, onCollapse, onNavigate)
                        }
                    }
                } else {
                    AnimatedContent(pane.inPlayer(), transitionSpec = {
                        when {
                            initialState == null && targetState != null -> MotionSpec.containerRise(open = true, still = still)
                            initialState != null && targetState == null -> MotionSpec.containerRise(open = false, still = still)
                            else -> MotionSpec.sharedAxisX(forward = (targetState?.ordinal ?: 0) >= (initialState?.ordinal ?: 0), still = still)
                        }
                    }, label = "auraPane", modifier = Modifier.weight(1f)) { p ->
                        if (p == null) Column(Modifier.fillMaxSize()) {
                            val coverA by animateFloatAsState(if (canvasOn) 0f else 1f, if (still) snap() else tween(500), label = "auraCoverAside")
                            val coverLook = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)
                                .graphicsLayer { alpha = coverA; scaleX = 0.82f + 0.18f * coverA; scaleY = 0.82f + 0.18f * coverA }
                            // 1.0.8: Motion Lyrics play over the artwork (which always stays; alone for songs without
                            // synced lyrics and while they load).
                            if (motionOn) LiveLyricsStage(Modifier.weight(1f).fillMaxWidth(), onOpenLyrics = { pane = Pane.LYRICS }, onActive = { motionBg = it }, onVideo = canvasOn) {
                                AuraCover(t.artUrl, coverVisible, onCoverBounds, Modifier.fillMaxSize().then(coverLook), reflection = true)
                            }
                            else AuraCover(t.artUrl, coverVisible, onCoverBounds, Modifier.weight(1f).fillMaxWidth().then(coverLook), reflection = true)
                            AuraDeck(onOpenSleep = { sleepSheet = true }, onOpenEq = { soundSheet = true }, onPane = { pane = it }, openPane = null, hideCapsule = motionShowing, onOpenSpeed = { speedSheet = true },
                                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp))
                        } else Column(Modifier.fillMaxSize()) {
                            AuraMiniHeader(onClose = { pane = null })
                            AuraPaneCard(p, t, onCollapse, onNavigate, Modifier.weight(1f))
                            AuraPaneSwitcher(p, onSelect = { pane = if (pane == it) null else it }, onClose = { pane = null })
                        }
                    }
                }
            }
        },
    )
    // 1.0.1: Queue / Lyrics open full screen over the player (pager + tabs, swipe down or Back to close).
    FullScreenPane(pane, onSelect = { pane = it }, onClose = { pane = null }, reveal = reveal, onNavigate = { pane = null; onCollapse(); onNavigate(it) })
    SleepSheet(sleepSheet) { sleepSheet = false }
    SpeedSheet(speedSheet) { speedSheet = false }
    com.ravi.musicspot.ui.settings.SoundSheet(soundSheet) { soundSheet = false }
}

/** The artwork's palette as three soft orbs that drift very slowly (40 s loop) over a blurred artwork wash. */
@Composable
private fun AuraBackground(url: String?, motion: Boolean = false) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val still = reducedMotion()
    var colors by remember { mutableStateOf<Pair<Color, Color>?>(null) }
    LaunchedEffect(url) { colors = runCatching { com.ravi.musicspot.ui.theme.artPalette(ctx, url) }.getOrNull() ?: colors }
    val spec = if (still) snap<Color>() else tween(900) // 1.0.8: was 1600
    val a by animateColorAsState(colors?.first ?: c.primaryContainer, spec, label = "auraA")
    val b by animateColorAsState(colors?.second ?: c.secondaryContainer, spec, label = "auraB")
    val p3 = c.primary
    // v2.0.14: unconditional loop (was rememberInfiniteTransition inside an if); 0..1 linear restart over 40s.
    // 1.0.2: the loop is read only while drawing (no recomposition per frame) and is off on lite devices, where a
    // moving background made every glass control above it re-blur on every frame.
    val drifting = !still && !com.ravi.musicspot.Graph.liteFx
    val auraLoop = com.ravi.musicspot.ui.motion.rememberLoopPhase(running = drifting, periodMs = 40_000, reverse = false)
    Box(Modifier.fillMaxSize().background(c.background))
    // 1.0.2: SoftArt replaces the full-screen Modifier.blur (same wash, much cheaper, every Android version).
    // 1.0.8 Motion Lyrics: the same cheap blurred art becomes the full-screen backdrop (stronger, less zoomed) with a
    // dark wash so the white lyrics read on any cover; glides in/out. Read only in the draw phase (no recomposition).
    val m by animateFloatAsState(if (motion) 1f else 0f, if (still) snap<Float>() else tween(520), label = "auraMotion")
    com.ravi.musicspot.ui.components.SoftArt(url, Modifier.fillMaxSize().clipToBounds().graphicsLayer {
        alpha = 0.38f + 0.57f * m; val s = 1.35f - 0.17f * m; scaleX = s; scaleY = s
    })
    // 1.0.5: the orbs used to allocate three radial Brushes (+ colour lists + shaders) and a vertical Brush on every
    // frame of the 40 s drift. Each orb's gradient is now built once per size / colour, centred on the origin, and the
    // canvas is translated to the orb's moving centre, which draws exactly radialGradient(center = (cx, cy)). The
    // palette colours a / b are read inside the cache block, so a colour glide rebuilds the cache without recomposing.
    val bgColor = c.background
    Box(Modifier.fillMaxSize().drawWithCache {
        val w = size.width; val h = size.height
        val r1 = w * 0.85f; val r2 = w * 0.8f; val r3 = w * 0.7f
        val orb1 = Brush.radialGradient(listOf(a.copy(alpha = 0.55f), Color.Transparent), center = Offset.Zero, radius = r1)
        val orb2 = Brush.radialGradient(listOf(b.copy(alpha = 0.50f), Color.Transparent), center = Offset.Zero, radius = r2)
        val orb3 = Brush.radialGradient(listOf(p3.copy(alpha = 0.22f), Color.Transparent), center = Offset.Zero, radius = r3)
        val fade = Brush.verticalGradient(listOf(Color.Transparent, bgColor.copy(alpha = 0.55f)), startY = h * 0.5f, endY = h)
        onDrawBehind {
            val phase = if (drifting) auraLoop.value else 0.25f
            val tau = (Math.PI * 2).toFloat()
            val t = phase * tau
            translate(w * (0.30f + 0.18f * kotlin.math.cos(t)), h * (0.25f + 0.10f * kotlin.math.sin(t))) { drawCircle(orb1, r1, Offset.Zero) }
            translate(w * (0.75f + 0.15f * kotlin.math.sin(t * 1f + 1.7f)), h * (0.55f + 0.12f * kotlin.math.cos(t + 0.6f))) { drawCircle(orb2, r2, Offset.Zero) }
            translate(w * (0.45f + 0.2f * kotlin.math.sin(t + 3.1f)), h * (0.85f + 0.06f * kotlin.math.cos(t + 2.2f))) { drawCircle(orb3, r3, Offset.Zero) }
            drawRect(fade)
            if (m > 0f) drawRect(Color.Black.copy(alpha = 0.34f * m))
        }
    })
}

@Composable
private fun AuraTopBar(onCollapse: () -> Unit, onMore: () -> Unit) {
    val stream by Playback.stream.collectAsState()
    val still = reducedMotion()
    val colI = remember { MutableInteractionSource() }
    val moreI = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).staggerIn(0), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).pressScale(colI, 0.86f).liquidGlass(CircleShape).clickable(colI, null, onClickLabel = "Collapse player") { onCollapse() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player")
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedContent(stream?.info?.source?.label ?: "Finding the best source…", transitionSpec = { MotionSpec.fadeThrough(still) }, label = "auraSrc") {
                Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(44.dp).pressScale(moreI, 0.86f).liquidGlass(CircleShape).clickable(moreI, null, onClickLabel = "More options") { onMore() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MoreHoriz, "More options")
        }
    }
}

/** Floating artwork: breathes while playing, mirrored reflection below, swipe to skip, double-tap to like. */
@Composable
private fun AuraCover(url: String?, visible: Boolean, onBounds: (Rect) -> Unit, modifier: Modifier, reflection: Boolean) {
    val st by Graph.player.state.collectAsState()
    val still = reducedMotion()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(if (st.playing) 1f else 0.86f, if (still) snap() else spring(dampingRatio = 0.78f, stiffness = 170f), label = "auraScale") // 1.0.8: was 70
    // v2.0.14: unconditional loop; 0..1 ping-pong over 4.2s (tween default easing).
    // 1.0.5: the breathing phase was read here (`by` + breathe in composition), recomposing the whole cover (artwork,
    // reflection, gestures) every frame while playing. It is now read only inside the two graphicsLayers below.
    val breatheLoop = com.ravi.musicspot.ui.motion.rememberLoopPhase(running = !still && st.playing, periodMs = 4200, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val breathing = !(still || !st.playing)
    val dx = remember { Animatable(0f) }
    val heart = remember { Animatable(0f) }
    val glow = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = if (reflection) minOf(maxWidth, maxHeight * 0.82f) else minOf(maxWidth, maxHeight)
        val sidePx = with(LocalDensity.current) { side.toPx() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                val halo = remember(glow) { Brush.radialGradient(listOf(glow.copy(alpha = 0.40f), Color.Transparent)) } // 1.0.5: remembered
                Box(Modifier.size(side).graphicsLayer { val breathe = if (breathing) breatheLoop.value else 0f; val s = (1.12f + 0.04f * breathe) * scale; scaleX = s; scaleY = s; alpha = if (visible) 0.7f else 0f }
                    .background(halo))
                Artwork(url, Modifier.size(side).onGloballyPositioned { onBounds(it.boundsInRoot()) }
                    .pointerInput(sidePx) { // v2.0.14: keyed on sidePx
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val v = dx.value
                                scope.launch {
                                    if (kotlin.math.abs(v) > sidePx * 0.28f) {
                                        haptics.click()
                                        dx.animateTo(if (v < 0) -sidePx * 1.2f else sidePx * 1.2f, tween(220, easing = MotionSpec.emphasizedAccelerate))
                                        if (v < 0) Graph.player.next() else Graph.player.previous()
                                        dx.snapTo(if (v < 0) sidePx * 0.7f else -sidePx * 0.7f)
                                    }
                                    dx.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 220f))
                                }
                            },
                            onDragCancel = { scope.launch { dx.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 220f)) } },
                        ) { ch, amount -> ch.consume(); scope.launch { dx.snapTo(dx.value + amount) } }
                    }
                    .pointerInput(st.current?.id) {
                        detectTapGestures(
                            onDoubleTap = {
                                val tr = st.current ?: return@detectTapGestures
                                haptics.confirm()
                                if (Graph.library.data.value.liked.none { it.id == tr.id }) Graph.library.setLiked(tr, true)
                                scope.launch { heart.snapTo(0f); heart.animateTo(1f, tween(800)); heart.snapTo(0f) }
                            },
                            onLongPress = { st.current?.let { haptics.confirm(); Menus.track = it } },
                        )
                    }
                    .graphicsLayer {
                        val f = (if (sidePx > 0f) dx.value / sidePx else 0f).coerceIn(-1f, 1f) // v2.0.14
                        translationX = dx.value; rotationY = -f * 22f; cameraDistance = 14f * density
                        val breathe = if (breathing) breatheLoop.value else 0f // 1.0.5: read in the layer only
                        val s = scale * (1f + 0.015f * breathe) * (1f - 0.1f * kotlin.math.abs(f))
                        scaleX = s; scaleY = s; alpha = if (visible) 1f - 0.45f * kotlin.math.abs(f) else 0f
                        shadowElevation = (if (st.playing) 18 else 6).dp.toPx(); shape = ART; clip = true // 1.0.8 lighter: was 30/8 dp
                        spotShadowColor = glow; ambientShadowColor = glow
                    }, ART, "Album art. Swipe for next or previous, double-tap to like, long-press for options")
                // 1.0.5: only "showing or not" is observed in composition; the burst value is read in the layer.
                val heartOn by remember { androidx.compose.runtime.derivedStateOf { heart.value > 0f } }
                if (heartOn) Icon(Icons.Rounded.Favorite, null, Modifier.size(side / 3).graphicsLayer {
                    val h = heart.value
                    val pop = if (h < 0.3f) h / 0.3f * 1.2f else 1.2f - 0.2f * ((h - 0.3f) / 0.7f)
                    scaleX = pop; scaleY = pop; alpha = if (h < 0.7f) 1f else 1f - (h - 0.7f) / 0.3f
                }, tint = Color.White)
            }
            if (reflection) {
                // iOS-style mirrored reflection, fading out downward.
                Box(Modifier.size(side, side * 0.16f).clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                    .graphicsLayer { alpha = if (visible) 0.22f * scale else 0f; translationX = dx.value }) {
                    Artwork(url, Modifier.size(side).graphicsLayer { scaleY = -1f; translationY = -side.toPx() * 0.84f }, RectangleShape)
                    val bgC = MaterialTheme.colorScheme.background
                    val mirrorFade = remember(bgC) { Brush.verticalGradient(listOf(Color.Transparent, bgC)) } // 1.0.5: remembered
                    Box(Modifier.fillMaxSize().background(mirrorFade))
                }
            }
        }
    }
}

/** The liquid-glass control deck. */
@Composable
private fun AuraDeck(onOpenSleep: () -> Unit, onOpenEq: () -> Unit, onPane: (Pane) -> Unit, openPane: Pane?, modifier: Modifier = Modifier, hideCapsule: Boolean = false, onOpenSpeed: () -> Unit = {}) {
    val st by Graph.player.state.collectAsState()
    // v2.0.14b: position is collected inside AuraSeekBar so the deck doesn't recompose 4x/sec.
    val lib by Graph.library.data.collectAsState()
    val engine by EngineAudioProcessor.status.collectAsState()
    val err by Playback.error.collectAsState()
    val sleepTick by Playback.sleepTick.collectAsState()
    val t = st.current ?: return
    val haptics = rememberHaptics()
    val still = reducedMotion()
    val liked = lib.liked.any { it.id == t.id }
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth().staggerIn(1).liquidGlass(DECK).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(st.index to t, modifier = Modifier.weight(1f), label = "auraTitle", contentKey = { it.second.id },
                transitionSpec = { MotionSpec.sharedAxisX(forward = targetState.first >= initialState.first, still = still) }) { (_, tt) ->
                Column(Modifier.fillMaxWidth()) {
                    com.ravi.musicspot.ui.components.TitleWithLossless(tt.id) { m -> // 1.0.7: glowing "LOSSLESS" by the name
                        Text(tt.title, m, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(tt.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val likeI = remember { MutableInteractionSource() }
            val pop by animateFloatAsState(if (liked) 1.12f else 1f, if (still) snap() else spring(dampingRatio = 0.4f, stiffness = 200f), label = "auraLike")
            Box(Modifier.size(40.dp).graphicsLayer { scaleX = pop; scaleY = pop }.pressScale(likeI, 0.85f).liquidGlass(CircleShape)
                .clickable(likeI, null, onClickLabel = if (liked) "Unlike" else "Like") { haptics.confirm(); Graph.library.setLiked(t, !liked) }, contentAlignment = Alignment.Center) {
                AnimatedContent(liked, transitionSpec = { iconSwap(still) }, label = "auraLikeSwap") { on ->
                    Icon(if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (on) primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        // 1.0.8: hidden only while Motion Lyrics are actually on screen (Canvas songs and songs without synced lyrics keep it).
        if (openPane == null && !hideCapsule) LyricsCapsule(onOpen = { onPane(Pane.LYRICS) }, modifier = Modifier.padding(top = 6.dp))
        if (err != null) Text("Couldn't play · tap Retry in ⋯ or skip", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp).clickable { Graph.player.retry() }, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        AuraSeekBar(st.durationMs) // v2.0.14b
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            AuraModeChip(Icons.Rounded.Shuffle, st.shuffle, if (st.shuffle) "Shuffle on" else "Shuffle off") { haptics.tick(); Graph.player.setShuffle(!st.shuffle) }
            val prevI = remember { MutableInteractionSource() }
            val nextI = remember { MutableInteractionSource() }
            val playI = remember { MutableInteractionSource() }
            val prevDown by prevI.collectIsPressedAsState()
            val nextDown by nextI.collectIsPressedAsState()
            val nudge = if (still) snap<Float>() else spring(dampingRatio = 0.45f, stiffness = 220f)
            val prevX by animateFloatAsState(if (prevDown && !still) -8f else 0f, nudge, label = "auraPrev")
            val nextX by animateFloatAsState(if (nextDown && !still) 8f else 0f, nudge, label = "auraNext")
            Box(Modifier.size(50.dp).graphicsLayer { translationX = prevX.dp.toPx() }.pressScale(prevI, 0.86f).clip(CircleShape)
                .clickable(prevI, null, onClickLabel = "Previous") { haptics.click(); Graph.player.previous() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(32.dp))
            }
            AuraPlayButton(st.playing, st.buffering, still, playI, primary, haptics) // 1.0.5: own scope, see AuraPlayButton
            Box(Modifier.size(50.dp).graphicsLayer { translationX = nextX.dp.toPx() }.pressScale(nextI, 0.86f).clip(CircleShape)
                .clickable(nextI, null, onClickLabel = "Next") { haptics.click(); Graph.player.next() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(32.dp))
            }
            val repOn = st.repeat != M3.REPEAT_MODE_OFF
            AuraModeChip(if (st.repeat == M3.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, repOn,
                when (st.repeat) { M3.REPEAT_MODE_ONE -> "Repeat one"; M3.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" }) { haptics.tick(); Graph.player.cycleRepeat() }
        }
        Spacer(Modifier.height(6.dp))
        val sleepOn = sleepTick >= 0 && Graph.player.sleepActive
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AuraChip(Icons.Rounded.Lyrics, "Lyrics", openPane == Pane.LYRICS) { onPane(Pane.LYRICS) }
            AuraChip(Icons.AutoMirrored.Rounded.PlaylistPlay, "Up next", openPane == Pane.QUEUE) { onPane(Pane.QUEUE) }
            // 1.0.7: Related (and Credits, via the pane switcher) lost their entry point when Queue/Lyrics went full screen.
            AuraChip(Icons.Rounded.Explore, "Related", openPane == Pane.RELATED) { onPane(Pane.RELATED) }
            AuraChip(Icons.Rounded.Bedtime, if (sleepOn) sleepLabelPublic() else "Sleep", sleepOn) { onOpenSleep() }
            AuraChip(Icons.Rounded.Equalizer, if (engine.eqActive) "EQ on" else "Sound", engine.eqActive) { onOpenEq() }
            val sp by Graph.player.speed.collectAsState() // 1.0.10: real speed control (was an unused Ic.Speed icon)
            AuraChip(com.ravi.musicspot.ui.Ic.Speed, if (sp.speed != 1f) com.ravi.musicspot.playback.speedLabel(sp.speed) else "Speed", sp.speed != 1f) { onOpenSpeed() }
        }
    }
}

/**
 * 1.0.5: the deck's play button, moved out of AuraDeck unchanged. Its corner and size springs are read in composition
 * (shape, size), so they used to recompose the whole glass deck for every frame of the morph; now only this button.
 */
@Composable
private fun AuraPlayButton(playing: Boolean, buffering: Boolean, still: Boolean, playI: MutableInteractionSource, primary: Color, haptics: com.ravi.musicspot.ui.motion.Haptics) {
    // Play: squircle while playing, circle when paused (M3 expressive shape morph), glowing in the song colour.
    val corner by animateFloatAsState(if (playing) 30f else 50f, if (still) snap() else spring(dampingRatio = 0.6f, stiffness = 320f), label = "auraPlayMorph")
    val size by animateDpAsState(if (playing) 64.dp else 60.dp, if (still) snap() else spring(dampingRatio = 0.65f, stiffness = 320f), label = "auraPlaySize")
    Box(contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(corner.toInt().coerceIn(0, 50))
        val playFill = remember(primary) { Brush.verticalGradient(listOf(primary, androidx.compose.ui.graphics.lerp(primary, Color.Black, 0.18f))) } // 1.0.5: remembered
        Box(Modifier.size(size).pressScale(playI, 0.9f)
            .graphicsLayer { shadowElevation = (if (playing) 24 else 10).dp.toPx(); this.shape = shape; clip = true; spotShadowColor = primary; ambientShadowColor = primary }
            .background(playFill)
            .clickable(playI, null, onClickLabel = if (playing) "Pause" else "Play") { haptics.click(); Graph.player.toggle() }, contentAlignment = Alignment.Center) {
            val onP = if ((primary.red * 0.299f + primary.green * 0.587f + primary.blue * 0.114f) > 0.6f) Color.Black else Color.White
            com.ravi.musicspot.ui.motion.PlayPauseIcon(playing, Modifier.size(32.dp), tint = onP)
        }
        if (buffering) CircularProgressIndicator(Modifier.size(size + 6.dp), strokeWidth = 2.dp)
    }
}

/** Thick expressive seek bar: 6 dp at rest, swelling to 14 dp while touched, with a glowing played part. */
@Composable
private fun AuraSeekBar(durationMs: Long) {
    // 1.0.5: position is no longer read in this body (which owns the gestures); the fill and the times read it in
    // their own leaf scopes, so a tick re-lays out only the played part and re-composes only the time texts.
    val posState = Graph.player.position.collectAsState() // v2.0.14b: collected here, not in AuraDeck
    val still = reducedMotion()
    val dur = durationMs.coerceAtLeast(1)
    var scrub by remember { mutableFloatStateOf(-1f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val active = scrub >= 0f
    val h by animateDpAsState(if (active) 12.dp else 5.dp, if (still) snap() else spring(dampingRatio = 0.7f, stiffness = 380f), label = "auraSeekH")
    val fracOf: () -> Float = { if (scrub >= 0f) scrub else (posState.value.toFloat() / dur).coerceIn(0f, 1f) } // 1.0.5
    // v2.0.14: dragging flag keeps tap-release from resetting scrub mid-drag; live fraction/duration via rememberUpdatedState.
    var dragging by remember { mutableStateOf(false) }
    val fracNow by androidx.compose.runtime.rememberUpdatedState(fracOf)
    val durNow by androidx.compose.runtime.rememberUpdatedState(durationMs)
    fun seekTo(f: Float) { val d = durNow; if (d > 0) Graph.player.seek((f * d).toLong().coerceIn(0L, d)) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Column {
        Box(Modifier.fillMaxWidth().height(22.dp)
            .onGloballyPositioned { widthPx = it.size.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { // v2.0.14
                detectTapGestures(onPress = { o -> scrub = (o.x / widthPx).coerceIn(0f, 1f); val ok = tryAwaitRelease(); if (!dragging) { if (ok && scrub >= 0f) seekTo(scrub); scrub = -1f } })
            }
            .pointerInput(Unit) { // v2.0.14
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; scrub = (o.x / widthPx).coerceIn(0f, 1f) },
                    onDragEnd = { if (scrub >= 0f) seekTo(scrub); scrub = -1f; dragging = false },
                    onDragCancel = { scrub = -1f; dragging = false },
                ) { ch, amount -> ch.consume(); scrub = ((if (scrub < 0f) fracNow() else scrub) + amount / widthPx).coerceIn(0f, 1f) }
            }, contentAlignment = Alignment.CenterStart) {
            val played = remember(primary) { Brush.horizontalGradient(listOf(primary.copy(alpha = 0.75f), primary)) } // 1.0.5: remembered
            Box(Modifier.fillMaxWidth().height(h).clip(PILL).background(track)) {
                AuraSeekFill(fracOf, played) // 1.0.5: leaf
            }
        }
        Row(Modifier.fillMaxWidth()) {
            // 1.0.5: leaf texts; same strings as before.
            PlayerTickText({ formatTime(if (scrub >= 0f) (scrub * dur).toLong() else posState.value) }, MaterialTheme.typography.labelSmall, MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            PlayerTickText({ "-" + formatTime((durationMs - (if (scrub >= 0f) (scrub * dur).toLong() else posState.value)).coerceAtLeast(0)) }, MaterialTheme.typography.labelSmall, MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 1.0.5: the played part of the seek bar in its own scope (same Box as before); [fracOf] is read only here. */
@Composable
private fun AuraSeekFill(fracOf: () -> Float, brush: Brush) {
    // 1.0.7: the played part is drawn, not laid out. fracOf() is read only inside the draw lambda, so a position tick
    // (or a scrub frame) invalidates this one draw node: no recomposition, no measure/layout of the seek bar.
    // inset() narrows the DrawScope to the played width, so the gradient spans the played part exactly as the old
    // fillMaxWidth(frac) Box did, with the same pill ends (radius = half the height).
    Box(Modifier.fillMaxSize().drawBehind {
        val w = size.width * fracOf().coerceIn(0f, 1f)
        if (w > 0f) inset(0f, 0f, size.width - w, 0f) {
            drawRoundRect(brush, cornerRadius = CornerRadius(size.height / 2f, size.height / 2f))
        }
    })
}

@Composable
private fun AuraModeChip(icon: ImageVector, on: Boolean, label: String, onClick: () -> Unit) {
    val still = reducedMotion()
    val i = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val a by animateFloatAsState(if (on) 1f else 0f, if (still) snap() else MotionSpec.popSpring(), label = "auraMode")
    val tint by animateColorAsState(if (on) primary else MaterialTheme.colorScheme.onSurfaceVariant, if (still) snap() else tween(MotionSpec.MEDIUM), label = "auraModeTint")
    Box(Modifier.size(40.dp).pressScale(i, 0.85f).clip(CircleShape).clickable(i, null, onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = a; scaleX = 0.4f + 0.6f * a; scaleY = 0.4f + 0.6f * a }.clip(CircleShape).background(primary.copy(alpha = 0.18f)))
        AnimatedContent(icon, transitionSpec = { iconSwap(still) }, label = "auraModeIcon") { ic -> Icon(ic, label, tint = tint) }
    }
}

/** A glass chip that expands its label when active (M3 expressive): icon only otherwise. */
@Composable
private fun AuraChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val still = reducedMotion()
    val i = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(if (active) primary.copy(alpha = 0.22f) else Color.Transparent, if (still) snap() else tween(MotionSpec.MEDIUM), label = "auraChipBg")
    Row(Modifier.height(34.dp).pressScale(i, 0.9f).liquidGlass(PILL).background(bg, PILL)
        .clickable(i, null, onClickLabel = label) { onClick() }
        .animateContentSizeSafe(still).padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, Modifier.size(18.dp), tint = if (active) primary else MaterialTheme.colorScheme.onSurface)
        if (active) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private fun Modifier.animateContentSizeSafe(still: Boolean): Modifier =
    if (still) this else this.then(Modifier.animateContentSize(spring(dampingRatio = 0.85f, stiffness = 380f)))

@Composable
private fun AuraMiniHeader(onClose: () -> Unit) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    val still = reducedMotion()
    val haptics = rememberHaptics()
    val primary = MaterialTheme.colorScheme.primary
    // v2.0.14b: progress fraction moved into AuraMiniProgress.
    // v2.0.11: compact mini player (56 dp) with a hairline progress along its bottom edge.
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).liquidGlass(RoundedCornerShape(20.dp))
        .clickable(onClickLabel = "Back to player") { onClose() }) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.artUrl, Modifier.size(40.dp), RoundedCornerShape(11.dp))
            Spacer(Modifier.width(10.dp))
            AnimatedContent(t, contentKey = { it.id }, transitionSpec = { MotionSpec.sharedAxisX(true, still) }, label = "auraHdr", modifier = Modifier.weight(1f)) { tt ->
                Column {
                    com.ravi.musicspot.ui.components.TitleWithLossless(tt.id) { m ->
                        Text(tt.title, m, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(tt.artist, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val prevI = remember { MutableInteractionSource() }
            Box(Modifier.size(36.dp).pressScale(prevI, 0.86f).clip(CircleShape).clickable(prevI, null, onClickLabel = "Previous") { haptics.click(); Graph.player.previous() },
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(22.dp)) }
            val playI = remember { MutableInteractionSource() }
            Box(Modifier.size(38.dp).pressScale(playI, 0.86f).clip(CircleShape).background(primary)
                .clickable(playI, null, onClickLabel = if (st.playing) "Pause" else "Play") { haptics.click(); Graph.player.toggle() }, contentAlignment = Alignment.Center) {
                com.ravi.musicspot.ui.motion.PlayPauseIcon(st.playing, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            val nextI = remember { MutableInteractionSource() }
            Box(Modifier.size(36.dp).pressScale(nextI, 0.86f).clip(CircleShape).clickable(nextI, null, onClickLabel = "Next") { haptics.click(); Graph.player.next() },
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(22.dp)) }
        }
        AuraMiniProgress(st.durationMs, Modifier.align(Alignment.BottomStart)) // v2.0.14b
    }
}

/** v2.0.14b: hairline progress of the mini header; the only place that reads position, so only it recomposes. */
@Composable
private fun AuraMiniProgress(durationMs: Long, modifier: Modifier = Modifier) {
    // 1.0.7: the position State is read only in the draw lambda (was read in composition + fillMaxWidth(frac), i.e. a
    // recomposition and a re-layout 4x a second). Same hairline, same pill ends, same colours.
    val posState = Graph.player.position.collectAsState()
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.padding(horizontal = 18.dp).fillMaxWidth().height(2.dp).clip(PILL)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        .drawBehind {
            val frac = if (durationMs > 0) (posState.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            if (frac > 0f) drawRoundRect(primary, size = Size(size.width * frac, size.height), cornerRadius = CornerRadius(size.height / 2f, size.height / 2f))
        })
}

@Composable
private fun AuraPaneCard(p: Pane, t: com.ravi.musicspot.model.Track, onCollapse: () -> Unit, onNavigate: (com.ravi.musicspot.ui.Route) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp).liquidGlass(DECK)) {
        when (p) {
            Pane.QUEUE -> QueuePane()
            Pane.LYRICS -> LyricsPane()
            Pane.RELATED -> RelatedPane(onNavigate = { onCollapse(); onNavigate(it) })
            Pane.CREDITS -> CreditsPane(t)
        }
    }
}

/**
 * v2.0.11 pane switcher for Aura: a compact floating glass bar. A round "back to artwork" button sits on the left; the
 * pane tabs share one glass pill, and the active tab stretches (weight spring) into a glowing lens showing its label,
 * with a small dot under it. Inactive tabs are icon only. 48 dp tall.
 */
@Composable
private fun AuraPaneSwitcher(open: Pane, onSelect: (Pane) -> Unit, onClose: () -> Unit) {
    val still = reducedMotion()
    val haptics = rememberHaptics()
    val entries = Pane.entries
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        val backI = remember { MutableInteractionSource() }
        Box(Modifier.size(48.dp).pressScale(backI, 0.86f).liquidGlass(CircleShape).clickable(backI, null, onClickLabel = "Back to artwork") { haptics.tick(); onClose() },
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.KeyboardArrowDown, "Back to artwork", Modifier.size(22.dp)) }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f).fillMaxHeight().liquidGlass(PILL).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            entries.forEach { e ->
                val on = e == open
                val w by animateFloatAsState(if (on) 2.4f else 1f, if (still) snap() else spring(dampingRatio = 0.85f, stiffness = 380f), label = "auraSwW")
                val lens by animateFloatAsState(if (on) 1f else 0f, if (still) snap() else MotionSpec.popSpring(), label = "auraSwLens")
                val fg by animateColorAsState(if (on) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), if (still) snap() else tween(MotionSpec.MEDIUM), label = "auraSwFg")
                val i = remember { MutableInteractionSource() }
                Box(Modifier.weight(w).fillMaxHeight().pressScale(i, 0.9f).clip(PILL)
                    .clickable(i, null, onClickLabel = e.label) { if (!on) haptics.tick(); onSelect(e) }, contentAlignment = Alignment.Center) {
                    val lensFill = remember(primary) { Brush.verticalGradient(listOf(primary.copy(alpha = 0.30f), primary.copy(alpha = 0.12f))) } // 1.0.5: remembered
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = lens; scaleX = 0.7f + 0.3f * lens; scaleY = 0.8f + 0.2f * lens }.clip(PILL)
                        .background(lensFill))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(e.icon, e.label, Modifier.size(19.dp), tint = fg)
                        if (on) {
                            Spacer(Modifier.width(5.dp))
                            Text(e.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, softWrap = false,
                                modifier = Modifier.graphicsLayer { alpha = lens })
                        }
                    }
                }
            }
        }
    }
}
