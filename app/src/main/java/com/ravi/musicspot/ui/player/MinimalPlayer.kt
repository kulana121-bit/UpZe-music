// v2.0.16: MINIMAL player redesigned as "Poster (big art)". File + entry function name kept (AppRoot calls MinimalPlayerScreen).
package com.ravi.musicspot.ui.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.ravi.musicspot.Graph
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.EngineAudioProcessor
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.formatTime
import com.ravi.musicspot.ui.glass.LocalBackdrop
import com.ravi.musicspot.ui.glass.LocalHazeState
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.motion.MotionSpec
import com.ravi.musicspot.ui.motion.PlayPauseIcon
import com.ravi.musicspot.ui.motion.iconSwap
import com.ravi.musicspot.ui.motion.pressScale
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.rememberHaptics
import com.ravi.musicspot.ui.motion.rememberLoopPhase
import com.ravi.musicspot.ui.theme.Ms
import com.ravi.musicspot.ui.theme.readable
import com.ravi.musicspot.ui.theme.readableOn
import kotlinx.coroutines.launch
import androidx.media3.common.Player as M3

// v2.0.16: Poster palette.
private val PosterInk = Color(0xFFF6F3EF)
private val PosterInkSoft = Color(0xFFF6F3EF).copy(alpha = 0.66f)
private val PosterHair = Color(0xFFF6F3EF).copy(alpha = 0.16f)
private val TitleShadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 14f)

/**
 * v2.0.16: "Poster (big art)" Now Playing (Settings → Appearance → Now Playing design).
 *  - top ~58%: the artwork edge-to-edge (square crop, no corners) fading into the background colour;
 *  - over the art's lower edge: a huge bold title (2 lines max, auto-shrinks) and the artist;
 *  - a big circular play button inside a circular progress ring (tap/drag the ring to seek), glass prev/next,
 *    like / shuffle / repeat chips, elapsed / remaining;
 *  - the same bottom tabs as before (Up next, Lyrics, Related, Credits, Sleep, Sound). A pane replaces the poster.
 * Swipe the art sideways for next/previous, double-tap to like, long-press for options; swipe down to collapse.
 */
