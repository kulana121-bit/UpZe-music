// 1.0.1: full-screen Queue / Lyrics pane of Now Playing.
package com.ravi.musicspot.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.motion.MotionSpec
import com.ravi.musicspot.ui.motion.growFrom
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.rememberHaptics
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 1.0.1: the panes that open full screen, in pager order. Related and Credits keep their in-player panes. */
internal val FullPanes = listOf(Pane.LYRICS, Pane.QUEUE, Pane.RELATED) // 1.0.7: chip order (Lyrics, Up next, Related); Related is full screen too

/** 1.0.1: true for the panes shown by [FullScreenPane]. */
internal fun Pane?.isFull(): Boolean = this == Pane.QUEUE || this == Pane.LYRICS || this == Pane.RELATED

/** 1.0.1: the pane an in-player layout should show: Related/Credits only (Queue/Lyrics are full screen). */
internal fun Pane?.inPlayer(): Pane? = if (this.isFull()) null else this

/** 1.0.1: pure gesture rule (unit-tested): did a vertical drag on the player end as a "swipe up"? */
internal fun isSwipeUp(totalDy: Float, velocityY: Float, distancePx: Float, flingPx: Float): Boolean =
    totalDy < -distancePx || (totalDy < -distancePx / 4f && velocityY < -flingPx)

/** 1.0.1: pure dismiss rule (unit-tested) for the pane's pull-down. */
internal fun shouldDismissPane(offsetPx: Float, heightPx: Float, velocityY: Float, flingPx: Float): Boolean =
    offsetPx > heightPx * 0.22f || (offsetPx > 0f && velocityY > flingPx)

/** 1.0.2: an interactive swipe-up past this much of the screen opens the pane on release (a fast flick opens sooner). */
internal const val OPEN_AT = 0.32f

/** 1.0.2: pure release rule (unit-testable) for the interactive swipe-up. */
internal fun shouldOpenPane(progress: Float, totalDy: Float, velocityY: Float, distancePx: Float, flingPx: Float): Boolean =
    velocityY < flingPx && (progress > OPEN_AT || isSwipeUp(totalDy, velocityY, distancePx, flingPx))

/**
 * 1.0.2: the swipe-up as a continuous gesture. While the finger moves up, the Queue / Lyrics page rises with it as a
 * rounded card and the player sinks back behind it; on release it springs open or falls back. [progress] is written
 * by the gesture and the settle spring, and read only in graphicsLayer blocks, so dragging never recomposes.
 */
@androidx.compose.runtime.Stable
class PaneReveal {
    /** 0 = closed, 1 = fully open. */
    var progress by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** True while the swipe owns the pane: finger down, then the settle after release. */
    var active by androidx.compose.runtime.mutableStateOf(false)
    /** Bumped on each release; [openOnRelease] says which way to settle. */
    var releases by mutableIntStateOf(0)
        private set
    var openOnRelease = false
        private set
    fun begin() { progress = 0f; active = true }
    fun release(open: Boolean) { openOnRelease = open; releases++ }
}

@Composable
fun rememberPaneReveal(): PaneReveal = remember { PaneReveal() }

/**
 * 1.0.2: the player sinking back while the pane covers it: scales to 93%, rises 32 dp, dims, rounds its corners.
 * Follows the finger during a swipe-up and springs with the pane when it opens from a button. Draw-phase only.
 */
@Composable
fun Modifier.paneDepth(reveal: PaneReveal?, open: Boolean): Modifier {
    val still = reducedMotion()
    val opened by androidx.compose.animation.core.animateFloatAsState(
        if (open) 1f else 0f,
        if (still) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.9f, stiffness = 480f),
        label = "paneDepth",
    )
    if (still) return this
    return this.graphicsLayer {
        val d = (if (reveal?.active == true) reveal.progress else opened).coerceIn(0f, 1f)
        if (d > 0.001f) {
            val sc = 1f - 0.07f * d
            scaleX = sc; scaleY = sc
            translationY = -32.dp.toPx() * d
            alpha = 1f - 0.45f * d
            shape = androidx.compose.foundation.shape.RoundedCornerShape((32f * d).coerceAtLeast(0f).dp)
            clip = true
        }
    }
}

