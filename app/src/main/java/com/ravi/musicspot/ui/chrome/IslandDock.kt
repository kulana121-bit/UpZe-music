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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.ravi.musicspot.ui.motion.pressScale
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs

private val ISLAND = RoundedCornerShape(32.dp)
private val ISLAND_PILL = RoundedCornerShape(percent = 50)
private val TAB_ROW = 60.dp

/**
 * v2.0.10 "Island" dock (Settings → Appearance → Dock design). The mini player and all five tabs (Search included)
 * live in ONE liquid-glass island with a soft hairline between them. The active tab is a glass lens that slides on a
 * spring and opens out to show its label (M3 expressive nav); the other tabs are icons only.
 * Same behaviours as the classic dock: Mini mode folds the tab row away (only the now-playing strip stays, or a
 * compact icon pill when nothing plays); Hide slides the island off the bottom; a tap on the folded island expands it.
 * The mini player keeps swipe left/right to skip, swipe up to open and the cover morph into Now Playing.
 */
@Composable
fun IslandDock(
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
    Box( // 1.0.0: was BoxWithConstraints with an unused scope (lint UnusedBoxWithConstraintsScope; extra subcomposition)
        modifier.fillMaxWidth().padding(horizontal = CHROME_SIDE)) {
        // Width: full when expanded; when folded without a song, a compact pill sized for icons.
        val compactW = (tabs.size * 48 + 16).dp
        Layout(content = {
            Column(
                Modifier.fillMaxWidth()
                    .shadow(18.dp, ISLAND, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
                    .liquidGlass(ISLAND)
                    .then(if (folded) Modifier.clickable(onClickLabel = "Show navigation") { haptics.tick(); onExpand() } else Modifier),
            ) {
                if (hasMini) IslandNowPlaying(onOpen, onDrag, onDragEnd, coverVisible, onCoverBounds, enabled = true)
                // Tab row folds away with the morph (height in layout, so nothing recomposes per frame).
                Layout(content = {
                    Column {
                        if (hasMini) Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), Color.Transparent))))
                        IslandTabs(tabs, selected, onSelect, enabled = !folded, compact = { if (hasMini) 0f else morph() })
                    }
                }, modifier = Modifier.graphicsLayer {
                    val m = morph(); alpha = if (hasMini) (1f - m * 1.6f).coerceIn(0f, 1f) else 1f
                }) { ms, c ->
                    val p = ms.first().measure(c.copy(minHeight = 0))
                    val m = morph()
                    val h = if (hasMini) (p.height * (1f - m)).toInt().coerceAtLeast(0) else lerpI(p.height, TAB_ROW.roundToPx() - 6.dp.roundToPx(), m)
                    layout(p.width, h) { p.place(0, 0) }
                }
            }
        }, modifier = Modifier.graphicsLayer { translationY = hideDrop.toPx() * hide(); alpha = 1f - hide() }) { ms, c ->
            val m = morph()
            val w = if (hasMini) c.maxWidth else lerpI(c.maxWidth, compactW.roundToPx().coerceAtMost(c.maxWidth), m)
            val p = ms.first().measure(Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = c.maxHeight))
            layout(c.maxWidth, p.height) { p.place((c.maxWidth - w) / 2, 0) }
        }
    }
}

private fun lerpI(a: Int, b: Int, t: Float): Int = (a + (b - a) * t.coerceIn(0f, 1f)).toInt()