@Composable
fun MinimalPlayerScreen(
    progress: () -> Float,
    onCollapse: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    coverVisible: Boolean,
    onCoverBounds: (Rect) -> Unit,
    onOpenEq: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    var pane by rememberSaveable { mutableStateOf<Pane?>(null) }
    // 1.0.1: last full-screen page (Queue/Lyrics), reopened by a swipe up.
    var lastFull by rememberSaveable { mutableStateOf(Pane.QUEUE) }
    androidx.compose.runtime.LaunchedEffect(pane) { pane?.let { if (it.isFull()) lastFull = it } }
    var sleepSheet by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val still = reducedMotion()
    androidx.activity.compose.BackHandler(enabled = true) { if (pane != null) pane = null else onCollapse() }

    val ctx = LocalContext.current
    var colors by remember { mutableStateOf<Pair<Color, Color>?>(null) }
    LaunchedEffect(t.artUrl) { colors = runCatching { com.ravi.musicspot.ui.theme.artPalette(ctx, t.artUrl) }.getOrNull() ?: colors }
    val bgTarget = lerp(colors?.second ?: Color(0xFF2A2A30), Color.Black, 0.55f)
    val bg by animateColorAsState(bgTarget, if (still) snap() else tween(1200), label = "posterBg")
    val accentRaw = colors?.first ?: MaterialTheme.colorScheme.primary
    val accent = readable(lerp(accentRaw, Color.White, 0.15f), bg, 4.5)

    val scheme = MaterialTheme.colorScheme.copy(
        primary = accent, onPrimary = readableOn(accent),
        background = bg, onBackground = PosterInk,
        surface = bg, onSurface = PosterInk, onSurfaceVariant = PosterInkSoft,
        surfaceContainerHigh = lerp(bg, Color.White, 0.08f), surfaceContainerHighest = lerp(bg, Color.White, 0.12f),
        error = Color(0xFFF2B8B5),
    )
    val tabs: @Composable () -> Unit = {
        PosterTabs(pane, onSelect = { pane = if (pane == it) null else it }, onOpenSleep = { sleepSheet = true }, onOpenEq = onOpenEq)
    }

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides PosterInk, LocalBackdrop provides null, LocalHazeState provides null) {
            Box(
                Modifier.fillMaxSize().background(bg)
                    .playerVerticalGestures(enabled = pane == null, onDrag = onDrag, onDragEnd = onDragEnd, onSwipeUp = { pane = lastFull }), // 1.0.1: swipe up = full-screen pane
            ) {
                if (landscape) {
                    Row(Modifier.fillMaxSize()) {
                        PosterArt(t, coverVisible, onCoverBounds, bg, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true), horizontalFade = true)
                        Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
                            PosterTopBar(onCollapse, onMore = { Menus.track = t })
                            AnimatedContent(pane.inPlayer(), transitionSpec = { MotionSpec.fadeThrough(still) }, label = "posterLand", modifier = Modifier.weight(1f).fillMaxWidth()) { p ->
                                if (p == null) BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                                    val ring = (maxHeight * 0.42f).coerceIn(72.dp, 120.dp)
                                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                                        PosterTitleBlock(t, onCollapse, onNavigate, maxSize = 40.sp)
                                        PosterTransport(ring)
                                        PosterChips(t)
                                    }
                                } else PosterPaneBody(p, t, onCollapse, onNavigate, Modifier.fillMaxSize())
                            }
                            tabs()
                        }
                    }
                } else {
                    AnimatedContent(pane.inPlayer(), transitionSpec = {
                        when {
                            initialState == null && targetState != null -> MotionSpec.containerRise(open = true, still = still)
                            initialState != null && targetState == null -> MotionSpec.containerRise(open = false, still = still)
                            else -> MotionSpec.fadeThrough(still)
                        }
                    }, label = "posterPane", modifier = Modifier.fillMaxSize()) { p ->
                        if (p == null) BoxWithConstraints(Modifier.fillMaxSize()) {
                            val artH = maxHeight * 0.58f
                            Column(Modifier.fillMaxSize()) {
                                PosterArt(t, coverVisible, onCoverBounds, bg, Modifier.fillMaxWidth().height(artH)) {
                                    Box(Modifier.align(Alignment.TopCenter).statusBarsPadding()) { PosterTopBar(onCollapse, onMore = { Menus.track = t }) }
                                    Box(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 4.dp)) {
                                        PosterTitleBlock(t, onCollapse, onNavigate, maxSize = 52.sp)
                                    }
                                }
                                Column(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                                        val ring = (maxHeight * 0.52f).coerceIn(72.dp, 132.dp)
                                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                                            PosterTransport(ring)
                                            PosterChips(t)
                                        }
                                    }
                                    tabs()
                                }
                            }
                        } else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            PosterTopBar(onCollapse, onMore = { Menus.track = t })
                            PosterPaneHeader(onClose = { pane = null })
                            PosterPaneBody(p, t, onCollapse, onNavigate, Modifier.weight(1f).fillMaxWidth())
                            tabs()
                        }
                    }
                }
            }
            // 1.0.1: Queue / Lyrics open full screen over the player (pager + tabs, swipe down or Back to close).
            FullScreenPane(pane, onSelect = { pane = it }, onClose = { pane = null })
            SleepSheet(sleepSheet) { sleepSheet = false }
        }
    }
}

@Composable
private fun PosterTopBar(onCollapse: () -> Unit, onMore: () -> Unit) {
    val stream by Playback.stream.collectAsState()
    val still = reducedMotion()
    val colI = remember { MutableInteractionSource() }
    val moreI = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).pressScale(colI, 0.86f).clip(CircleShape).clickable(colI, null, onClickLabel = "Collapse player") { onCollapse() },
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.KeyboardArrowDown, "Collapse player", Modifier.size(28.dp), tint = PosterInk) }
        Spacer(Modifier.width(4.dp))
        AnimatedContent(stream?.info?.source?.label ?: "Finding the best source…", transitionSpec = { MotionSpec.fadeThrough(still) }, label = "posterSrc",
            modifier = Modifier.weight(1f)) {
            Text(it.uppercase(), style = MaterialTheme.typography.labelSmall.copy(shadow = TitleShadow), letterSpacing = 1.5.sp, color = PosterInkSoft,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).pressScale(moreI, 0.86f).clip(CircleShape).clickable(moreI, null, onClickLabel = "More options") { onMore() },
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.MoreHoriz, "More options", tint = PosterInk) }
    }
}