/**
 * 1.0.1: the player body's vertical gestures. Dragging down still moves the whole player (collapse to the mini
 * player, via [onDrag]/[onDragEnd]); a swipe or fling up opens the full-screen pane ([onSwipeUp]) and leaves the
 * player where it is. Horizontal gestures (artwork skip) are untouched: this only reacts after vertical touch slop.
 * 1.0.2: with a [reveal], an upward drag opens the pane immediately and drives it with the finger (see [PaneReveal]).
 * The pointer handler is no longer restarted when [enabled] changes, so opening the pane mid-drag keeps the gesture.
 */
@Composable
internal fun Modifier.playerVerticalGestures(
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onSwipeUp: () -> Unit,
    reveal: PaneReveal? = null,
): Modifier {
    val en by rememberUpdatedState(enabled)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val swipeUp by rememberUpdatedState(onSwipeUp)
    return this.pointerInput(reveal) {
        val distance = 56.dp.toPx()
        val fling = 900.dp.toPx()
        var total = 0f
        var up = 0f
        var blocked = false
        var rising = false
        val tracker = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = { total = 0f; up = 0f; rising = false; blocked = !en; tracker.resetTracking() },
            onDragEnd = {
                if (blocked) return@detectVerticalDragGestures
                val v = tracker.calculateVelocity().y
                when {
                    rising && reveal != null -> { reveal.release(shouldOpenPane(reveal.progress, up, v, distance, fling)); if (total > 0f) dragEnd(0f) }
                    isSwipeUp(up, v, distance, fling) -> { dragEnd(0f); swipeUp() }
                    else -> dragEnd(total)
                }
            },
            onDragCancel = { if (rising) reveal?.release(false); if (!blocked) dragEnd(0f) },
        ) { change, dy ->
            if (blocked) return@detectVerticalDragGestures
            tracker.addPosition(change.uptimeMillis, change.position)
            up += dy
            if (reveal != null && (rising || (total <= 0f && up < 0f))) {
                if (!rising) { rising = true; reveal.begin(); swipeUp() }
                reveal.progress = (-up / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                return@detectVerticalDragGestures
            }
            // Only downward movement drives the player sheet; an upward drag is a pane gesture, so the sheet stays put.
            val next = (total + dy).coerceAtLeast(0f)
            val applied = next - total
            total = next
            if (applied != 0f) drag(applied)
        }
    }
}

/** 1.0.2: the pane's entrance clock (0..1), read by [cascade] while drawing. 1 when there is no entrance running. */
internal val LocalPaneEntrance = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * 1.0.2 Material "cascade": the element rises 28 dp, scales up from 96% and fades in with the emphasized-decelerate
 * curve once the pane's entrance clock passes its [slot] (45 ms of the 1 s clock per slot). Draw-phase only, so it
 * costs no recomposition; does nothing once the clock reaches 1.
 */
internal fun Modifier.cascade(clock: () -> Float, slot: Float): Modifier = this.graphicsLayer {
    val t = clock()
    if (t >= 1f) return@graphicsLayer
    val local = ((t - slot.coerceIn(0f, 12f) * 0.04f) / 0.5f).coerceIn(0f, 1f) // 1.0.8: same cascade on a 600 ms clock
    val e = MotionSpec.emphasizedDecelerate.transform(local)
    alpha = e
    translationY = (1f - e) * 28.dp.toPx()
    val sc = 0.96f + 0.04f * e
    scaleX = sc; scaleY = sc
}

/**
 * 1.0.1: full-screen Queue / Lyrics pane, drawn above the player (call it after the player's own layout, inside
 * the same parent Box). A HorizontalPager with a tab indicator that follows the finger switches between Queue and
 * Lyrics; dragging down on the header, or pulling down past the top of a list, closes it (it follows the finger);
 * Back closes it through the player's own BackHandler. Opens on the page of [pane]; [onSelect] reports page
 * changes so the player's buttons stay in sync. Works the same in portrait and landscape.
 */
