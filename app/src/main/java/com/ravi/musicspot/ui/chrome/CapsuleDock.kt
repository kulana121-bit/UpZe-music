package com.ravi.musicspot.ui.chrome

// v2.0.16: CAPSULE dock rewritten as the "Orb" dock — floating 64dp now-playing cover orb (progress ring, corner
// play/pause) at bottom-right plus a compact glass tab pill to its left. Public signature of CapsuleDock unchanged.

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.min

// v2.0.16: Orb dock metrics.
private val ORB_PILL_SHAPE = RoundedCornerShape(percent = 50)
private val ORB_SIZE = 64.dp
private val ORB_RING = 3.dp
private val ORB_PP = 26.dp
private val ORB_GAP = 10.dp
private val ORB_SIDE = 16.dp
private val ORB_PILL_H = 56.dp
private val ORB_PILL_COMPACT_H = 46.dp
private val ORB_SLOT_COMPACT = 44.dp
private val ORB_PAD = 6.dp

private fun orbLerpI(a: Int, b: Int, t: Float): Int = (a + (b - a) * t.coerceIn(0f, 1f)).toInt()

/**
 * v2.0.16: "Orb (floating cover)" dock (Settings → Appearance → Dock design). A 64dp floating now-playing orb at
 * bottom-right shows the cover inside a thin progress ring, with a small play/pause button over its corner; tap,
 * long-press or swipe up opens the player, swipe left/right skips. To its left a compact liquid-glass pill holds the
 * tab icons (selected tab on a filled accent background with a dot). With nothing playing the orb hides and the pill
 * spans the full width. Mini mode folds the pill away (orb stays) or, with no song, shrinks it to a compact pill
 * (tap → expand); Hide slides the dock off the bottom. Signature kept identical for AppRoot.
 */
@Composable
fun CapsuleDock(
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
    val folded = miniTarget
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Layout(
            content = {
                // v2.0.16: pill is always index 0; the orb (when a track is loaded) is index 1.
                Box(
                    Modifier
                        .graphicsLayer { alpha = if (hasMini) (1f - morph() * 1.6f).coerceIn(0f, 1f) else 1f }
                        .shadow(16.dp, ORB_PILL_SHAPE, clip = false, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.40f))
                        .liquidGlass(ORB_PILL_SHAPE)
                        .then(if (folded) Modifier.clickable(onClickLabel = "Show navigation") { haptics.tick(); onExpand() } else Modifier),
                ) {
                    OrbTabs(tabs, selected, onSelect, enabled = !folded)
                }
                if (hasMini) Box { OrbNowPlaying(onOpen, onDrag, onDragEnd, coverVisible, onCoverBounds) }
            },
            modifier = modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = ORB_SIDE)
                .graphicsLayer { translationY = hideDrop.toPx() * hide(); alpha = 1f - hide() },
        ) { ms, c ->
            // morph() is only read here and in graphicsLayer lambdas: the fold animates without recomposing.
            val m = morph().coerceIn(0f, 1f)
            val maxW = c.maxWidth
            val orbPx = ORB_SIZE.roundToPx()
            val gap = ORB_GAP.roundToPx()
            val orbP = if (hasMini && ms.size > 1) ms[1].measure(Constraints.fixed(orbPx, orbPx)) else null
            val pillH = if (orbP != null) ORB_PILL_H.roundToPx() else orbLerpI(ORB_PILL_H.roundToPx(), ORB_PILL_COMPACT_H.roundToPx(), m)
            val pillW = if (orbP != null) {
                // Pill sits left of the orb; in Mini mode it collapses toward the orb and fades.
                val full = (maxW - orbPx - gap).coerceAtLeast(0)
                orbLerpI(full, (full * 0.35f).toInt(), m).coerceAtLeast(1)
            } else {
                val compact = (ORB_SLOT_COMPACT * tabs.size + ORB_PAD * 2).roundToPx().coerceAtMost(maxW)
                orbLerpI(maxW, compact, m)
            }
            val pillP = ms[0].measure(Constraints.fixed(pillW, pillH))
            val h = if (orbP != null) orbPx else pillH
            layout(maxW, h) {
                if (orbP != null) {
                    val x = maxW - orbPx - gap - pillW
                    pillP.place(x.coerceAtLeast(0), (h - pillH) / 2, zIndex = -1f)
                    orbP.place(maxW - orbPx, 0)
                } else {
                    pillP.place((maxW - pillW) / 2, 0)
                }
            }
        }
    }
}