/**
 * v2.0.16: edge-to-edge square-cropped artwork (no corners) fading into [bg]. Swipe = skip (guarded against a
 * zero width), double-tap = like, long-press = options; slow Ken Burns drift while playing unless reduce-motion.
 * Reports the (unclipped, square) art bounds for the cover morph.
 */
@Composable
private fun PosterArt(
    t: Track, visible: Boolean, onBounds: (Rect) -> Unit, bg: Color, modifier: Modifier,
    horizontalFade: Boolean = false, overlay: @Composable BoxScope.() -> Unit = {},
) {
    val st by Graph.player.state.collectAsState()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    val dx = remember { Animatable(0f) }
    val heart = remember { Animatable(0f) }
    val drift by rememberLoopPhase(running = !still && st.playing, periodMs = 12_000, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val stillNow by rememberUpdatedState(still)
    fun settle() = scope.launch { if (stillNow) dx.snapTo(0f) else dx.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 160f)) }
    BoxWithConstraints(
        modifier.clipToBounds()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = dx.value
                        val w = size.width.toFloat()
                        if (w > 0f && kotlin.math.abs(v) > w * 0.25f) { // v2.0.16: guard /0
                            haptics.click()
                            if (v < 0) Graph.player.next() else Graph.player.previous()
                        }
                        settle()
                    },
                    onDragCancel = { settle() },
                ) { ch, amount -> ch.consume(); scope.launch { dx.snapTo(dx.value + amount) } }
            }
            .pointerInput(t.id) {
                detectTapGestures(
                    onDoubleTap = {
                        haptics.confirm()
                        if (Graph.library.data.value.liked.none { it.id == t.id }) Graph.library.setLiked(t, true)
                        scope.launch { if (stillNow) { heart.snapTo(1f); kotlinx.coroutines.delay(500); heart.snapTo(0f) } else { heart.snapTo(0f); heart.animateTo(1f, tween(900)); heart.snapTo(0f) } }
                    },
                    onLongPress = { haptics.confirm(); Menus.track = t },
                )
            },
    ) {
        val side = maxOf(maxWidth, maxHeight)
        val wPx = constraints.maxWidth.toFloat()
        Artwork(t.artUrl, Modifier.align(Alignment.Center).requiredSize(side)
            .onGloballyPositioned { c -> onBounds(Rect(c.positionInRoot(), c.size.toSize())) }
            .graphicsLayer {
                val f = (if (wPx > 0f) dx.value / wPx else 0f).coerceIn(-1f, 1f) // v2.0.16: guard /0
                translationX = dx.value * 0.45f
                val s = 1f + 0.04f * drift
                scaleX = s; scaleY = s
                alpha = if (visible) 1f - 0.45f * kotlin.math.abs(f) else 0f
            }, RectangleShape, "Album art. Swipe for next or previous, double-tap to like, long-press for options")
        // Fade into the background (down in portrait, right in landscape) plus a light scrim under the top bar.
        Box(Modifier.matchParentSize().background(
            if (horizontalFade) Brush.horizontalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 0.85f to bg.copy(alpha = 0.8f), 1f to bg)
            else Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.16f to Color.Transparent, 0.42f to Color.Transparent, 0.74f to bg.copy(alpha = 0.78f), 1f to bg),
        ))
        val h = heart.value
        if (h > 0f) Icon(Icons.Rounded.Favorite, null, Modifier.align(Alignment.Center).size(minOf(maxWidth, maxHeight) / 4).graphicsLayer {
            val pop = if (h < 0.3f) h / 0.3f * 1.2f else 1.2f - 0.2f * ((h - 0.3f) / 0.7f)
            scaleX = pop; scaleY = pop; alpha = if (h < 0.7f) 1f else 1f - (h - 0.7f) / 0.3f
        }, tint = Color.White)
        overlay()
    }
}