@Composable
fun FullScreenPane(pane: Pane?, onSelect: (Pane) -> Unit, onClose: () -> Unit, reveal: PaneReveal? = null, onNavigate: (com.ravi.musicspot.ui.Route) -> Unit = {}) {
    val still = reducedMotion()
    val visible = pane.isFull()
    val rising = reveal?.active == true
    val haptics = rememberHaptics()
    val close by rememberUpdatedState(onClose)
    // 1.0.2: settle the interactive swipe-up: spring fully open, or fall back and close.
    if (reveal != null) {
        LaunchedEffect(reveal.releases) {
            if (!reveal.active || reveal.releases == 0) return@LaunchedEffect
            val open = reveal.openOnRelease
            androidx.compose.animation.core.animate(reveal.progress, if (open) 1f else 0f,
                animationSpec = if (still) androidx.compose.animation.core.snap() else spring(dampingRatio = if (open) 0.84f else 0.92f, stiffness = if (open) 520f else 620f, visibilityThreshold = 0.001f),
            ) { v, _ -> reveal.progress = v }
            if (!open) close()
            reveal.active = false
        }
        // A tick as the finger crosses the open point (and again if it drops back below it).
        LaunchedEffect(reveal) {
            var prev: Boolean? = null
            snapshotFlow { if (reveal.active) reveal.progress > OPEN_AT else null }
                .collect { now -> if (prev != null && now != null && now != prev) haptics.tick(); prev = now }
        }
        LaunchedEffect(visible) { if (!visible && reveal.active && reveal.releases > 0) reveal.active = false }
    }
    // Opened from a button (Up next, Lyrics, the ⋮ menu): grow out of it (container transform) and shrink back into it.
    // Opened by the swipe up: rise from the bottom. Lite-effects devices always use the plain rise / fade.
    val held = remember { arrayOfNulls<Offset>(1) }
    val wasVisible = remember { BooleanArray(1) }
    if (visible && !wasVisible[0]) held[0] = if (!rising && !still && !com.ravi.musicspot.Graph.liteFx && com.ravi.musicspot.ui.motion.TapOrigin.freshTap()) com.ravi.musicspot.ui.motion.TapOrigin.point() else null
    wasVisible[0] = visible
    AnimatedVisibility(visible = visible, enter = androidx.compose.animation.EnterTransition.None, exit = androidx.compose.animation.ExitTransition.None) {
        val tap = held[0]
        val motion = if (rising) Modifier.graphicsLayer {
                // 1.0.2: the page follows the finger as a rounded card that straightens out as it fills the screen.
                val p = reveal!!.progress
                translationY = (1f - p) * size.height
                val sc = 0.9f + 0.1f * p
                scaleX = sc; scaleY = sc
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                shape = androidx.compose.foundation.shape.RoundedCornerShape((36f * (1f - p)).coerceAtLeast(0f).dp) // 1.0.4: spring overshoot (p > 1) made this negative and crashed
                clip = true
                alpha = (0.55f + p * 1.5f).coerceAtMost(1f)
            }
            else if (tap != null) Modifier.animateEnterExit(enter = fadeIn(tween(MotionSpec.SHORT)), exit = fadeOut(tween(MotionSpec.EXIT, delayMillis = 40)))
                .then(Modifier.growFrom(this, tap, 0.5f))
            else Modifier.animateEnterExit(
                enter = if (still) fadeIn(tween(120)) else slideInVertically(spring(dampingRatio = MotionSpec.SURFACE_DAMPING, stiffness = MotionSpec.SURFACE_STIFFNESS, visibilityThreshold = IntOffset(1, 1))) { it } + fadeIn(tween(MotionSpec.SHORT)),
                exit = if (still) fadeOut(tween(100)) else slideOutVertically(MotionSpec.exitTween()) { it } + fadeOut(MotionSpec.exitTween()),
            )
        FullPaneContent(initial = pane ?: Pane.QUEUE, target = pane, visible = visible, onSelect = onSelect, onClose = onClose, onNavigate = onNavigate, modifier = motion,
            ready = { reveal?.active != true || reveal.progress > 0.55f })
    }
}

