/*
 * Floating glass navigation. The side rail and mini player follow OpenTune 1.0.4 SideChrome.kt / WindowLayout.kt
 * (GPL-3.0-or-later), rewritten for UpZe. The v2.0.6 dock (expanded ↔ mini morph, liquid blob, art tint) is
 * UpZe's own design.
 */
package com.ravi.musicspot.ui.chrome

import com.ravi.musicspot.ui.motion.pressScale
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.ui.Tab
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.glass.glassShadow
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.rememberHaptics
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Expanded dock height (icons + labels). */
val DOCK_HEIGHT = 68.dp
/** Mini dock height (icons only). */
val DOCK_MINI_HEIGHT = 56.dp
val MINI_HEIGHT = 64.dp
/** Space between the mini player and the expanded dock (and beside the mini dock / search button). */
val CHROME_GAP = 8.dp
/** Outer side margin of the floating chrome. */
val CHROME_SIDE = 12.dp
/** Margin between the chrome and the navigation bar. */
val CHROME_BOTTOM = 12.dp
private val DOCK_INSET = 6.dp
private val DOCK_SHAPE = RoundedCornerShape(26.dp)
private val LENS = RoundedCornerShape(percent = 50)
/** Icon slot width in the mini (icons-only) dock. */
private const val MINI_SLOT = 44f

/**
 * Height the floating chrome takes at the bottom of the page (pages pad their lists by this). Always the EXPANDED
 * chrome, whatever the dock is doing, so the inset never changes while scrolling (no list jumps).
 */
fun chromeHeight(hasMini: Boolean): Dp = CHROME_BOTTOM + DOCK_HEIGHT + (if (hasMini) MINI_HEIGHT + CHROME_GAP else 0.dp) + 16.dp

// ---- geometry ------------------------------------------------------------------------------------------------------

private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
/** Smoothstep of [x] over [e0, e1]: 0 → 1 with zero velocity at both ends (no visible seams between phases). */
internal fun smooth(e0: Float, e1: Float, x: Float): Float { val t = clamp01((x - e0) / (e1 - e0)); return t * t * (3f - 2f * t) }
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Where everything in the bottom chrome sits, in dp, for one value of the dock progress. All y values are the
 * distance from the chrome's bottom edge to the element's bottom edge. [m] = mini morph (0 expanded … 1 mini),
 * [h] = hide (0 shown … 1 slid away).
 *
 * v2.0.7 layout: Search is its own round glass button beside the dock (as before v2.0.6), never a dock tab.
 *  - Expanded: [ dock (tabs + labels) ][gap][ search ●], the mini player full width above.
 *  - Mini with a song: the search button fades out, the dock folds into one exact circle at the right holding only
 *    the active tab's icon (no dot row) and the mini player glides down beside it.
 *  - Mini without a song: a compact split capsule, centred: [ icons-only pill with a solid accent lens ][gap][ ● ].
 */
internal data class DockFrame(
    val a: Float, val b: Float,
    val dockX: Float, val dockY: Float, val dockW: Float, val dockH: Float, val dockAlpha: Float,
    val miniX: Float, val miniY: Float, val miniW: Float,
    val searchX: Float, val searchY: Float, val searchSize: Float, val searchAlpha: Float,
    /** Total height of the chrome box (the expanded layout); elements are placed inside it from the bottom. */
    val boxH: Float,
)

internal fun miniDockWidth(hasMini: Boolean, tabs: Int): Float = if (hasMini) DOCK_MINI_HEIGHT.value else tabs * MINI_SLOT + 2 * DOCK_INSET.value

