package com.ravi.musicspot.ui.chrome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.ui.Tab
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.glass.liquidGlass
import com.ravi.musicspot.ui.motion.MotionSpec
import com.ravi.musicspot.ui.motion.PlayPauseIcon
import com.ravi.musicspot.ui.motion.pressScale
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// v2.0.17: "Split (two islands)" dock geometry — one 56dp row: now-playing island (left) + nav island (right).
private val SPLIT_SIDE = 12.dp
private val SPLIT_GAP = 8.dp
private val SPLIT_H = 56.dp
private val SPLIT_COMPACT_H = 44.dp
private val SPLIT_SHAPE = RoundedCornerShape(28.dp)
private val SPLIT_SLOT = 48.dp
private val SPLIT_NAV_PAD = 6.dp
private const val SPLIT_NOW_FRAC = 0.6f
/** v2.0.17: the now-playing island's content is laid out at least this wide, so it clips (not squashes) while collapsing. */
private val SPLIT_NOW_MIN_CONTENT = 200.dp
/** v2.0.17: breathing room between the islands' top edge and the last list item. */
private val SPLIT_LIST_GAP = 12.dp

/**
 * v2.0.17: bottom padding lists need while the Split dock is on: CHROME_BOTTOM + the real island row height (56dp, the
 * same with or without a song, since both islands share one row) + a small gap. The nav-bar inset is added by the caller.
 */
@Suppress("UNUSED_PARAMETER")
fun edgeDockHeight(hasMini: Boolean): Dp = CHROME_BOTTOM + SPLIT_H + SPLIT_LIST_GAP

private fun splitLerpI(a: Int, b: Int, t: Float): Int = (a + (b - a) * t.coerceIn(0f, 1f)).toInt()

/**
 * v2.0.17: "Split (two islands)" dock (enum value stays EDGE). Two separate floating liquid-glass islands on one row
 * (12dp side margins, 8dp gap). LEFT ≈60%: now playing — 40dp circular cover inside a progress ring, ellipsized
 * title/artist, play/pause; tap → player, swipe up → player, swipe left/right → next/previous. RIGHT: icon-only nav
 * pill; the selected tab sits on an accent circle that springs in (snaps under reduced motion). With nothing playing
 * the left island collapses away and the nav island glides to the centre. Mini nav mode: with a song the nav island
 * folds into a 56dp circle showing the current tab (tap → expand) and the now-playing island takes the room; without a
 * song the nav pill shrinks to 44dp tall (tap → expand). Hide slides both off. Every animated value (morph, hide,
 * presence, playback position, swipe) is read only in layout / graphicsLayer / draw lambdas. Each island is its own
 * glass layer, clipped to its own shape; nothing glass is nested inside glass.
 */