/** Huge title (auto-shrinks to fit 2 lines) and the artist (tap to open them). */
@Composable
private fun PosterTitleBlock(t: Track, onCollapse: () -> Unit, onNavigate: (Route) -> Unit, maxSize: TextUnit) {
    val st by Graph.player.state.collectAsState()
    val err by Playback.error.collectAsState()
    val still = reducedMotion()
    Column(Modifier.fillMaxWidth()) {
        AnimatedContent(st.index to t, label = "posterTitle", contentKey = { it.second.id },
            transitionSpec = { MotionSpec.sharedAxisX(forward = targetState.first >= initialState.first, still = still) }) { (_, tt) ->
            Column(Modifier.fillMaxWidth()) {
                PosterFitTitle(tt.title, maxSize)
                Spacer(Modifier.height(4.dp))
                val aid = tt.artistId
                Text(tt.artist, style = MaterialTheme.typography.titleMedium.copy(shadow = TitleShadow), fontWeight = FontWeight.SemiBold, color = PosterInkSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (aid != null) Modifier.clickable(onClickLabel = "Go to artist") { onCollapse(); onNavigate(Route.Artist(aid, tt.artist)) } else Modifier)
            }
        }
        if (err != null) Text("Couldn't play · tap to retry", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp).clickable(onClickLabel = "Retry") { Graph.player.retry() }, maxLines = 1)
    }
}

/** Bold display title: starts at [max] and steps down (to 24 sp) until it fits in two lines; hidden until it fits. */
@Composable
private fun PosterFitTitle(text: String, max: TextUnit) {
    val min = 24.sp
    var size by remember(text, max) { mutableStateOf(max) }
    var ready by remember(text, max) { mutableStateOf(false) }
    Text(
        text,
        style = TextStyle(shadow = TitleShadow),
        fontSize = size,
        lineHeight = size * 1.02f,
        fontWeight = FontWeight.Black,
        letterSpacing = (-1).sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = PosterInk,
        onTextLayout = { r ->
            if (r.hasVisualOverflow && size.value > min.value) size = (size.value * 0.9f).coerceAtLeast(min.value).sp
            else ready = true
        },
        modifier = Modifier.fillMaxWidth().drawWithContent { if (ready) drawContent() },
    )
}

/** Glass prev · ring-wrapped play · glass next, with elapsed / remaining underneath. */
@Composable
private fun PosterTransport(ringSize: Dp) {
    val st by Graph.player.state.collectAsState()
    val haptics = rememberHaptics()
    PosterProgress(
        durationMs = st.durationMs, playing = st.playing, buffering = st.buffering, ringSize = ringSize,
        prev = { PosterRoundButton(Icons.Outlined.SkipPrevious, "Previous") { haptics.click(); Graph.player.previous() } },
        next = { PosterRoundButton(Icons.Outlined.SkipNext, "Next") { haptics.click(); Graph.player.next() } },
    )
}

@Composable
private fun PosterRoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val i = remember { MutableInteractionSource() }
    Box(Modifier.size(56.dp).pressScale(i, 0.86f).glass(CircleShape).clickable(i, null, onClickLabel = label) { onClick() },
        contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(28.dp), tint = PosterInk) }
}

/**
 * v2.0.16: the progress ring around the play button. Position is collected only here. Touch the ring (outside the
 * play button) to seek there; keep dragging around it to scrub (no wrap past 12 o'clock). dragging flag + durationMs>0 guard.
 */
