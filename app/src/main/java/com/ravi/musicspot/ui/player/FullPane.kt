// 1.0.1: full-screen Queue / Lyrics pane for every Now Playing design.
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 1.0.1: the panes that open full screen, in pager order. Related and Credits keep their in-player panes. */
internal val FullPanes = listOf(Pane.QUEUE, Pane.LYRICS)

/** 1.0.1: true for the panes shown by [FullScreenPane]. */
internal fun Pane?.isFull(): Boolean = this == Pane.QUEUE || this == Pane.LYRICS

/** 1.0.1: the pane an in-player layout should show: Related/Credits only (Queue/Lyrics are full screen). */
internal fun Pane?.inPlayer(): Pane? = if (this.isFull()) null else this

/** 1.0.1: pure gesture rule (unit-tested): did a vertical drag on the player end as a "swipe up"? */
internal fun isSwipeUp(totalDy: Float, velocityY: Float, distancePx: Float, flingPx: Float): Boolean =
    totalDy < -distancePx || (totalDy < -distancePx / 4f && velocityY < -flingPx)

/** 1.0.1: pure dismiss rule (unit-tested) for the pane's pull-down. */
internal fun shouldDismissPane(offsetPx: Float, heightPx: Float, velocityY: Float, flingPx: Float): Boolean =
    offsetPx > heightPx * 0.22f || (offsetPx > 0f && velocityY > flingPx)

/**
 * 1.0.1: the player body's vertical gestures. Dragging down still moves the whole player (collapse to the mini
 * player, via [onDrag]/[onDragEnd]); a swipe or fling up opens the full-screen pane ([onSwipeUp]) and leaves the
 * player where it is. Horizontal gestures (artwork skip) are untouched: this only reacts after vertical touch slop.
 */
internal fun Modifier.playerVerticalGestures(
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onSwipeUp: () -> Unit,
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    val distance = 56.dp.toPx()
    val fling = 900.dp.toPx()
    var total = 0f
    var up = 0f
    val tracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = { total = 0f; up = 0f; tracker.resetTracking() },
        onDragEnd = {
            val v = tracker.calculateVelocity().y
            if (isSwipeUp(up, v, distance, fling)) { onDragEnd(0f); onSwipeUp() } else onDragEnd(total)
        },
        onDragCancel = { onDragEnd(0f) },
    ) { change, dy ->
        tracker.addPosition(change.uptimeMillis, change.position)
        up += dy
        // Only downward movement drives the player sheet; an upward drag is a pane gesture, so the sheet stays put.
        val next = (total + dy).coerceAtLeast(0f)
        val applied = next - total
        total = next
        if (applied != 0f) onDrag(applied)
    }
}

/**
 * 1.0.1: full-screen Queue / Lyrics pane, drawn above the player (call it after the player's own layout, inside
 * the same parent Box). A HorizontalPager with a tab indicator that follows the finger switches between Queue and
 * Lyrics; dragging down on the header, or pulling down past the top of a list, closes it (it follows the finger);
 * Back closes it through the player's own BackHandler. Opens on the page of [pane]; [onSelect] reports page
 * changes so the player's buttons stay in sync. Works the same in portrait and landscape.
 */
@Composable
fun FullScreenPane(pane: Pane?, onSelect: (Pane) -> Unit, onClose: () -> Unit) {
    val still = reducedMotion()
    val visible = pane.isFull()
    // Opened from a button (Up next, Lyrics, the ⋮ menu): grow out of it (container transform) and shrink back into it.
    // Opened by the swipe up: rise from the bottom. Lite-effects devices always use the plain rise / fade.
    val held = remember { arrayOfNulls<Offset>(1) }
    val wasVisible = remember { BooleanArray(1) }
    if (visible && !wasVisible[0]) held[0] = if (!still && !com.ravi.musicspot.Graph.liteFx && com.ravi.musicspot.ui.motion.TapOrigin.freshTap()) com.ravi.musicspot.ui.motion.TapOrigin.point() else null
    wasVisible[0] = visible
    AnimatedVisibility(visible = visible, enter = androidx.compose.animation.EnterTransition.None, exit = androidx.compose.animation.ExitTransition.None) {
        val tap = held[0]
        val motion = if (tap != null) Modifier.animateEnterExit(enter = fadeIn(tween(MotionSpec.SHORT)), exit = fadeOut(tween(MotionSpec.EXIT, delayMillis = 40)))
                .then(Modifier.growFrom(this, tap, 0.5f))
            else Modifier.animateEnterExit(
                enter = if (still) fadeIn(tween(120)) else slideInVertically(spring(dampingRatio = MotionSpec.SURFACE_DAMPING, stiffness = MotionSpec.SURFACE_STIFFNESS, visibilityThreshold = IntOffset(1, 1))) { it } + fadeIn(tween(MotionSpec.SHORT)),
                exit = if (still) fadeOut(tween(100)) else slideOutVertically(MotionSpec.exitTween()) { it } + fadeOut(MotionSpec.exitTween()),
            )
        FullPaneContent(initial = pane ?: Pane.QUEUE, target = pane, visible = visible, onSelect = onSelect, onClose = onClose, modifier = motion)
    }
}

@Composable
private fun FullPaneContent(initial: Pane, target: Pane?, visible: Boolean, onSelect: (Pane) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
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
            else androidx.compose.animation.core.animate(offset, 0f, animationSpec = spring(dampingRatio = 0.86f, stiffness = 380f)) { v, _ -> offset = v }
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
    Surface(
        modifier.fillMaxSize()
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .offset { IntOffset(0, offset.roundToInt()) }
            .graphicsLayer { alpha = 1f - (offset / heightPx).coerceIn(0f, 1f) * 0.35f }
            // Swallow taps so nothing under the pane (the player) reacts.
            .pointerInput(Unit) { detectTapGestures { } },
        color = c.background, contentColor = c.onBackground,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.primary.copy(alpha = 0.14f), c.background.copy(alpha = 0f))))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Header: handle + current song + close. The whole header is a drag-down-to-close area.
                Column(
                    Modifier.fillMaxWidth().draggable(dragHeader, Orientation.Vertical, onDragStopped = { v -> settle(v) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.onSurfaceVariant.copy(alpha = 0.45f)))
                    PaneHeader(onClose = { haptics.tick(); close() })
                    PaneTabBar({ pager.currentPage + pager.currentPageOffsetFraction }, onTab = { i -> haptics.tick(); scope.launch { pager.animateScrollToPage(i) } })
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    HorizontalPager(
                        state = pager, beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxHeight().widthIn(max = 820.dp).fillMaxWidth().nestedScroll(pull),
                        key = { FullPanes[it].name },
                    ) { p ->
                        Box(Modifier.fillMaxSize()) {
                            when (FullPanes[p]) {
                                Pane.LYRICS -> LyricsPane()
                                else -> QueuePane()
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
                    Text(tt.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
@Composable
private fun PaneTabBar(position: () -> Float, onTab: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    val labels = listOf("Queue", "Lyrics")
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