@Composable
private fun IslandTabs(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit, enabled: Boolean, compact: () -> Float) {
    val haptics = rememberHaptics()
    val still = reducedMotion()
    val n = tabs.size
    val sel = tabs.indexOf(selected).coerceAtLeast(0)
    // Active tab gets 2.2× the width of the others so its label fits; widths and the lens move on one spring.
    val weights = tabs.map { t ->
        animateFloatAsState(if (t == selected) 2.2f else 1f, if (still) snap() else spring(dampingRatio = 0.78f, stiffness = 150f), label = "islandW").value
    }
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(TAB_ROW).padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { i, t ->
            val on = i == sel
            val interaction = remember { MutableInteractionSource() }
            val lens by animateFloatAsState(if (on) 1f else 0f, if (still) snap() else MotionSpec.popSpring(), label = "islandLens")
            val fg by animateColorAsState(if (on) primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f), if (still) snap() else tween(MotionSpec.MEDIUM), label = "islandFg")
            Box(
                Modifier.weight(weights[i]).fillMaxHeight().pressScale(interaction, 0.88f)
                    .then(if (enabled) Modifier.clickable(interaction, null, onClickLabel = t.label) { if (!on) haptics.tick(); onSelect(t) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = lens; scaleX = 0.6f + 0.4f * lens; scaleY = 0.75f + 0.25f * lens }
                    .clip(ISLAND_PILL)
                    .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.30f), primary.copy(alpha = 0.14f)))))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    AnimatedContent(on, transitionSpec = { com.ravi.musicspot.ui.motion.iconSwap(still) }, label = "islandIcon") { o ->
                        Icon(if (o) t.selectedIcon else t.icon, t.label, Modifier.size(24.dp), tint = fg)
                    }
                    if (on) {
                        Spacer(Modifier.width(6.dp))
                        Text(t.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, softWrap = false,
                            modifier = Modifier.graphicsLayer { alpha = (lens * (1f - compact() * 1.4f)).coerceIn(0f, 1f) })
                    }
                }
            }
        }
    }
}

@Composable
private fun IslandNowPlaying(onOpen: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: (Float) -> Unit, coverVisible: Boolean, onCoverBounds: (Rect) -> Unit, enabled: Boolean) {
    val st by Graph.player.state.collectAsState()
    // v2.0.14b: keep the State; its value is read only inside the progress lambda (draw phase), not in composition.
    val posState = Graph.player.position.collectAsState()
    val err by Playback.error.collectAsState()
    val t = st.current ?: return
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val still = reducedMotion()
    val swipe = remember { Animatable(0f) }
    Box(
        Modifier.fillMaxWidth().height(56.dp)
            .clickable(enabled = enabled, onClickLabel = "Open player") { onOpen() }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { onDragEnd(total) }, onDragCancel = { onDragEnd(0f) }) { _, dy -> total += dy; onDrag(dy) }
            }
            .pointerInput(t.id) {
                detectHorizontalDragGestures(onDragEnd = {
                    val v = swipe.value
                    scope.launch {
                        if (abs(v) > 140f) { haptics.click(); if (v < 0) Graph.player.next() else Graph.player.previous() }
                        swipe.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 160f))
                    }
                }) { _, dx -> scope.launch { swipe.snapTo(swipe.value + dx) } }
            },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp).graphicsLayer { translationX = swipe.value * 0.6f; alpha = 1f - (abs(swipe.value) / 700f).coerceIn(0f, 0.5f) },
            verticalAlignment = Alignment.CenterVertically) {
            // Artwork with a soft spinning progress ring around it.
            val durMs = st.durationMs // v2.0.14b
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { if (durMs > 0) (posState.value.toFloat() / durMs).coerceIn(0f, 1f) else 0f }, modifier = Modifier.size(44.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                Artwork(t.artUrl, Modifier.size(37.dp).onGloballyPositioned { onCoverBounds(it.boundsInRoot()) }
                    .graphicsLayer { alpha = if (coverVisible) 1f else 0f }, CircleShape)
            }
            Spacer(Modifier.width(10.dp))
            AnimatedContent(t, contentKey = { it.id }, transitionSpec = { MotionSpec.sharedAxisX(true, still) }, label = "islandTitle", modifier = Modifier.weight(1f)) { tt ->
                Column {
                    Text(tt.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(err?.let { "Couldn't play · tap to retry" } ?: tt.artist, style = MaterialTheme.typography.bodySmall,
                        color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val ppI = remember { MutableInteractionSource() }
            Box(Modifier.size(38.dp).pressScale(ppI, 0.86f).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                .clickable(ppI, null, onClickLabel = if (st.playing) "Pause" else "Play") { haptics.click(); if (err != null) Graph.player.retry() else Graph.player.toggle() },
                contentAlignment = Alignment.Center) {
                if (st.buffering) CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 2.dp)
                com.ravi.musicspot.ui.motion.PlayPauseIcon(st.playing)
            }
            val nI = remember { MutableInteractionSource() }
            Box(Modifier.size(38.dp).pressScale(nI, 0.86f).clip(CircleShape).clickable(nI, null, onClickLabel = "Next") { haptics.click(); Graph.player.next() },
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SkipNext, "Next") }
        }
    }
}
