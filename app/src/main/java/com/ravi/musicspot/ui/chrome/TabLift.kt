// 1.0.1: gooey "lift" tab selection for every dock: the tapped tab's icon rises out of the bar as a round glass
// bubble on a metaball neck, the neck stretches and snaps, and the bubble drops back into its slot.
package com.ravi.musicspot.ui.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.ui.Tab
import com.ravi.musicspot.ui.glass.glass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 1.0.1: shared state of the tab-lift effect. Docks register where each tab icon and the bar edge are (window px,
 * written from onGloballyPositioned into plain arrays: no state writes, no allocation); [lift] starts the animation;
 * [TabLiftOverlay] (drawn once above the chrome by AppRoot) reads everything in the layout/draw phase only, so the
 * animation never recomposes anything.
 */
object TabLift {
    private val icons = Array(Tab.entries.size) { FloatArray(4) { Float.NaN } } // left, top, right, bottom
    /** Bar edge the bubble leaves from: [0] = edge coordinate (top y, or right x for the side rail), [1] = 1 for the rail. */
    private val bar = FloatArray(2) { Float.NaN }
    internal val origin = FloatArray(2) // overlay's own window offset
    /** 0 = resting in the bar, 1 = fully lifted (springs overshoot a little). */
    val progress = Animatable(0f)
    /** The tab currently lifted, or null (changes once per tap; drives a single recomposition of the overlay). */
    var tab by mutableStateOf<Tab?>(null); private set
    private var job: Job? = null

    internal fun registerIcon(t: Tab, x: Float, y: Float, w: Float, h: Float) { val a = icons[t.ordinal]; a[0] = x; a[1] = y; a[2] = x + w; a[3] = y + h }
    internal fun registerBar(edge: Float, rail: Boolean) { bar[0] = edge; bar[1] = if (rail) 1f else 0f }
    internal fun icon(t: Tab): FloatArray = icons[t.ordinal]
    internal fun barEdge(): Float = bar[0]
    internal fun isRail(): Boolean = bar[1] == 1f

    /** Lift [t]'s icon out of the bar and drop it back as the selected tab. No-op under Reduce motion. */
    fun lift(t: Tab, scope: CoroutineScope, still: Boolean) {
        if (still || icons[t.ordinal][0].isNaN() || bar[0].isNaN()) return
        job?.cancel()
        tab = t
        job = scope.launch {
            try {
                progress.snapTo(0f)
                if (Graph.liteFx) {
                    progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 900f))
                    progress.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 700f))
                } else {
                    progress.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 420f))
                    delay(60)
                    progress.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 330f))
                }
            } finally { if (tab == t) tab = null }
        }
    }

    /** Alpha for the icon resting in the bar: it hides while its bubble is out. Read in draw only. */
    internal fun inBarAlpha(t: Tab): Float {
        if (tab != t) return 1f
        val p = progress.value
        return (1f - p * 3f).coerceIn(0f, 1f)
    }
}

/**
 * 1.0.1: put on a dock's tab icon. Registers its window bounds for [TabLift], hides it while it is lifted, and when
 * [selected] draws the active-tab glow (the bar's tint shifting toward the selected tab) behind it and, scaled by
 * [dotAlpha], the small dot under it. Everything reads state in draw.
 */
fun Modifier.liftSource(tab: Tab, selected: Boolean, accent: Color, dotAlpha: () -> Float = { 1f }, register: Boolean = true): Modifier = this
    .then(if (register) Modifier.onGloballyPositioned { c ->
        val p = c.positionInWindow(); TabLift.registerIcon(tab, p.x, p.y, c.size.width.toFloat(), c.size.height.toFloat())
    } else Modifier)
    .graphicsLayer { alpha = TabLift.inBarAlpha(tab) }
    .drawWithCache {
        // Glow brush built once per size (not per frame); the dot alpha is read at draw time.
        val r = size.minDimension * 1.25f
        val c = Offset(size.width / 2f, size.height / 2f)
        val glow = Brush.radialGradient(listOf(accent.copy(alpha = 0.20f), Color.Transparent), c, r)
        val dotR = 2.dp.toPx(); val dotAt = Offset(size.width / 2f, size.height + 5.dp.toPx())
        onDrawWithContent {
            if (selected) drawCircle(glow, r, c)
            drawContent()
            if (selected) { val a = dotAlpha(); if (a > 0.01f) drawCircle(accent, dotR, dotAt, alpha = a) }
        }
    }

/** 1.0.1: put on the bar a dock's tabs sit in (its top edge, or the right edge for the side rail). */
fun Modifier.liftBar(rail: Boolean = false): Modifier = this.onGloballyPositioned { c ->
    val p = c.positionInWindow()
    TabLift.registerBar(if (rail) p.x + c.size.width else p.y, rail)
}

/**
 * 1.0.1: the lifted bubble + gooey neck, drawn above the chrome. The neck is one cached Path rebuilt in place each
 * frame (rewind, no allocation) from cubic béziers between the bar edge and the bubble; it thins as the bubble rises
 * and snaps once the gap passes ~26 dp. The bubble is real glass (backdrop blur) on Android 12+, and a frosted
 * translucent fill with a light rim on Android 8–11 and lite-effects devices; lite devices also skip the neck and use a
 * short lift.
 */