internal fun dockFrame(m: Float, h: Float, width: Float, hasMini: Boolean, tabs: Int, hideDrop: Float): DockFrame {
    val mm = clamp01(m); val hh = clamp01(h)
    val dE = DOCK_HEIGHT.value; val dM = DOCK_MINI_HEIGHT.value; val mp = MINI_HEIGHT.value; val gap = CHROME_GAP.value
    val sE = dE; val sM = dM
    val wM = miniDockWidth(hasMini, tabs).coerceAtMost(width)
    val a = if (hasMini) smooth(0f, 0.5f, mm) else smooth(0f, 1f, mm)
    val b = if (hasMini) smooth(0.45f, 1f, mm) else 0f
    val dockWE = width - sE - gap
    val dockW = lerp(dockWE, wM, a)
    val dockH = lerp(dE, dM, a)
    val searchSize: Float
    val searchX: Float
    val searchAlpha: Float
    val dockX: Float
    if (hasMini) {
        // Dock's right edge travels from beside the search button to the chrome's edge; search fades out first.
        searchSize = lerp(sE, sM * 0.7f, smooth(0f, 0.35f, mm))
        searchX = width - sE / 2f - searchSize / 2f
        searchAlpha = (1f - smooth(0f, 0.3f, mm)) * (1f - hh)
        // 1.0.0: the right edge used to reach the orb while it was still visible (m ≈ 0.05–0.25, worst on narrow
        // phones), so the dock slid under the fading Search orb. It now stops at the orb's left edge until the orb has
        // all but faded (alpha < 0.05), then continues to the chrome's edge (DockGeometryTest.searchNeverOverlapsDock…).
        val clear = clamp01((0.05f - searchAlpha) / 0.05f)
        val right = minOf(lerp(dockWE, width, smooth(0f, 0.35f, mm)), lerp(searchX - gap * searchAlpha, width, clear))
        dockX = right - dockW
    } else {
        searchSize = lerp(sE, sM, a)
        val group = dockW + gap + searchSize
        dockX = lerp(0f, (width - group) / 2f, a)
        searchX = dockX + dockW + gap
        searchAlpha = 1f - hh
    }
    // Mini state: dock centred on the mini player's row ((64 − 56) / 2 = 4 dp up). Hide: slides below the screen.
    val dockY = lerp(0f, (mp - dM) / 2f, b) - hideDrop * hh
    val searchY = (dE - searchSize) / 2f * (if (hasMini) 1f else (1f - a)) - hideDrop * hh
    val miniW = lerp(width, width - wM - gap, a)
    val miniY = (dE + gap) * (1f - b) * (1f - hh)
    val boxH = if (hasMini) dE + gap + mp else dE
    return DockFrame(a, b, dockX, dockY, dockW, dockH, 1f - hh, 0f, miniY, miniW, searchX, searchY, searchSize, searchAlpha, boxH)
}

// ---- the chrome ----------------------------------------------------------------------------------------------------

/**
 * Bottom chrome for phones: the mini player, the dock and the separate search button, laid out by one [Layout]
 * from [dockFrame]. The animated values are only read in layout/draw lambdas, so the morph never recomposes.
 */