@Composable
fun EdgeDock(
    tabs: List<Tab>,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    hasMini: Boolean,
    morph: () -> Float,
    hide: () -> Float,
    miniTarget: Boolean,
    onExpand: () -> Unit,
    hideDrop: Dp,
    onOpen: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    coverVisible: Boolean,
    onCoverBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val still = reducedMotion()
    val folded = miniTarget
    // 1 = now-playing island present, 0 = collapsed away. State read only in layout/graphicsLayer.
    val presence: State<Float> = animateFloatAsState(
        if (hasMini) 1f else 0f,
        if (still) snap() else spring(dampingRatio = 0.86f, stiffness = 380f), label = "splitPresence",
    )
    // How far the nav island has folded into its circle (only with a song).
    val circle: () -> Float = { (morph().coerceIn(0f, 1f) * presence.value).coerceIn(0f, 1f) }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Layout(
            content = {
                // index 0: nav island
                Box(
                    Modifier
                        .shadow(14.dp, SPLIT_SHAPE, clip = false, ambientColor = Color.Black.copy(alpha = 0.28f), spotColor = Color.Black.copy(alpha = 0.38f))
                        .liquidGlass(SPLIT_SHAPE)
                        .then(if (folded) Modifier.clickable(onClickLabel = "Show navigation") { haptics.tick(); onExpand() } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    SplitTabs(tabs, selected, onSelect, enabled = !folded, modifier = Modifier.graphicsLayer { alpha = (1f - circle() * 1.6f).coerceIn(0f, 1f) })
                    // Folded-circle face: just the current tab's icon.
                    Icon(selected.selectedIcon, null, Modifier.size(22.dp).graphicsLayer { alpha = ((circle() - 0.4f) / 0.6f).coerceIn(0f, 1f) },
                        tint = MaterialTheme.colorScheme.primary)
                }
                // index 1: now-playing island (always composed so it can animate in and out)
                Box(
                    Modifier
                        .graphicsLayer { alpha = presence.value.coerceIn(0f, 1f) }
                        .shadow(14.dp, SPLIT_SHAPE, clip = false, ambientColor = Color.Black.copy(alpha = 0.28f), spotColor = Color.Black.copy(alpha = 0.38f))
                        .liquidGlass(SPLIT_SHAPE),
                ) {
                    if (hasMini) SplitNowPlaying(onOpen, onDrag, onDragEnd, coverVisible, onCoverBounds)
                }
            },
            modifier = modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = SPLIT_SIDE)
                .graphicsLayer { translationY = hideDrop.toPx() * hide(); alpha = 1f - hide() },
        ) { ms, c ->
            val m = morph().coerceIn(0f, 1f)
            val p = presence.value.coerceIn(0f, 1f)
            val maxW = c.maxWidth
            val h = SPLIT_H.roundToPx()
            val gap = SPLIT_GAP.roundToPx()
            // Nav island alone (no song): natural icon width, centred; Mini mode shrinks its height.
            val navAloneW = min(maxW, (SPLIT_SLOT * tabs.size + SPLIT_NAV_PAD * 2).roundToPx())
            val navAloneH = splitLerpI(h, SPLIT_COMPACT_H.roundToPx(), m)
            // With a song: left ≈60%, nav takes the rest; Mini mode folds nav into a circle and left grows.
            val leftFull = (maxW * SPLIT_NOW_FRAC).toInt()
            val navWithW = splitLerpI(max(maxW - leftFull - gap, h), h, m)
            val leftWithW = maxW - navWithW - gap
            val navW = splitLerpI(navAloneW, navWithW, p).coerceAtLeast(1)
            val navH = splitLerpI(navAloneH, h, p).coerceAtLeast(1)
            val leftW = (leftWithW * p).toInt()
            val navP = ms[0].measure(Constraints.fixed(navW, navH))
            val leftP = if (leftW > 0) ms[1].measure(Constraints.fixed(leftW, h)) else null
            layout(maxW, h) {
                val navX = splitLerpI((maxW - navW) / 2, maxW - navW, p)
                navP.place(navX, h - navH)
                leftP?.place(0, 0)
            }
        }
    }
}