@Composable
fun TabLiftOverlay(accent: Color) {
    val t = TabLift.tab ?: return
    val lite = Graph.liteFx
    val blur = android.os.Build.VERSION.SDK_INT >= 31 && !lite
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val frost = androidx.compose.ui.graphics.lerp(surface, accent, 0.14f).copy(alpha = 0.86f)
    val rim = Color.White.copy(alpha = 0.32f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val radiusPx = with(density) { 24.dp.toPx() }
    val liftPx = with(density) { (if (lite) 16.dp else 44.dp).toPx() }
    val snapPx = with(density) { 26.dp.toPx() }
    // Bubble centre for the current frame, in overlay-local px; shared by the neck Canvas and the bubble layout.
    val geo = remember { FloatArray(6) } // bx, by, ex, ey (edge point), alongX, alongY

    fun compute(): Boolean {
        val ic = TabLift.icon(t); val edge = TabLift.barEdge()
        if (ic[0].isNaN() || edge.isNaN()) return false
        val cx = (ic[0] + ic[2]) / 2f - TabLift.origin[0]; val cy = (ic[1] + ic[3]) / 2f - TabLift.origin[1]
        val p = TabLift.progress.value
        if (TabLift.isRail()) {
            val ex = edge - TabLift.origin[0]
            geo[0] = cx + (ex + liftPx - cx) * p; geo[1] = cy; geo[2] = ex; geo[3] = cy; geo[4] = 1f; geo[5] = 0f
        } else {
            val ey = edge - TabLift.origin[1]
            geo[0] = cx; geo[1] = cy + (ey - liftPx - cy) * p; geo[2] = cx; geo[3] = ey; geo[4] = 0f; geo[5] = -1f
        }
        return true
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { c -> val p = c.positionInWindow(); TabLift.origin[0] = p.x; TabLift.origin[1] = p.y }) {
        if (!lite) Box(Modifier.fillMaxSize().drawWithCache {
            val path = Path()
            onDrawBehind {
                if (!compute()) return@onDrawBehind
                val bx = geo[0]; val by = geo[1]; val ex = geo[2]; val ey = geo[3]; val ax = geo[4]; val ay = geo[5]
                val px = -ay; val py = ax // perpendicular to "out"
                val gap = (bx - ex) * ax + (by - ey) * ay - radiusPx
                if (gap > snapPx || gap < -radiusPx * 1.6f) return@onDrawBehind
                val k = (gap / snapPx).coerceIn(0f, 1f)
                val w0 = radiusPx * (1.05f - 0.55f * k)       // half-width where the neck meets the bar
                val waist = radiusPx * (0.62f - 0.5f * k)     // pinches as it stretches, then snaps
                val c = 0.64f; val s = 0.77f                  // attach points on the bubble (±50° from its base)
                val a1x = bx - ax * radiusPx * c + px * radiusPx * s; val a1y = by - ay * radiusPx * c + py * radiusPx * s
                val a2x = bx - ax * radiusPx * c - px * radiusPx * s; val a2y = by - ay * radiusPx * c - py * radiusPx * s
                val mid = (gap * 0.5f).coerceAtLeast(0f)
                path.rewind()
                path.moveTo(ex + px * w0, ey + py * w0)
                path.cubicTo(ex + px * waist + ax * mid, ey + py * waist + ay * mid, a1x - ax * mid * 0.6f, a1y - ay * mid * 0.6f, a1x, a1y)
                path.lineTo(a2x, a2y)
                path.cubicTo(a2x - ax * mid * 0.6f, a2y - ay * mid * 0.6f, ex - px * waist + ax * mid, ey - py * waist + ay * mid, ex - px * w0, ey - py * w0)
                path.close()
                drawPath(path, frost)
            }
        })
        val glassMod = if (blur) Modifier.glass(CircleShape, tint = androidx.compose.ui.graphics.lerp(surface, accent, 0.12f))
            else Modifier.drawWithCache {
                val stroke = Stroke(1.dp.toPx())
                val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), 0f, size.height * 0.6f)
                onDrawBehind { drawCircle(frost); drawCircle(rim, style = stroke); drawCircle(sheen) }
            }
        Box(
            Modifier.layout { m, _ ->
                val d = (radiusPx * 2).roundToInt()
                val pl = m.measure(Constraints.fixed(d, d))
                layout(0, 0) { if (compute()) pl.place((geo[0] - radiusPx).roundToInt(), (geo[1] - radiusPx).roundToInt()) }
            }.graphicsLayer {
                // Swells a little at the top of the lift; fades in as it leaves the slot and out as it lands.
                val p = TabLift.progress.value
                val sc = 0.8f + 0.25f * p.coerceIn(0f, 1.2f)
                scaleX = sc; scaleY = sc; alpha = (p * 4f).coerceIn(0f, 1f)
                shadowElevation = 10.dp.toPx() * p.coerceIn(0f, 1f); shape = CircleShape; clip = false
            }.then(glassMod),
            contentAlignment = Alignment.Center,
        ) {
            Icon(t.selectedIcon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