@Composable
private fun FullPaneContent(initial: Pane, target: Pane?, visible: Boolean, onSelect: (Pane) -> Unit, onClose: () -> Unit, onNavigate: (com.ravi.musicspot.ui.Route) -> Unit, modifier: Modifier = Modifier, ready: () -> Boolean = { true }) {
    val scope = rememberCoroutineScope()
    // 1.0.2: entrance clock for the cascade (header, tabs, then rows / lyric lines). Starts once the page is mostly up.
    val stillIn = reducedMotion()
    val entrance = remember { androidx.compose.animation.core.Animatable(if (stillIn) 1f else 0f) }
    val readyNow by rememberUpdatedState(ready)
    LaunchedEffect(Unit) {
        if (entrance.value >= 1f) return@LaunchedEffect
        snapshotFlow { readyNow() }.first { it }
        entrance.animateTo(1f, tween(600, easing = androidx.compose.animation.core.LinearEasing)) // 1.0.8: was 1000 ms
    }
    val clock: () -> Float = remember { { entrance.value } }
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val flingPx = with(density) { 1200.dp.toPx() }
    val pager = rememberPagerState(initialPage = FullPanes.indexOf(initial).coerceAtLeast(0)) { FullPanes.size }
    // Drag offset: plain float state written by gestures (no coroutine per event); read only in offset{}/graphicsLayer.
    var offset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var heightPx by remember { mutableIntStateOf(1) }
    val latestVisible by rememberUpdatedState(visible)
    val latestTarget by rememberUpdatedState(target)
    val select by rememberUpdatedState(onSelect)
    val close by rememberUpdatedState(onClose)

    // Buttons elsewhere (e.g. the Lyrics capsule while the pane is open) move the pager to their page.
    LaunchedEffect(target) {
        val i = FullPanes.indexOf(target)
        if (i >= 0 && i != pager.currentPage && !pager.isScrollInProgress) pager.animateScrollToPage(i)
    }
    // Swiping between pages reports the new pane, so the player's tab/chip highlight follows.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { p -> if (latestVisible) FullPanes.getOrNull(p)?.let { if (it != latestTarget) select(it) } }
    }

    fun settle(velocity: Float) {
        scope.launch {
            if (shouldDismissPane(offset, heightPx.toFloat(), velocity, flingPx)) { haptics.tick(); close() }
            else androidx.compose.animation.core.animate(offset, 0f, animationSpec = spring(dampingRatio = 0.86f, stiffness = 560f)) { v, _ -> offset = v }
        }
    }
    // Pull-down past the top of the Queue/Lyrics list drags the pane; scrolling back up first takes the pane back.
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f && offset > 0f) {
                    val used = maxOf(available.y, -offset)
                    offset += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    offset += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset > 0f) { settle(available.y); return available }
                return Velocity.Zero
            }
        }
    }
    val dragHeader = rememberDraggableState { d -> offset = (offset + d).coerceAtLeast(0f) }

    val c = MaterialTheme.colorScheme
    androidx.compose.runtime.CompositionLocalProvider(LocalPaneEntrance provides clock) {
    Surface(
        modifier.fillMaxSize()
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            // 1.0.2: pulling down shrinks the page into a rounded card that follows the finger (draw phase only).
            .graphicsLayer {
                val f = (offset / heightPx).coerceIn(0f, 1f)
                translationY = offset
                alpha = 1f - f * 0.35f
                if (f > 0f) {
                    val sc = 1f - 0.1f * f
                    scaleX = sc; scaleY = sc
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                    shape = androidx.compose.foundation.shape.RoundedCornerShape((36f * (f * 4f).coerceIn(0f, 1f)).dp)
                    clip = true
                }
            }
            // Swallow taps so nothing under the pane (the player) reacts.
            .pointerInput(Unit) { detectTapGestures { } },
        color = c.background, contentColor = c.onBackground,
    ) {
        val wash = remember(c.primary, c.background) { Brush.verticalGradient(listOf(c.primary.copy(alpha = 0.14f), c.background.copy(alpha = 0f))) } // 1.0.5: remembered
        Box(Modifier.fillMaxSize().background(wash)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Header: handle + current song + close. The whole header is a drag-down-to-close area.
                Column(
                    Modifier.fillMaxWidth().draggable(dragHeader, Orientation.Vertical, onDragStopped = { v -> settle(v) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.padding(top = 8.dp).cascade(clock, 0f).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.onSurfaceVariant.copy(alpha = 0.45f)))
                    Box(Modifier.cascade(clock, 0.5f)) { PaneHeader(onClose = { haptics.tick(); close() }) }
                    Box(Modifier.cascade(clock, 1.5f)) {
                        PaneTabBar({ pager.currentPage + pager.currentPageOffsetFraction }, onTab = { i -> haptics.tick(); scope.launch { pager.animateScrollToPage(i) } })
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    HorizontalPager(
                        state = pager, beyondViewportPageCount = 0, // 1.0.8 lighter: only the visible page is kept composed (was 1)
                        modifier = Modifier.fillMaxHeight().widthIn(max = 820.dp).fillMaxWidth().nestedScroll(pull),
                        key = { FullPanes[it].name },
                    ) { p ->
                        // 1.0.2: the page being swiped away recedes (scales to 92%, fades) as the next one slides in.
                        Box(Modifier.fillMaxSize().graphicsLayer {
                            val off = kotlin.math.abs((pager.currentPage - p) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                            val sc = 1f - 0.08f * off
                            scaleX = sc; scaleY = sc
                            alpha = 1f - 0.5f * off
                        }) {
                            when (FullPanes[p]) {
                                Pane.LYRICS -> LyricsPane()
                                Pane.RELATED -> RelatedPane(onNavigate) // 1.0.7: third full-screen page
                                else -> QueuePane()
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun PaneHeader(onClose: () -> Unit) {
    val st by Graph.player.state.collectAsState()
    val t = st.current
    Row(Modifier.fillMaxWidth().widthIn(max = 820.dp).padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
        if (t != null) {
            Artwork(t.artUrl, Modifier.size(44.dp), Ms.r12)
            Spacer(Modifier.width(12.dp))
            val still = reducedMotion()
            AnimatedContent(t, contentKey = { it.id }, transitionSpec = { MotionSpec.fadeThrough(still) }, label = "fullPaneHdr", modifier = Modifier.weight(1f)) { tt ->
                Column(Modifier.fillMaxWidth()) {
                    com.ravi.musicspot.ui.components.TitleWithLossless(tt.id) { m ->
                        Text(tt.title, m, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(tt.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            FilledIconButton(onClick = { Graph.player.toggle() }) { com.ravi.musicspot.ui.motion.PlayPauseIcon(st.playing) }
        } else Spacer(Modifier.weight(1f))
    }
}

/**
 * Two tabs (Queue, Lyrics) with an indicator that slides with the pager, including mid-swipe. [position] is read only
 * in the layout phase (indicator offset), so a swipe does not recompose the bar every frame; labels recompose only
 * when the nearest page changes.
 */
/** 1.0.5: tab labels of [PaneTabBar], hoisted out of composition. */
private val PaneTabLabels = listOf("Lyrics", "Queue", "Related") // 1.0.7: matches FullPanes

@Composable
private fun PaneTabBar(position: () -> Float, onTab: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    val labels = PaneTabLabels // 1.0.5: shared constant instead of a new list on every recomposition
    val selected by remember { androidx.compose.runtime.derivedStateOf { position().roundToInt().coerceIn(0, labels.size - 1) } }
    BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).height(44.dp)) {
        val tabW = maxWidth / labels.size
        Box(Modifier.fillMaxSize().clip(Ms.pill).background(c.surfaceContainerHigh.copy(alpha = 0.7f)))
        Box(
            Modifier.width(tabW).fillMaxHeight()
                .offset { IntOffset((tabW.toPx() * position().coerceIn(0f, (labels.size - 1).toFloat())).roundToInt(), 0) }
                .padding(3.dp).clip(Ms.pill).background(c.primary.copy(alpha = 0.22f)),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                val tint by androidx.compose.animation.animateColorAsState(if (on) c.primary else c.onSurfaceVariant, label = "paneTab")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(Ms.pill).clickable(onClickLabel = label) { onTab(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = tint)
                }
            }
        }
    }
}