@Composable
private fun PosterProgress(durationMs: Long, playing: Boolean, buffering: Boolean, ringSize: Dp, prev: @Composable () -> Unit, next: @Composable () -> Unit) {
    val pos by Graph.player.position.collectAsState()
    val still = reducedMotion()
    val haptics = rememberHaptics()
    val dur = durationMs.coerceAtLeast(1)
    var scrub by remember { mutableFloatStateOf(-1f) }
    var dragging by remember { mutableStateOf(false) }
    val active = scrub >= 0f
    val frac = if (active) scrub else (pos.toFloat() / dur).coerceIn(0f, 1f)
    val durNow by rememberUpdatedState(durationMs)
    fun seekTo(f: Float) { val d = durNow; if (d > 0) Graph.player.seek((f * d).toLong().coerceIn(0L, d)) }
    val spin by rememberLoopPhase(running = buffering && !still, periodMs = 1100, reverse = false)
    val primary = MaterialTheme.colorScheme.primary
    val thumbR by animateFloatAsState(if (active) 1.5f else 1f, if (still) snap() else spring(dampingRatio = 0.6f, stiffness = 400f), label = "posterThumb")
    val playFrac = 0.66f

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            prev()
            Box(Modifier.size(ringSize).pointerInput(Unit) {
                fun angleFrac(o: Offset): Float {
                    val deg = Math.toDegrees(kotlin.math.atan2((o.x - size.width / 2f).toDouble(), (size.height / 2f - o.y).toDouble())).toFloat()
                    return ((deg + 360f) % 360f) / 360f
                }
                fun inRing(o: Offset): Boolean {
                    val r = minOf(size.width, size.height) / 2f
                    if (r <= 0f) return false
                    val d = kotlin.math.hypot(o.x - size.width / 2f, o.y - size.height / 2f)
                    return d > r * playFrac && d <= r * 1.15f
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!inRing(down.position) || durNow <= 0) return@awaitEachGesture
                    down.consume()
                    dragging = true
                    scrub = angleFrac(down.position)
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            ch.consume()
                            val f = angleFrac(ch.position)
                            val prevF = if (scrub < 0f) f else scrub
                            scrub = if (kotlin.math.abs(f - prevF) > 0.5f) (if (prevF > 0.5f) 1f else 0f) else f
                        }
                        if (scrub >= 0f) { haptics.tick(); seekTo(scrub) }
                    } finally {
                        scrub = -1f; dragging = false
                    }
                }
            }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = size.minDimension * 0.05f
                    val inset = stroke * thumbR
                    val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                    val tl = Offset(inset, inset)
                    drawArc(PosterHair, 0f, 360f, false, tl, arcSize, style = Stroke(stroke))
                    drawArc(primary, -90f, 360f * frac, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (buffering && !still) drawArc(PosterInk.copy(alpha = 0.55f), -90f + 360f * spin, 42f, false, tl, arcSize, style = Stroke(stroke * 0.6f, cap = StrokeCap.Round))
                    val a = Math.toRadians((-90f + 360f * frac).toDouble())
                    val r = arcSize.width / 2f
                    drawCircle(PosterInk, stroke * 0.85f * thumbR, Offset(center.x + r * kotlin.math.cos(a).toFloat(), center.y + r * kotlin.math.sin(a).toFloat()))
                }
                val playI = remember { MutableInteractionSource() }
                Box(Modifier.size(ringSize * playFrac).pressScale(playI, 0.9f).clip(CircleShape).background(primary)
                    .clickable(playI, null, onClickLabel = if (playing) "Pause" else "Play") { haptics.click(); Graph.player.toggle() },
                    contentAlignment = Alignment.Center) {
                    PlayPauseIcon(playing, Modifier.size(ringSize * 0.28f), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            next()
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val shown = if (active) (scrub * dur).toLong() else pos
            Text(if (durationMs > 0) formatTime(shown) else "--:--", style = MaterialTheme.typography.labelMedium, color = if (active) PosterInk else PosterInkSoft)
            Spacer(Modifier.weight(1f))
            Text(if (durationMs > 0) "-" + formatTime((durationMs - shown).coerceAtLeast(0L)) else "--:--", style = MaterialTheme.typography.labelMedium, color = PosterInkSoft)
        }
    }
}

/** Like, shuffle and repeat as glass chips. */
@Composable
private fun PosterChips(t: Track) {
    val st by Graph.player.state.collectAsState()
    val lib by Graph.library.data.collectAsState()
    val haptics = rememberHaptics()
    val liked = lib.liked.any { it.id == t.id }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        PosterChip(if (liked) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder, if (liked) "Liked" else "Like", liked) { haptics.confirm(); Graph.library.setLiked(t, !liked) }
        PosterChip(Icons.Outlined.Shuffle, "Shuffle", st.shuffle) { haptics.tick(); Graph.player.setShuffle(!st.shuffle) }
        PosterChip(if (st.repeat == M3.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
            when (st.repeat) { M3.REPEAT_MODE_ONE -> "Repeat one"; M3.REPEAT_MODE_ALL -> "Repeat"; else -> "Repeat" },
            st.repeat != M3.REPEAT_MODE_OFF) { haptics.tick(); Graph.player.cycleRepeat() }
    }
}