@Composable
fun DockChrome(
    tabs: List<Tab>,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    hasMini: Boolean,
    morph: () -> Float,
    hide: () -> Float,
    /** True while the dock is (or is becoming) mini: decides what a tap on the dock does. */
    miniTarget: Boolean,
    onExpand: () -> Unit,
    hideDrop: Dp,
    modifier: Modifier = Modifier,
    miniPlayer: @Composable () -> Unit,
) {
    val dockTabs = tabs.filter { it != Tab.SEARCH }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = CHROME_SIDE)) {
        val widthDp = maxWidth.value
        val frameOf = { dockFrame(morph(), hide(), widthDp, hasMini, dockTabs.size, hideDrop.value) }
        Layout(
            content = {
                if (hasMini) Box(Modifier.layoutId("mini")) { miniPlayer() }
                Box(Modifier.layoutId("dock")) {
                    DockBody(dockTabs, selected, onSelect, hasMini, widthDp, frameOf, collapsedSingle = hasMini && miniTarget, onExpand = onExpand)
                }
                Box(Modifier.layoutId("search")) {
                    SearchOrb(selected == Tab.SEARCH, frameOf, enabled = !(hasMini && miniTarget)) { onSelect(Tab.SEARCH) }
                }
            },
        ) { measurables, constraints ->
            val f = frameOf()
            val w = constraints.maxWidth
            val boxH = f.boxH.dp.roundToPx()
            val placeables = measurables.map { mz ->
                when (mz.layoutId) {
                    "mini" -> mz.measure(Constraints.fixed(f.miniW.dp.roundToPx().coerceIn(0, w), MINI_HEIGHT.roundToPx())) to
                        IntOffset(f.miniX.dp.roundToPx(), boxH - (f.miniY.dp + MINI_HEIGHT).roundToPx())
                    "search" -> { val s = f.searchSize.dp.roundToPx().coerceAtLeast(1)
                        mz.measure(Constraints.fixed(s, s)) to IntOffset(f.searchX.dp.roundToPx(), boxH - (f.searchY.dp + f.searchSize.dp).roundToPx()) }
                    else -> mz.measure(Constraints.fixed(f.dockW.dp.roundToPx().coerceIn(0, w), f.dockH.dp.roundToPx())) to
                        IntOffset(f.dockX.dp.roundToPx(), boxH - (f.dockY.dp + f.dockH.dp).roundToPx())
                }
            }
            layout(w, boxH) { placeables.forEach { (p, at) -> p.place(at) } }
        }
    }
}

/** The round glass search button beside the dock; lit with the accent while Search is open. */
@Composable
private fun SearchOrb(selected: Boolean, frameOf: () -> DockFrame, enabled: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val accent = artAccent()
    val still = reducedMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !still) 0.88f else 1f, spring(dampingRatio = 0.6f, stiffness = 255f), label = "orbScale")
    val lit by animateFloatAsState(if (selected) 1f else 0f, if (still) tween(0) else spring(dampingRatio = 0.8f, stiffness = 194f), label = "orbLit")
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val base = Modifier.fillMaxSize()
        .graphicsLayer { val f = frameOf(); alpha = f.searchAlpha; scaleX = scale; scaleY = scale }
        .glassShadow(14.dp, CircleShape) // v2.0.17: no shadow showing through the clear Water film
        .glass(CircleShape, tint = androidx.compose.ui.graphics.lerp(surface, accent, 0.10f)).clip(CircleShape)
    Box(
        if (enabled) base.clickable(interactionSource = interaction, indication = null) { if (!selected) haptics.tick(); onClick() }
            .semantics { role = Role.Tab; this.selected = selected; contentDescription = "Search" }
        else base.clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        // Accent lens that swells in from the centre when Search is the open tab.
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 0.4f + 0.6f * lit; scaleY = 0.4f + 0.6f * lit; alpha = lit }.clip(CircleShape)
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.16f)))))
        Icon(
            if (selected) Icons.Rounded.Search else Icons.Outlined.Search, null,
            tint = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f), MaterialTheme.colorScheme.primary, lit),
            modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = -12f * (1f - lit) * (if (still) 0f else 1f) },
        )
    }
}

/** The artwork's vibrant colour (falls back to the theme primary), gliding when the song changes. */
@Composable
private fun artAccent(): Color {
    val st by Graph.player.state.collectAsState()
    val url = st.current?.artUrl
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    var picked by remember { androidx.compose.runtime.mutableStateOf<Color?>(null) }
    LaunchedEffect(url) { picked = if (url == null) null else runCatching { com.ravi.musicspot.ui.theme.artPalette(ctx, url)?.first }.getOrNull() ?: picked }
    val still = reducedMotion()
    val c by animateColorAsState(picked ?: primary, if (still) tween(0) else tween(980), label = "dockAccent")
    return c
}