/** v2.0.17: icon-only tabs; selected tab on an accent circle with a little spring scale. */
@Composable
private fun SplitTabs(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val still = reducedMotion()
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxSize().liftBar().padding(horizontal = SPLIT_NAV_PAD), verticalAlignment = Alignment.CenterVertically) { // 1.0.1: liftBar
        tabs.forEach { t ->
            val on = t == selected
            val interaction = remember { MutableInteractionSource() }
            val dot = animateFloatAsState(if (on) 1f else 0f, if (still) snap() else spring(dampingRatio = 0.5f, stiffness = 500f), label = "splitDot")
            val fg by animateColorAsState(if (on) onPrimary else idle, if (still) snap() else tween(MotionSpec.SHORT), label = "splitFg")
            Box(
                Modifier.weight(1f).fillMaxHeight().pressScale(interaction, 0.88f)
                    .semantics { this.selected = on }
                    .then(if (enabled) Modifier.clickable(interaction, null, onClickLabel = t.label, role = Role.Tab) { if (!on) haptics.tick(); onSelect(t) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                // Accent circle, drawn in its own clipped layer; scale/alpha read only in the layer lambda.
                Box(
                    Modifier.size(38.dp)
                        .graphicsLayer { val d = dot.value; alpha = d.coerceIn(0f, 1f); scaleX = 0.5f + 0.5f * d; scaleY = 0.5f + 0.5f * d }
                        .clip(CircleShape).background(primary),
                )
                AnimatedContent(on, transitionSpec = { com.ravi.musicspot.ui.motion.iconSwap(still) }, label = "splitIcon") { o ->
                    Icon(if (o) t.selectedIcon else t.icon, t.label, Modifier.size(22.dp).liftSource(t, false, primary, dotAlpha = { 0f }, register = o == on), tint = fg) // 1.0.1: tab lift (accent circle marks the active tab)
                }
            }
        }
    }
}

@Composable
private fun SplitNowPlaying(
    onOpen: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    coverVisible: Boolean,
    onCoverBounds: (Rect) -> Unit,
) {
    val st by Graph.player.state.collectAsState()
    val err by Playback.error.collectAsState()
    val posState = Graph.player.position.collectAsState()
    val t = st.current ?: return
    val durationMs = st.durationMs
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    val swipe = remember { Animatable(0f) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Box(
        Modifier.fillMaxSize()
            .clickable(onClickLabel = "Open player") { onOpen() }
            // Swipe up → player (drives the sheet like the other docks).
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { onDragEnd(total) }, onDragCancel = { onDragEnd(0f) }) { _, dy -> total += dy; onDrag(dy) }
            }
            // Swipe left/right → next/previous.
            .pointerInput(t.id) {
                detectHorizontalDragGestures(onDragEnd = {
                    val v = swipe.value
                    scope.launch {
                        if (abs(v) > 120f) { haptics.click(); if (v < 0) Graph.player.next() else Graph.player.previous() }
                        swipe.animateTo(0f, if (still) snap() else spring(dampingRatio = 0.75f, stiffness = 180f))
                    }
                }) { _, dx -> scope.launch { swipe.snapTo(swipe.value + dx) } }
            },
    ) {
        Row(
            Modifier.fillMaxSize()
                // Lay out at a minimum width so collapsing clips the content instead of squashing it.
                .layout { mm, cc ->
                    val w = max(cc.maxWidth, SPLIT_NOW_MIN_CONTENT.roundToPx())
                    val pl = mm.measure(Constraints.fixed(w, cc.maxHeight))
                    layout(cc.maxWidth, cc.maxHeight) { pl.place(0, 0) }
                }
                .padding(start = 4.dp, end = 4.dp)
                .graphicsLayer { translationX = swipe.value * 0.5f; alpha = 1f - (abs(swipe.value) / 600f).coerceIn(0f, 0.5f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 40dp circular cover inside a progress ring; position read only in drawBehind.
            Box(
                Modifier.size(48.dp).drawBehind {
                    val sw = 2.5.dp.toPx()
                    val inset = sw / 2f
                    val arcSize = Size(size.width - sw, size.height - sw)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
                    val frac = if (durationMs > 0) (posState.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    if (frac > 0f) drawArc(primary, -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                },
                contentAlignment = Alignment.Center,
            ) {
                Artwork(t.artUrl, Modifier.size(40.dp).onGloballyPositioned { onCoverBounds(it.boundsInRoot()) }
                    .graphicsLayer { alpha = if (coverVisible) 1f else 0f }, CircleShape)
            }
            Spacer(Modifier.width(10.dp))
            AnimatedContent(t, contentKey = { it.id }, transitionSpec = { MotionSpec.sharedAxisX(true, still) }, label = "splitTitle", modifier = Modifier.weight(1f)) { tt ->
                Column {
                    Text(tt.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(err?.let { "Couldn't play · tap to retry" } ?: tt.artist, style = MaterialTheme.typography.bodySmall,
                        color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val ppI = remember { MutableInteractionSource() }
            Box(
                Modifier.size(44.dp).pressScale(ppI, 0.86f).clip(CircleShape)
                    .clickable(ppI, null, onClickLabel = if (st.playing) "Pause" else "Play") { haptics.click(); if (err != null) Graph.player.retry() else Graph.player.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (st.buffering) CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 2.dp)
                PlayPauseIcon(st.playing, Modifier.size(26.dp))
            }
        }
    }
}