@Composable
private fun OrbTabs(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit, enabled: Boolean) {
    val haptics = rememberHaptics()
    val still = reducedMotion()
    val sel = tabs.indexOf(selected).coerceAtLeast(0)
    // Selection highlight position (in slots); read only in the draw lambda, so its glide never recomposes.
    val pos by animateFloatAsState(sel.toFloat(), if (still) snap() else spring(dampingRatio = 0.72f, stiffness = 260f), label = "orbLens")
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    Row(
        Modifier.fillMaxSize().liftBar().padding(horizontal = ORB_PAD).drawBehind { // 1.0.1: liftBar
            val n = tabs.size.coerceAtLeast(1)
            val slot = size.width / n
            val hlW = (min(slot, 52.dp.toPx()) - 4.dp.toPx()).coerceAtLeast(0f)
            val hlH = (size.height - 12.dp.toPx()).coerceAtLeast(0f)
            val cx = slot * (pos + 0.5f)
            drawRoundRect(primary.copy(alpha = 0.18f), Offset(cx - hlW / 2f, (size.height - hlH) / 2f), Size(hlW, hlH), CornerRadius(hlH / 2f))
            drawCircle(primary, 2.dp.toPx(), Offset(cx, size.height - 9.dp.toPx()))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, t ->
            val on = i == sel
            val interaction = remember { MutableInteractionSource() }
            val fg by animateColorAsState(if (on) primary else idle, if (still) snap() else tween(MotionSpec.MEDIUM), label = "orbFg")
            Box(
                Modifier.weight(1f).fillMaxHeight().pressScale(interaction, 0.86f)
                    .semantics { this.selected = on }
                    .then(if (enabled) Modifier.clickable(interaction, null, onClickLabel = t.label, role = Role.Tab) { if (!on) haptics.tick(); onSelect(t) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (on) t.selectedIcon else t.icon, t.label, Modifier.size(24.dp).offset(y = (-2).dp).liftSource(t, on, primary, dotAlpha = { 0f }), tint = fg) // 1.0.1: tab lift (this dock draws its own dot)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrbNowPlaying(onOpen: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: (Float) -> Unit, coverVisible: Boolean, onCoverBounds: (Rect) -> Unit) {
    val st by Graph.player.state.collectAsState()
    val posState = Graph.player.position.collectAsState()
    val err by Playback.error.collectAsState()
    val t = st.current ?: return
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val durationMs = st.durationMs
    Box(Modifier.size(ORB_SIZE)) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { translationX = swipe.value * 0.35f; alpha = 1f - (abs(swipe.value) / 600f).coerceIn(0f, 0.5f) }
                .shadow(18.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
                .liquidGlass(CircleShape)
                .combinedClickable(
                    onClickLabel = "Open player",
                    onLongClickLabel = "Open player",
                    onLongClick = { haptics.click(); onOpen() },
                    onClick = { if (err != null) Graph.player.retry(); onOpen() },
                )
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { onDragEnd(total) }, onDragCancel = { onDragEnd(0f) }) { _, dy -> total += dy; onDrag(dy) }
                }
                .pointerInput(t.id) {
                    detectHorizontalDragGestures(onDragEnd = {
                        val v = swipe.value
                        scope.launch {
                            if (abs(v) > 110f) { haptics.click(); if (v < 0) Graph.player.next() else Graph.player.previous() }
                            swipe.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 160f))
                        }
                    }) { _, dx -> scope.launch { swipe.snapTo(swipe.value + dx) } }
                }
                .drawWithContent {
                    drawContent()
                    // Thin progress ring; position is read only here, at draw time.
                    val frac = if (durationMs > 0) (posState.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    val sw = ORB_RING.toPx()
                    val inset = sw / 2f
                    val arcSize = Size(size.width - sw, size.height - sw)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
                    if (frac > 0f) drawArc(if (err != null) Color(0xFFE5484D) else primary, -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                },
            contentAlignment = Alignment.Center,
        ) {
            val coverSize = ORB_SIZE - ORB_RING * 2 - 4.dp
            Box(
                Modifier.size(coverSize).onGloballyPositioned { onCoverBounds(it.boundsInRoot()) }
                    .graphicsLayer { alpha = if (coverVisible) 1f else 0f },
            ) {
                Artwork(t.artUrl, Modifier.size(coverSize), CircleShape)
            }
        }
        // Small play/pause overlapping the orb's top-left corner.
        val ppI = remember { MutableInteractionSource() }
        Box(
            Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp).size(ORB_PP)
                .pressScale(ppI, 0.86f)
                .shadow(6.dp, CircleShape, clip = false)
                .clip(CircleShape).background(primary)
                .border(1.5.dp, MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape)
                .clickable(ppI, null, onClickLabel = if (st.playing) "Pause" else "Play", role = Role.Button) {
                    haptics.click(); if (err != null) Graph.player.retry() else Graph.player.toggle()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (st.buffering) CircularProgressIndicator(Modifier.size(ORB_PP - 4.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onPrimary)
            PlayPauseIcon(st.playing, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