@Composable
private fun DockBody(
    tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit, hasMini: Boolean, widthDp: Float,
    frameOf: () -> DockFrame, collapsedSingle: Boolean, onExpand: () -> Unit,
) {
    val haptics = rememberHaptics()
    val still = reducedMotion()
    val n = tabs.size
    val selIdx = tabs.indexOf(selected)
    val sel = selIdx.coerceAtLeast(0)
    val hasSel = selIdx >= 0
    val accent = artAccent()
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val film = androidx.compose.ui.graphics.lerp(surface, accent, 0.10f)

    // Active-tab lens: a spring on the tab index; its velocity stretches it while it travels (liquid drop).
    val blob = remember { Animatable(sel.toFloat()) }
    LaunchedEffect(sel, still) {
        if (still) blob.snapTo(sel.toFloat())
        else blob.animateTo(sel.toFloat(), spring(dampingRatio = 0.72f, stiffness = 184f))
    }
    val blobShown by animateFloatAsState(if (hasSel) 1f else 0f, if (still) tween(0) else tween(308), label = "blobShown")

    // Dock-local geometry, all in dp.
    val inset = DOCK_INSET.value
    val dockWE = widthDp - DOCK_HEIGHT.value - CHROME_GAP.value
    val slotE = (dockWE - 2 * inset) / n
    fun centerE(i: Float) = inset + slotE * (i + 0.5f)
    fun centerM(i: Float) = if (hasMini) DOCK_MINI_HEIGHT.value / 2f else inset + MINI_SLOT * (i + 0.5f)
    fun center(i: Float, a: Float) = lerp(centerE(i), centerM(i), a)
    // Icon centre from the dock's top: expanded leaves room for the label; mini centres it exactly.
    fun iconY(a: Float) = lerp(24f, DOCK_MINI_HEIGHT.value / 2f, a)
    // Solid accent lens in the song-less mini capsule (its signature look); translucent everywhere else.
    fun solid(a: Float) = if (hasMini) 0f else smooth(0.5f, 1f, a)

    val shapeMod = Modifier.fillMaxSize()
        .graphicsLayer {
            val f = frameOf()
            alpha = f.dockAlpha
            // A full capsule at every size: the folded dock is an exact circle.
            this.shape = LENS; clip = true
        }
        .glass(LENS, tint = film)
    val expandI = remember { MutableInteractionSource() }
    val clear = com.ravi.musicspot.ui.glass.glassIsClear() // v2.0.17
    Box(
        Modifier.fillMaxSize().then(if (clear) Modifier else Modifier.shadowFollowing(frameOf)).then(
            if (collapsedSingle) shapeMod.clickable(interactionSource = expandI, indication = null, onClickLabel = "Show navigation") { haptics.tick(); onExpand() }
                .semantics { contentDescription = "Navigation, ${selected.label}" }
            else shapeMod,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.10f)))))

        // The lens.
        Box(
            Modifier.layout { m, _ ->
                val f = frameOf(); val a = f.a
                val fi = blob.value
                val lo = kotlin.math.floor(fi).toInt().coerceIn(0, n - 1); val hi = (lo + 1).coerceAtMost(n - 1)
                val cx = lerp(center(lo.toFloat(), a), center(hi.toFloat(), a), fi - lo)
                val stretch = if (still) 0f else (kotlin.math.abs(blob.velocity) * 4f).coerceAtMost(lerp(slotE, MINI_SLOT, a) * 0.5f)
                val bw = lerp(slotE - 8f, 40f, a) + stretch * (1f - a)
                val bh = (lerp(DOCK_HEIGHT.value - 2 * inset, 40f, a) - stretch * 0.22f * (1f - a)).coerceAtLeast(24f)
                val cy = lerp(DOCK_HEIGHT.value / 2f, iconY(a), a)
                val p = m.measure(Constraints.fixed(bw.dp.roundToPx(), bh.dp.roundToPx()))
                layout(0, 0) { p.place((cx - bw / 2f).dp.roundToPx(), (cy - bh / 2f).dp.roundToPx()) }
            }.graphicsLayer { alpha = blobShown }.clip(LENS)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.14f))))
                .border(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.50f), accent.copy(alpha = 0.08f))), LENS),
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = solid(frameOf().a) }.background(accent))
        }

        tabs.forEachIndexed { i, t ->
            val on = i == selIdx
            DockItem(
                tab = t, selected = on, enabled = !collapsedSingle,
                place = { f ->
                    val a = f.a
                    val w = lerp(slotE, if (hasMini) DOCK_MINI_HEIGHT.value else MINI_SLOT, a)
                    Triple(center(i.toFloat(), a) - w / 2f, w, iconY(a))
                },
                frameOf = frameOf,
                hideWhenFolded = hasMini && !on,
                onAccent = onAccent,
                solid = { if (on) solid(frameOf().a) else 0f },
            ) { if (!on) haptics.tick(); onSelect(t) }
        }

        // Folded circle while Search is open: show the search glyph instead of a tab.
        if (hasMini && !hasSel) Icon(
            Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).size(24.dp).graphicsLayer { alpha = smooth(0.4f, 1f, frameOf().a) },
        )
    }
}