@Composable
private fun PosterChip(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val still = reducedMotion()
    val i = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val tint by animateColorAsState(if (active) primary else PosterInk, if (still) snap() else tween(MotionSpec.MEDIUM), label = "posterChipTint")
    val fill = if (active) lerp(MaterialTheme.colorScheme.surfaceContainerHigh, primary, 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh
    Row(Modifier.pressScale(i, 0.94f).glass(Ms.pill, tint = fill)
        .clickable(i, null, onClickLabel = if (active) "$label on" else "$label off") { onClick() }
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(icon, transitionSpec = { iconSwap(still) }, label = "posterChipSwap") { ic -> Icon(ic, null, Modifier.size(18.dp), tint = tint) }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, softWrap = false)
    }
}

/** Small text tabs: the panes (toggle; active one underlined) then Sleep and Sound. Scrolls if it doesn't fit. */
@Composable
private fun PosterTabs(open: Pane?, onSelect: (Pane) -> Unit, onOpenSleep: () -> Unit, onOpenEq: () -> Unit) {
    val haptics = rememberHaptics()
    val engine by EngineAudioProcessor.status.collectAsState()
    val sleepTick by Playback.sleepTick.collectAsState()
    val sleepOn = sleepTick >= 0 && Graph.player.sleepActive
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Pane.entries.forEach { p -> PosterTab(p.label, p == open) { haptics.tick(); onSelect(p) } }
        Box(Modifier.padding(horizontal = 6.dp).size(width = 1.dp, height = 14.dp).background(PosterHair))
        PosterTab(if (sleepOn) "Sleep · on" else "Sleep", sleepOn) { onOpenSleep() }
        PosterTab(if (engine.eqActive) "EQ on" else "Sound", engine.eqActive) { onOpenEq() }
    }
}

@Composable
private fun PosterTab(label: String, active: Boolean, onClick: () -> Unit) {
    val still = reducedMotion()
    val i = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val line by animateFloatAsState(if (active) 1f else 0f, if (still) snap() else tween(MotionSpec.SHORT), label = "posterTabLine")
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(i, null, onClickLabel = label) { onClick() }.padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) PosterInk else PosterInkSoft, maxLines = 1, softWrap = false)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.height(2.dp).width(18.dp).graphicsLayer { scaleX = line; alpha = line }.background(primary))
    }
}

@Composable
private fun PosterPaneHeader(onClose: () -> Unit) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    val still = reducedMotion()
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Back to player") { onClose() }.padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Artwork(t.artUrl, Modifier.size(48.dp), RectangleShape)
        Spacer(Modifier.width(14.dp))
        AnimatedContent(t, contentKey = { it.id }, transitionSpec = { MotionSpec.sharedAxisX(true, still) }, label = "posterHdr", modifier = Modifier.weight(1f)) { tt ->
            Column {
                Text(tt.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tt.artist, style = MaterialTheme.typography.bodySmall, color = PosterInkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val playI = remember { MutableInteractionSource() }
        Box(Modifier.size(44.dp).pressScale(playI, 0.86f).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .clickable(playI, null, onClickLabel = if (st.playing) "Pause" else "Play") { haptics.click(); Graph.player.toggle() }, contentAlignment = Alignment.Center) {
            PlayPauseIcon(st.playing, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(PosterHair))
}

@Composable
private fun PosterPaneBody(p: Pane, t: Track, onCollapse: () -> Unit, onNavigate: (Route) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        when (p) {
            Pane.QUEUE -> QueuePane()
            Pane.LYRICS -> LyricsPane()
            Pane.RELATED -> RelatedPane(onNavigate = { onCollapse(); onNavigate(it) })
            Pane.CREDITS -> CreditsPane(t)
        }
    }
}