/** The dock's soft shadow, following its morphing corner radius. */
private fun Modifier.shadowFollowing(frameOf: () -> DockFrame): Modifier = this.graphicsLayer {
    val f = frameOf()
    shadowElevation = 14.dp.toPx() * f.dockAlpha
    shape = LENS
    ambientShadowColor = Color.Black.copy(alpha = 0.30f)
    spotShadowColor = Color.Black.copy(alpha = 0.40f)
    clip = false
}

@Composable
private fun DockItem(
    tab: Tab, selected: Boolean, enabled: Boolean,
    /** Given the frame: (left x, width, icon centre y) in dp, dock-local. */
    place: (DockFrame) -> Triple<Float, Float, Float>,
    frameOf: () -> DockFrame,
    hideWhenFolded: Boolean,
    onAccent: Color,
    solid: () -> Float,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val still = reducedMotion()
    val scale by animateFloatAsState(when { still -> 1f; pressed -> 0.88f; selected -> 1.06f; else -> 1f }, spring(dampingRatio = 0.85f, stiffness = 163f), label = "dockScale")
    val hop = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected && !still) {
            hop.snapTo(0f)
            hop.animateTo(1f, tween(154, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 214f))
        } else hop.snapTo(0f)
    }
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f), tween(if (still) 0 else 300, easing = FastOutSlowInEasing), label = "fg")
    val base = Modifier.layout { m, c ->
        val (x, w, _) = place(frameOf())
        val p = m.measure(Constraints.fixed(w.dp.roundToPx().coerceAtLeast(1), c.maxHeight))
        layout(0, 0) { p.place(x.dp.roundToPx(), 0) }
    }.graphicsLayer { if (hideWhenFolded) alpha = 1f - smooth(0f, 0.7f, frameOf().a) }
    val iconMod = Modifier
        .layout { m, c ->
            val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
            val (_, w, iy) = place(frameOf())
            layout(c.maxWidth, c.maxHeight) { p.place(((w.dp.toPx() - p.width) / 2f).toInt(), (iy.dp.toPx() - p.height / 2f).toInt()) }
        }
        .size(24.dp)
    Box(
        if (enabled) base.clickable(interactionSource = interaction, indication = null, onClick = onClick).semantics { role = Role.Tab; this.selected = selected; contentDescription = tab.label }
        else base.clearAndSetSemantics { },
    ) {
        val icon = if (selected) tab.selectedIcon else tab.icon
        Icon(icon, null, tint = fg, modifier = iconMod
            .graphicsLayer { val b = hop.value; val s = scale * (1f + 0.14f * b); scaleX = s; scaleY = s; translationY = -4.dp.toPx() * b; alpha = 1f - solid() })
        if (selected) Icon(icon, null, tint = onAccent, modifier = iconMod
            .graphicsLayer { val b = hop.value; val s = scale * (1f + 0.14f * b); scaleX = s; scaleY = s; translationY = -4.dp.toPx() * b; alpha = solid() })
        Text(tab.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, softWrap = false,
            modifier = Modifier
                .layout { m, c ->
                    val p = m.measure(c.copy(minWidth = 0, minHeight = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
                    val (_, w, iy) = place(frameOf())
                    layout(c.maxWidth, c.maxHeight) { p.place(((w.dp.toPx() - p.width) / 2f).toInt(), (iy + 15f).dp.roundToPx()) }
                }
                .graphicsLayer { alpha = 1f - smooth(0f, 0.4f, frameOf().a) })
    }
}

/** Side rail for wide windows (≥600dp), glass, with the same tabs. */
@Composable
fun SideRail(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Column(
        modifier.windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(12.dp).width(80.dp).fillMaxHeight().glass(RoundedCornerShape(28.dp)).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("MS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 12.dp))
        tabs.forEach { t ->
            val on = t == selected
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent, label = "rail")
            Column(
                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(bg).clickable { if (!on) haptics.tick(); onSelect(t) }.semantics { role = Role.Tab; this.selected = on },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(if (on) t.selectedIcon else t.icon, null, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * The mini player card, docked above the pill. Tap or swipe up opens the player (the drag drives the same
 * progress the player uses, so the cover follows the finger); swipe left/right skips. Nothing in it scrolls.
 */
@Composable
fun MiniPlayer(
    onOpen: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    coverVisible: Boolean,
    onCoverBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val st by Graph.player.state.collectAsState()
    // v2.0.14b: position is collected inside MiniProgressLine.
    val err by Playback.error.collectAsState()
    val t = st.current ?: return
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val shape = Ms.r20
    Box(
        modifier.fillMaxWidth().height(MINI_HEIGHT)
            .graphicsLayer { translationX = swipe.value; alpha = 1f - (abs(swipe.value) / 600f).coerceIn(0f, 0.6f) }
            .glassShadow(14.dp, shape).glass(shape, strong = true) // v2.0.17
            .clickable(onClickLabel = "Open player") { onOpen() }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { onDragEnd(total) }, onDragCancel = { onDragEnd(0f) }) { _, dy -> total += dy; onDrag(dy) }
            }
            .pointerInput(t.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = swipe.value
                        scope.launch {
                            if (abs(v) > 140f) { haptics.click(); if (v < 0) Graph.player.next() else Graph.player.previous() }
                            swipe.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 255f))
                        }
                    },
                ) { _, dx -> scope.launch { swipe.snapTo(swipe.value + dx) } }
            },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.artUrl, Modifier.size(48.dp).onGloballyPositioned { onCoverBounds(it.boundsInRoot()) }.graphicsLayer { alpha = if (coverVisible) 1f else 0f }, Ms.r12)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(err?.let { "Couldn't play · tap to retry" } ?: t.artist, style = MaterialTheme.typography.bodySmall,
                    color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (st.buffering) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 2.dp)
                val ppI = remember { MutableInteractionSource() }
                IconButton(onClick = { haptics.click(); if (err != null) Graph.player.retry() else Graph.player.toggle() }, interactionSource = ppI, modifier = Modifier.pressScale(ppI, 0.88f)) {
                    com.ravi.musicspot.ui.motion.PlayPauseIcon(st.playing)
                }
            }
            IconButton(onClick = { haptics.click(); Graph.player.next() }) { Icon(Icons.Rounded.SkipNext, "Next") }
        }
        // Progress as a 2dp line clipped inside the card's rounded shape.
        // v2.0.17: lifted 5dp and inset 18dp so it sits inside the r20 corners instead of being cut by them at the edge.
        MiniProgressLine(st.durationMs, Modifier.align(Alignment.BottomStart).padding(bottom = 5.dp)) // v2.0.14b
    }
}

/** v2.0.14b: the mini player's progress line; the only part that reads position, so only it recomposes. */
@Composable
private fun MiniProgressLine(durationMs: Long, modifier: Modifier = Modifier) {
    val pos by Graph.player.position.collectAsState()
    val frac = if (durationMs > 0) (pos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(modifier.padding(horizontal = 18.dp).fillMaxWidth().height(2.dp).clip(LENS).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
    }
}
